package cn.lunadeer.reColorfulMap.services;

import cn.lunadeer.reColorfulMap.ImageMapItem;
import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.SafeMessageFormat;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;

import static cn.lunadeer.reColorfulMap.services.ArtworkWallGeometry.*;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.entity.GlowItemFrame;

/** Item frames carry small image paths in their map items' PDC. */
public final class ArtworkWallService implements AutoCloseable {

    // Protection plugins must see each affected frame. Do not process our own probes recursively.
    private final ArtworkProtection protection = new ArtworkProtection();
    private static final boolean FOLIA = ArtworkWallGeometry.isFolia();
    // Holds admission until the owner-thread callback ends, bounding prepared thumbnail memory too.
    private final Semaphore operations = new Semaphore(64);
    private final Set<UUID> pendingPlacements = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingPickups = ConcurrentHashMap.newKeySet();
    private final Set<UUID> preloading = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, Long> unavailableMetadata = new java.util.LinkedHashMap<>();
    private final ReColorfulMap plugin;
    private final Executor background;
    private final ThreadPoolExecutor ownedExecutor;
    private volatile boolean closed;

    public ArtworkWallService(ReColorfulMap plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin);
        ownedExecutor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), task -> {
                    Thread thread = new Thread(task, "ReColorfulMap-wall-files");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        ownedExecutor.allowCoreThreadTimeOut(true);
        background = ownedExecutor;
    }

    /** Injected scheduler allows deterministic preparation/commit race tests. */
    public ArtworkWallService(ReColorfulMap plugin, Executor background) {
        this.plugin = java.util.Objects.requireNonNull(plugin);
        this.background = java.util.Objects.requireNonNull(background);
        ownedExecutor = null;
    }

    public boolean isProtectionProbe() {
        return protection.isProbe();
    }

    /** Loading an item prewarms only small immutable dimensions, never PNG pixels. */
    public void preload(UUID artwork) {
        if (closed || storage().cachedArtwork(artwork).isPresent() || metadataUnavailable(artwork) || !preloading.add(artwork)) {
            return;
        }
        try {
            background.execute(() -> {
                try {
                    if (!closed) storage().artwork(artwork);
                } catch (Exception failure) {
                    synchronized (unavailableMetadata) {
                        unavailableMetadata.put(artwork, System.nanoTime());
                        while (unavailableMetadata.size() > 4096) {
                            unavailableMetadata.remove(unavailableMetadata.keySet().iterator().next());
                        }
                    }
                    XLogger.warn("Cannot read artwork metadata %s: %s", artwork, failure.getMessage());
                } finally {
                    preloading.remove(artwork);
                }
            });
        } catch (RejectedExecutionException full) {
            preloading.remove(artwork);
        }
    }

    private boolean metadataUnavailable(UUID artwork) {
        synchronized (unavailableMetadata) {
            Long failure = unavailableMetadata.get(artwork);
            if (failure == null) return false;
            if (System.nanoTime() - failure < TimeUnit.SECONDS.toNanos(30)) return true;
            unavailableMetadata.remove(artwork);
            return false;
        }
    }

    private void completePlacement(UUID player) {
        if (pendingPlacements.remove(player)) operations.release();
    }

    private void completePickup(UUID artwork) {
        if (pendingPickups.remove(artwork)) operations.release();
    }

    @Override
    public void close() {
        closed = true;
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
        pendingPlacements.clear();
        pendingPickups.clear();
        preloading.clear();
        synchronized (unavailableMetadata) { unavailableMetadata.clear(); }
    }

    private record Placement(UUID playerId, Player player, ItemFrame originFrame, Location origin,
                             BlockFace facing, World world, ItemStack held,
                             MapStorage store, UUID artworkId, MapStorage.Artwork artwork) { }

    private record Pickup(ItemFrame clicked, Location location, BlockFace facing,
                          MapStorage.ImageReference reference, MapStorage.Artwork artwork,
                          Player player, HangingBreakEvent hanging, EntityDamageByEntityEvent damage,
                          boolean dropFrame) { }

    public void place(Player player, ItemFrame clicked, ItemStack held) {
        if (closed || !pendingPlacements.add(player.getUniqueId())) return;
        if (!operations.tryAcquire()) {
            pendingPlacements.remove(player.getUniqueId());
            reportFailure(player, new IllegalStateException(Language.imageMapEvent.operationBusy));
            return;
        }
        try {
            MapStorage.ImageReference reference = MapItemData.requireReference(held);
            if (!reference.thumbnail()) throw new IllegalStateException(Language.imageMapItem.imageCorrupt);
            plugin.getMapViewRegistry().register(held);
            Placement placement = new Placement(player.getUniqueId(), player, clicked, clicked.getLocation().clone(),
                    clicked.getFacing(), clicked.getWorld(), held.clone(), storage(), reference.artwork(), null);
            background.execute(() -> preparePlacement(placement));
        } catch (Exception failure) {
            completePlacement(player.getUniqueId());
            reportFailure(player, failure);
        }
    }

    /** Only immutable artwork identity and file storage are accessed on this worker. */
    private void preparePlacement(Placement request) {
        try {
            if (closed) return;
            MapStorage.Artwork artwork = request.store().artwork(request.artworkId());
            request.store().validateArtwork(artwork);
            Placement prepared = new Placement(request.playerId(), request.player(), request.originFrame(),
                    request.origin(), request.facing(), request.world(), request.held(), request.store(),
                    request.artworkId(), artwork);
            onPlayerThread(prepared, () -> createPlacement(prepared));
        } catch (Exception failure) {
            failOnPlayerThread(request, failure);
        }
    }

    /** Bukkit map and item creation belongs to the player's server/region thread. */
    private void createPlacement(Placement placement) {
        try {
            ItemFrame[][] frames = placementFrames(placement);
            protection.checkPlacementPermission(placement.player(), frames);
            placementFrames(placement);
            ItemStack[][] tiles = createTiles(placement);
            finishPlacement(placement, tiles);
        } catch (Exception failure) {
            finishFailure(placement, failure);
        }
    }

    private void finishPlacement(Placement placement, ItemStack[][] tiles) {
        try {
            ItemFrame[][] frames = placementFrames(placement);
            protection.checkPlacementPermission(placement.player(), frames);
            // Protection callbacks may change the world or inventory. Recheck before committing.
            placementFrames(placement);
            commitPlacement(placement, frames, tiles);
        } catch (Exception failure) {
            reportFailure(placement.player(), failure);
        } finally {
            completePlacement(placement.playerId());
        }
    }

    private ItemFrame[][] placementFrames(Placement placement) throws Exception {
        if (closed) throw new IllegalStateException("The plugin is shutting down.");
        // Another copy may be destroyed after decoding or during a protection callback.
        // Recheck at every commit boundary so cached pixels cannot place a deleted artwork.
        if (placement.store().isDeleted(placement.artwork().id())) {
            throw new IllegalStateException(Language.imageMapItem.imageCorrupt);
        }
        // Check the captured location before accessing the entity: the player may have changed regions.
        requireOwned(placement.origin());
        ItemFrame origin = placement.originFrame();
        if (!origin.isValid() || !placement.origin().equals(origin.getLocation())
                || origin.getFacing() != placement.facing()
                || placement.player().getWorld() != placement.world()
                || !placement.held().equals(placement.player().getInventory().getItemInMainHand())) {
            throw new IllegalStateException(Language.imageMapEvent.mapChanged);
        }
        ItemFrame[][] frames = getItemFrameMatrix(origin, placement.artwork().columns(), placement.artwork().rows());
        requireEmpty(frames);
        return frames;
    }

    private void commitPlacement(Placement placement, ItemFrame[][] frames, ItemStack[][] tiles) throws Exception {
        Player player = placement.player();
        ItemStack held = placement.held();
        FrameSnapshot[][] before = null;
        boolean handChanged = false;
        try {
            placementFrames(placement);
            requireEmpty(frames);
            before = snapshot(frames);
            for (int x = 0; x < frames.length; x++) {
                for (int y = 0; y < frames[x].length; y++) {
                    frames[x][y].setItem(tiles[x][y], false);
                    frames[x][y].setRotation(Rotation.NONE);
                }
            }
            ItemStack remaining = held.clone();
            remaining.setAmount(held.getAmount() - 1);
            handChanged = true;
            player.getInventory().setItemInMainHand(remaining.getAmount() == 0
                    ? new ItemStack(Material.AIR) : remaining);
        } catch (Exception failure) {
            if (before != null) {
                restore(frames, before);
            }
            if (handChanged) {
                try {
                    player.getInventory().setItemInMainHand(held);
                } catch (Exception restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                    XLogger.err("Failed to restore held image map for %s: %s", player.getName(), restoreFailure.getMessage());
                }
            }
            throw failure;
        }
    }

    private void onPlayerThread(Placement placement, Runnable task) {
        try {
            if (closed) {
                completePlacement(placement.playerId());
                return;
            }
            Scheduler.runTask(placement.player(), task, () -> completePlacement(placement.playerId()));
        } catch (Exception failure) {
            completePlacement(placement.playerId());
            // Scheduling can fail during shutdown; logging is safe even on the worker thread.
            XLogger.warn("Cannot finish image map placement: %s", failure.getMessage());
        }
    }

    private void failOnPlayerThread(Placement placement, Exception failure) {
        onPlayerThread(placement, () -> finishFailure(placement, failure));
    }

    private void finishFailure(Placement placement, Exception failure) {
        try {
            reportFailure(placement.player(), failure);
        } finally {
            completePlacement(placement.playerId());
        }
    }

    /** Builds map items on the server thread; paths are saved with the items themselves. */
    private ItemStack[][] createTiles(Placement placement) throws Exception {
        MapStorage.Artwork artwork = placement.artwork();
        ItemStack[][] tiles = new ItemStack[artwork.columns()][artwork.rows()];
        for (int x = 0; x < artwork.columns(); x++) {
            for (int y = 0; y < artwork.rows(); y++) {
                ItemStack tile = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) tile.getItemMeta();
                MapStorage.ImageReference reference = MapStorage.ImageReference.tile(artwork.id(), x, y);
                MapView view = plugin.getMapViewRegistry().getOrCreate(placement.world(), reference, null);
                meta.setMapView(view);
                MapItemData.setReference(meta, reference);
                tile.setItemMeta(meta);
                tiles[x][y] = tile;
            }
        }
        return tiles;
    }

    public void pickup(HangingBreakEvent event, ItemFrame clicked, MapStorage.ImageReference reference) {
        Player player = event instanceof HangingBreakByEntityEvent byEntity
                && byEntity.getRemover() instanceof Player p ? p : null;
        preparePickup(event, null, clicked, reference, player);
    }

    public void pickup(EntityDamageByEntityEvent event, ItemFrame clicked, MapStorage.ImageReference reference) {
        preparePickup(null, event, clicked, reference, event.getDamager() instanceof Player p ? p : null);
    }

    private void preparePickup(HangingBreakEvent hanging, EntityDamageByEntityEvent damage, ItemFrame clicked,
                               MapStorage.ImageReference reference, Player player) {
        if (closed) {
            cancel(hanging, damage);
            return;
        }
        // Invalidated same-source copies remain removable as ordinary loose tiles.
        if (storage().isDeleted(reference.artwork())) return;
        MapStorage.Artwork artwork = storage().cachedArtwork(reference.artwork()).orElse(null);
        if (artwork == null) {
            // Preserve removal of loose legacy tiles when their artwork files are genuinely unavailable.
            if (metadataUnavailable(reference.artwork())) return;
            // A newly discovered loose legacy tile must retain vanilla's exact break/drop behavior.
            // Warm its metadata first rather than guessing how to replay a cancelled native event.
            cancel(hanging, damage);
            preload(reference.artwork());
            reportFailure(player, new IllegalStateException(Language.imageMapEvent.imageLoading));
            return;
        }
        try {
            ItemFrame[][] frames = completePickupFrames(clicked, reference, artwork);
            if (frames == null) return;
            cancel(hanging, damage);
            if (!pendingPickups.add(artwork.id())) return;
            if (!operations.tryAcquire()) {
                pendingPickups.remove(artwork.id());
                throw new IllegalStateException(Language.imageMapEvent.operationBusy);
            }
            try {
                if (hanging != null) protection.checkBreakPermission(hanging, frames);
                else protection.checkDamagePermission(damage, frames);
                // Reuse native tile IDs after a cold start even before a binding event reaches them.
                for (ItemFrame[] column : frames) for (ItemFrame frame : column) {
                    plugin.getMapViewRegistry().register(frame.getItem());
                }
                boolean dropFrame = hanging != null && (player == null || player.getGameMode() != GameMode.CREATIVE)
                        && !Boolean.FALSE.equals(clicked.getWorld().getGameRuleValue(GameRule.DO_ENTITY_DROPS));
                Pickup pickup = new Pickup(clicked, clicked.getLocation().clone(), clicked.getFacing(), reference,
                        artwork, player, hanging, damage, dropFrame);
                background.execute(() -> prepareRecovery(pickup));
            } catch (Exception failure) {
                completePickup(artwork.id());
                throw failure;
            }
        } catch (Exception failure) {
            cancel(hanging, damage);
            reportFailure(player, failure);
        }
    }

    private static void cancel(HangingBreakEvent hanging, EntityDamageByEntityEvent damage) {
        if (hanging != null) hanging.setCancelled(true);
        else damage.setCancelled(true);
    }

    private void prepareRecovery(Pickup pickup) {
        try {
            if (closed) return;
            BufferedImage thumbnail = storage().read(MapStorage.ImageReference.thumbnail(pickup.artwork().id()));
            onPickupThread(pickup, () -> finishRecovery(pickup, thumbnail));
        } catch (Exception failure) {
            onPickupThread(pickup, () -> {
                completePickup(pickup.artwork().id());
                reportFailure(pickup.player(), failure);
            });
        }
    }

    private void onPickupThread(Pickup pickup, Runnable operation) {
        Runnable retired = () -> completePickup(pickup.artwork().id());
        if (closed) { retired.run(); return; }
        try {
            if (pickup.player() != null) {
                Scheduler.runTask(pickup.player(), operation, retired);
            } else if (FOLIA) {
                if (pickup.clicked().getScheduler().run(plugin, task -> operation.run(), retired) == null) retired.run();
            } else {
                Scheduler.runTask(operation);
            }
        } catch (Exception failure) {
            retired.run();
            XLogger.warn("Cannot finish image map pickup: %s", failure.getMessage());
        }
    }

    private void finishRecovery(Pickup pickup, BufferedImage thumbnail) {
        try {
            if (closed || storage().isDeleted(pickup.artwork().id())) {
                throw new IllegalStateException(Language.imageMapItem.imageCorrupt);
            }
            requireOwned(pickup.location());
            if (!pickup.clicked().isValid() || !pickup.location().equals(pickup.clicked().getLocation())
                    || pickup.facing() != pickup.clicked().getFacing()) {
                throw new IllegalStateException(Language.imageMapEvent.mapChanged);
            }
            ItemFrame[][] frames = pickupFrames(pickup.clicked(), pickup.reference(), pickup.artwork());
            if (pickup.hanging() != null) protection.checkBreakPermission(pickup.hanging(), frames);
            else protection.checkDamagePermission(pickup.damage(), frames);
            frames = pickupFrames(pickup.clicked(), pickup.reference(), pickup.artwork());
            if (storage().isDeleted(pickup.artwork().id())) throw new IllegalStateException(Language.imageMapItem.imageCorrupt);
            recover(pickup, frames, thumbnail);
        } catch (Exception failure) {
            reportFailure(pickup.player(), failure);
        } finally {
            completePickup(pickup.artwork().id());
        }
    }

    private static ItemFrame[][] completePickupFrames(ItemFrame clicked, MapStorage.ImageReference reference, MapStorage.Artwork artwork) {
        try {
            return pickupFrames(clicked, reference, artwork);
        } catch (UnavailableFrameAreaException unavailable) {
            // An unloaded or foreign region can contain a complete painting. Do not split it blindly.
            throw unavailable;
        } catch (Exception incomplete) {
            // 2.3 also allowed individual tiles. Missing metadata or neighboring tiles must not trap them.
            return null;
        }
    }

    private static ItemFrame[][] pickupFrames(ItemFrame clicked, MapStorage.ImageReference reference, MapStorage.Artwork artwork) throws Exception {
        Location origin = originLocation(clicked.getLocation(), clicked.getFacing(), reference.x(), reference.y(), artwork.rows());
        ItemFrame originFrame = getItemFrame(origin, clicked.getFacing());
        if (originFrame == null) {
            throw new IllegalStateException(Language.imageMapEvent.inconsistentItemFrameArray);
        }
        ItemFrame[][] frames = getItemFrameMatrix(originFrame, artwork.columns(), artwork.rows());
        requireMatching(frames, artwork);
        return frames;
    }

    private static void recover(Pickup pickup, ItemFrame[][] frames, BufferedImage thumbnail) throws Exception {
        ItemFrame clicked = pickup.clicked();
        requireMatching(frames, pickup.artwork());
        ImageMapItem recovered = new ImageMapItem(clicked.getWorld(), pickup.artwork(), thumbnail);
        FrameSnapshot[][] before = snapshot(frames);
        Item dropped = null;
        Item frameDrop = null;
        try {
            for (ItemFrame[] column : frames) {
                for (ItemFrame frame : column) frame.setItem(new ItemStack(Material.AIR), false);
            }
            dropped = clicked.getWorld().dropItemNaturally(clicked.getLocation(), recovered);
            if (!dropped.isValid()) throw new IllegalStateException("The recovered map could not be dropped.");
            if (pickup.hanging() != null) {
                if (pickup.dropFrame()) {
                    frameDrop = clicked.getWorld().dropItemNaturally(clicked.getLocation(),
                            new ItemStack(clicked instanceof GlowItemFrame ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME));
                    if (!frameDrop.isValid()) throw new IllegalStateException("The empty item frame could not be dropped.");
                }
                clicked.remove();
            }
        } catch (Exception failure) {
            removeRollbackDrop(frameDrop, failure);
            removeRollbackDrop(dropped, failure);
            restore(frames, before);
            throw failure;
        }
    }

    private static void removeRollbackDrop(Item dropped, Exception failure) {
        if (dropped == null) return;
        try { dropped.remove(); }
        catch (Exception removeFailure) { failure.addSuppressed(removeFailure); }
    }

    private static Optional<MapStorage.ImageReference> tileReference(ItemStack item) {
        return MapItemData.reference(item).filter(reference -> !reference.thumbnail());
    }

    public static boolean isExpectedTile(MapStorage.ImageReference reference, MapStorage.Artwork artwork, int x, int y) {
        return reference != null && !reference.thumbnail() && reference.artwork().equals(artwork.id())
                && reference.x() == x && reference.y() == y;
    }

    private static void requireMatching(ItemFrame[][] frames, MapStorage.Artwork artwork) {
        for (int x = 0; x < frames.length; x++) {
            for (int y = 0; y < frames[x].length; y++) {
                ItemFrame frame = frames[x][y];
                requireOwned(frame.getLocation());
                if (!frame.isValid() || !isExpectedTile(tileReference(frame.getItem()).orElse(null), artwork, x, y)) {
                    throw new IllegalStateException(Language.imageMapEvent.inconsistentItemFrameArray);
                }
            }
        }
    }

    private static void requireEmpty(ItemFrame[][] frames) {
        for (ItemFrame[] column : frames) {
            for (ItemFrame frame : column) {
                requireOwned(frame.getLocation());
                if (!frame.isValid() || frame.isFixed() || !frame.getItem().getType().isAir()) {
                    throw new IllegalStateException(Language.imageMapEvent.occupiedItemFrame);
                }
            }
        }
    }

    private record FrameSnapshot(ItemStack item, Rotation rotation) { }

    private static FrameSnapshot[][] snapshot(ItemFrame[][] frames) {
        FrameSnapshot[][] result = new FrameSnapshot[frames.length][];
        for (int x = 0; x < frames.length; x++) {
            result[x] = new FrameSnapshot[frames[x].length];
            for (int y = 0; y < frames[x].length; y++) {
                result[x][y] = new FrameSnapshot(frames[x][y].getItem().clone(), frames[x][y].getRotation());
            }
        }
        return result;
    }

    private static void restore(ItemFrame[][] frames, FrameSnapshot[][] before) {
        for (int x = 0; x < frames.length; x++) {
            for (int y = 0; y < frames[x].length; y++) {
                try {
                    frames[x][y].setItem(before[x][y].item(), false);
                    frames[x][y].setRotation(before[x][y].rotation());
                } catch (Exception failure) {
                    XLogger.err("Failed to restore item frame %s: %s", frames[x][y].getUniqueId(), failure.getMessage());
                }
            }
        }
    }

    private MapStorage storage() {
        return plugin.getMapStorage();
    }

    private void reportFailure(Player player, Exception failure) {
        String detail = failure instanceof RejectedExecutionException
                ? Language.imageMapEvent.operationBusy : failure.getMessage();
        String message = SafeMessageFormat.format(plugin.getLogger(), Language.imageMapEvent.operationFailed, detail);
        if (player != null) {
            Notification.error(player, message);
        }
        XLogger.warn(message);
    }
}
