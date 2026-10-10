package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.Configuration;
import cn.lunadeer.reColorfulMap.ImageMapItem;
import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.ImageRenderer;
import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import cn.lunadeer.reColorfulMap.utils.VaultConnect.VaultConnect;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Item frames carry small image paths in their map items' PDC. */
public class ImageMapEvent implements Listener {

    public static class ImageMapEventText extends ConfigurationPart {
        public String notSupportedDirection = "Don't support place image map in up or down direction";
        public String incompleteItemFrameArray = "Incomplete item frame array need (%d x %d)";
        public String hookEconomyFailed = "Failed to hook economy plugin.";
        public String balanceNotEnough = "Not enough balance to place image map.";
        public String priceInfo = "Matrix size: %d x %d, unit price: %f, total price: %f, your balance: %f";
        public String occupiedItemFrame = "All item frames must be empty.";
        public String inconsistentItemFrameArray = "The item frame array is incomplete or contains a different image.";
        public String protectedItemFrame = "You cannot modify one or more of these item frames.";
        public String mapChanged = "The held map or item frames changed. Please try again.";
        public String unownedRegion = "The image crosses a server region that cannot be modified from here.";
        public String unloadedChunk = "Some item frames are in unloaded chunks. Load the entire image area and try again.";
        public String operationFailed = "Failed to update image map: %s";
    }

    // Protection plugins must see each affected frame. Do not process our own probes recursively.
    private final ThreadLocal<Boolean> protectionProbe = ThreadLocal.withInitial(() -> false);
    private static final boolean FOLIA = isFolia();
    private final Set<UUID> pendingPlacements = ConcurrentHashMap.newKeySet();

    private record Placement(UUID playerId, Player player, ItemFrame originFrame, Location origin,
                             BlockFace facing, World world, ItemStack held,
                             MapStorage store, MapStorage.Artwork artwork) { }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void putImageMapsOnItemFrame(PlayerInteractEntityEvent event) {
        if (event.isCancelled() || protectionProbe.get() || event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof ItemFrame clicked)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand().clone();
        if (!ImageMapItem.isImageMap(held)) {
            return;
        }
        // Claim recognized maps even when their files are damaged, so vanilla cannot consume them.
        event.setCancelled(true);
        UUID playerId = player.getUniqueId();
        if (!pendingPlacements.add(playerId)) {
            return;
        }
        try {
            ImageMapItem imageMap = new ImageMapItem(held);
            ItemFrame[][] frames = getItemFrameMatrix(clicked, imageMap.getXCount(), imageMap.getYCount());
            requireEmpty(frames);
            checkPlacementPermission(player, frames);
            placementCost(player, imageMap.getArtwork());
            World world = clicked.getWorld();
            Placement placement = new Placement(playerId, player, clicked, clicked.getLocation().clone(),
                    clicked.getFacing(), world, held, storage(), imageMap.getArtwork());
            Scheduler.runTaskAsync(() -> preparePlacement(placement));
        } catch (Exception failure) {
            pendingPlacements.remove(playerId);
            reportFailure(player, failure);
        }
    }

    /** Only immutable artwork identity and file storage are accessed on this worker. */
    private void preparePlacement(Placement placement) {
        try {
            BufferedImage[][] images = readTiles(placement.store(), placement.artwork());
            onPlayerThread(placement, () -> createPlacement(placement, images));
        } catch (Exception failure) {
            failOnPlayerThread(placement, failure);
        }
    }

    /** Bukkit map and item creation belongs to the player's server/region thread. */
    private void createPlacement(Placement placement, BufferedImage[][] images) {
        try {
            placementFrames(placement);
            ItemStack[][] tiles = createTiles(placement, images);
            finishPlacement(placement, tiles);
        } catch (Exception failure) {
            finishFailure(placement, failure);
        }
    }

    private void finishPlacement(Placement placement, ItemStack[][] tiles) {
        try {
            ItemFrame[][] frames = placementFrames(placement);
            checkPlacementPermission(placement.player(), frames);
            double cost = placementCost(placement.player(), placement.artwork());
            // Protection callbacks may change the world or inventory. Recheck before any payment.
            placementFrames(placement);
            commitPlacement(placement, frames, tiles, cost);
        } catch (Exception failure) {
            reportFailure(placement.player(), failure);
        } finally {
            pendingPlacements.remove(placement.playerId());
        }
    }

    private static ItemFrame[][] placementFrames(Placement placement) throws Exception {
        // Another copy may be destroyed after decoding or during a protection/economy callback.
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

    private static void commitPlacement(Placement placement, ItemFrame[][] frames, ItemStack[][] tiles, double cost) throws Exception {
        Player player = placement.player();
        ItemStack held = placement.held();
        FrameSnapshot[][] before = null;
        boolean charged = false;
        boolean handChanged = false;
        try {
            if (cost > 0) {
                VaultConnect.instance.withdrawPlayer(player, cost);
                charged = true;
            }
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
            if (charged) {
                try {
                    VaultConnect.instance.depositPlayer(player, cost);
                } catch (Exception refundFailure) {
                    failure.addSuppressed(refundFailure);
                    XLogger.err("Failed to refund image map placement for %s: %s", player.getName(), refundFailure.getMessage());
                }
            }
            throw failure;
        }
    }

    private void onPlayerThread(Placement placement, Runnable task) {
        try {
            Scheduler.runTask(placement.player(), task, () -> pendingPlacements.remove(placement.playerId()));
        } catch (Exception failure) {
            pendingPlacements.remove(placement.playerId());
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
            pendingPlacements.remove(placement.playerId());
        }
    }

    private void checkPlacementPermission(Player player, ItemFrame[][] frames) {
        protectionProbe.set(true);
        try {
            for (ItemFrame[] column : frames) {
                for (ItemFrame frame : column) {
                    PlayerInteractEntityEvent probe = new PlayerInteractEntityEvent(player, frame, EquipmentSlot.HAND);
                    Bukkit.getPluginManager().callEvent(probe);
                    if (probe.isCancelled()) {
                        throw new IllegalStateException(Language.imageMapEvent.protectedItemFrame);
                    }
                }
            }
        } finally {
            protectionProbe.remove();
        }
    }

    private static double placementCost(Player player, MapStorage.Artwork artwork) {
        if (!Configuration.economy.enable) {
            return 0;
        }
        if (VaultConnect.instance == null || !VaultConnect.instance.economyAvailable()) {
            throw new IllegalStateException(Language.imageMapEvent.hookEconomyFailed);
        }
        double cost = Configuration.economy.costPerMap * artwork.columns() * artwork.rows();
        if (!Double.isFinite(cost) || cost < 0) {
            throw new IllegalStateException("Invalid image map placement price.");
        }
        double balance = VaultConnect.instance.getBalance(player);
        if (balance < cost) {
            Notification.error(player, Language.imageMapEvent.priceInfo,
                    artwork.columns(), artwork.rows(), Configuration.economy.costPerMap, cost, balance);
            throw new IllegalStateException(Language.imageMapEvent.balanceNotEnough);
        }
        return cost;
    }

    /** File decoding runs asynchronously; no world, player or map APIs are used here. */
    private static BufferedImage[][] readTiles(MapStorage storage, MapStorage.Artwork artwork) throws Exception {
        BufferedImage[][] images = new BufferedImage[artwork.columns()][artwork.rows()];
        for (int x = 0; x < artwork.columns(); x++) {
            for (int y = 0; y < artwork.rows(); y++) {
                images[x][y] = storage.read(MapStorage.ImageReference.tile(artwork.id(), x, y));
            }
        }
        return images;
    }

    /** Builds map items on the server thread; paths are saved with the items themselves. */
    private static ItemStack[][] createTiles(Placement placement, BufferedImage[][] images) throws Exception {
        MapStorage.Artwork artwork = placement.artwork();
        ItemStack[][] tiles = new ItemStack[artwork.columns()][artwork.rows()];
        for (int x = 0; x < artwork.columns(); x++) {
            for (int y = 0; y < artwork.rows(); y++) {
                ItemStack tile = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) tile.getItemMeta();
                MapView view = Bukkit.createMap(placement.world());
                meta.setMapView(view);
                MapStorage.ImageReference reference = MapStorage.ImageReference.tile(artwork.id(), x, y);
                MapItemData.setReference(meta, reference);
                ImageRenderer.renderOnMeta(meta, images[x][y], placement.store(), reference);
                tile.setItemMeta(meta);
                tiles[x][y] = tile;
            }
        }
        return tiles;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void itemFrameBroken(HangingBreakEvent event) {
        if (event.isCancelled() || protectionProbe.get() || !(event.getEntity() instanceof ItemFrame clicked)) {
            return;
        }
        Optional<MapStorage.ImageReference> reference = tileReference(clicked.getItem());
        if (reference.isEmpty()) {
            return;
        }
        Player player = event instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() instanceof Player p ? p : null;
        try {
            ItemFrame[][] frames = completePickupFrames(clicked, reference.get());
            if (frames == null) {
                return; // A loose legacy tile or an incomplete wall can still be removed normally.
            }
            // Once a whole artwork is recognized, failures must leave every frame intact.
            event.setCancelled(true);
            checkBreakPermission(event, frames);
            recover(clicked, reference.get(), frames);
            // Vanilla can now remove and drop the emptied clicked frame.
            event.setCancelled(false);
        } catch (Exception failure) {
            event.setCancelled(true);
            reportFailure(player, failure);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakImageMapsFromItemFrame(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || protectionProbe.get() || !(event.getEntity() instanceof ItemFrame clicked)) {
            return;
        }
        Optional<MapStorage.ImageReference> reference = tileReference(clicked.getItem());
        if (reference.isEmpty()) {
            return;
        }
        try {
            ItemFrame[][] frames = completePickupFrames(clicked, reference.get());
            if (frames == null) {
                return;
            }
            event.setCancelled(true);
            checkDamagePermission(event, frames);
            recover(clicked, reference.get(), frames);
        } catch (Exception failure) {
            event.setCancelled(true);
            reportFailure(event.getDamager() instanceof Player player ? player : null, failure);
        }
    }

    private void checkBreakPermission(HangingBreakEvent original, ItemFrame[][] frames) {
        protectionProbe.set(true);
        try {
            for (ItemFrame[] column : frames) {
                for (ItemFrame frame : column) {
                    if (frame.isFixed()) {
                        throw new IllegalStateException(Language.imageMapEvent.protectedItemFrame);
                    }
                    HangingBreakEvent probe = original instanceof HangingBreakByEntityEvent byEntity
                            ? createHangingBreakProbe(HangingBreakByEntityEvent.class, byEntity, frame)
                            : new HangingBreakEvent(frame, original.getCause());
                    Bukkit.getPluginManager().callEvent(probe);
                    if (probe.isCancelled()) {
                        throw new IllegalStateException(Language.imageMapEvent.protectedItemFrame);
                    }
                }
            }
        } finally {
            protectionProbe.remove();
        }
    }

    /** Paper 26.2 requires a DamageSource; older versions expose only the three-argument constructor. */
    static <T extends HangingBreakByEntityEvent> T createHangingBreakProbe(
            Class<T> eventType, HangingBreakByEntityEvent original, Hanging target) {
        try {
            Method damageSource;
            try {
                damageSource = eventType.getMethod("getDamageSource");
            } catch (NoSuchMethodException legacyApi) {
                return eventType.getConstructor(Hanging.class, Entity.class, HangingBreakEvent.RemoveCause.class)
                        .newInstance(target, original.getRemover(), original.getCause());
            }
            // Preserve the actual source for protection plugins instead of fabricating generic damage.
            return eventType.getConstructor(Hanging.class, Entity.class, damageSource.getReturnType(),
                            HangingBreakEvent.RemoveCause.class)
                    .newInstance(target, original.getRemover(), damageSource.invoke(original), original.getCause());
        } catch (ReflectiveOperationException failure) {
            // An unsupported event shape must leave the painting in place, never skip protection checks.
            throw new IllegalStateException("Cannot create an item frame protection event on this server", failure);
        }
    }

    @SuppressWarnings("deprecation")
    private void checkDamagePermission(EntityDamageByEntityEvent original, ItemFrame[][] frames) {
        protectionProbe.set(true);
        try {
            for (ItemFrame[] column : frames) {
                for (ItemFrame frame : column) {
                    if (frame.isFixed()) {
                        throw new IllegalStateException(Language.imageMapEvent.protectedItemFrame);
                    }
                    EntityDamageByEntityEvent probe = createDamageProbe(EntityDamageByEntityEvent.class, original, frame);
                    Bukkit.getPluginManager().callEvent(probe);
                    if (probe.isCancelled()) {
                        throw new IllegalStateException(Language.imageMapEvent.protectedItemFrame);
                    }
                }
            }
        } finally {
            protectionProbe.remove();
        }
    }

    /** Retains modern damage attribution for protection plugins while supporting the 1.20.1 API. */
    static <T extends EntityDamageByEntityEvent> T createDamageProbe(
            Class<T> eventType, EntityDamageByEntityEvent original, Entity target) {
        try {
            Method damageSource;
            try {
                damageSource = eventType.getMethod("getDamageSource");
            } catch (NoSuchMethodException legacyApi) {
                return eventType.getConstructor(Entity.class, Entity.class,
                                EntityDamageByEntityEvent.DamageCause.class, double.class)
                        .newInstance(original.getDamager(), target, original.getCause(), original.getDamage());
            }
            return eventType.getConstructor(Entity.class, Entity.class, EntityDamageByEntityEvent.DamageCause.class,
                            damageSource.getReturnType(), double.class)
                    .newInstance(original.getDamager(), target, original.getCause(),
                            damageSource.invoke(original), original.getDamage());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot create an item frame damage protection event on this server", failure);
        }
    }

    private static ItemFrame[][] completePickupFrames(ItemFrame clicked, MapStorage.ImageReference reference) {
        try {
            return pickupFrames(clicked, reference);
        } catch (UnavailableFrameAreaException unavailable) {
            // An unloaded or foreign region can contain a complete painting. Do not split it blindly.
            throw unavailable;
        } catch (Exception incomplete) {
            // 2.3 also allowed individual tiles. Missing metadata or neighboring tiles must not trap them.
            return null;
        }
    }

    private static ItemFrame[][] pickupFrames(ItemFrame clicked, MapStorage.ImageReference reference) throws Exception {
        MapStorage.Artwork artwork = storage().artwork(reference.artwork());
        Location origin = originLocation(clicked.getLocation(), clicked.getFacing(), reference.x(), reference.y(), artwork.rows());
        ItemFrame originFrame = getItemFrame(origin, clicked.getFacing());
        if (originFrame == null) {
            throw new IllegalStateException(Language.imageMapEvent.inconsistentItemFrameArray);
        }
        ItemFrame[][] frames = getItemFrameMatrix(originFrame, artwork.columns(), artwork.rows());
        requireMatching(frames, artwork);
        return frames;
    }

    private static void recover(ItemFrame clicked, MapStorage.ImageReference reference, ItemFrame[][] frames) throws Exception {
        MapStorage.Artwork artwork = storage().artwork(reference.artwork());
        requireMatching(frames, artwork);
        // Validate the thumbnail before touching a single frame; files survive normal pickup.
        ImageMapItem recovered = new ImageMapItem(clicked.getWorld(), artwork);
        FrameSnapshot[][] before = snapshot(frames);
        Item dropped = null;
        try {
            for (ItemFrame[] column : frames) {
                for (ItemFrame frame : column) {
                    frame.setItem(new ItemStack(Material.AIR), false);
                }
            }
            dropped = clicked.getWorld().dropItemNaturally(clicked.getLocation(), recovered);
            if (!dropped.isValid()) {
                throw new IllegalStateException("The recovered map could not be dropped.");
            }
        } catch (Exception failure) {
            if (dropped != null) {
                try {
                    dropped.remove();
                } catch (Exception removeFailure) {
                    failure.addSuppressed(removeFailure);
                }
            }
            restore(frames, before);
            throw failure;
        }
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

    /** Coordinates use the image's top-left origin; the wall is anchored at its bottom-left frame. */
    public static Location tileLocation(Location origin, BlockFace facing, int x, int y, int rows) {
        validateCoordinates(facing, x, y, rows);
        return origin.clone().add(facing.getModZ() * x, rows - 1 - y, -facing.getModX() * x);
    }

    /** Inverse of tileLocation, also works for ColorfulMap 2.3 frames with no item metadata. */
    public static Location originLocation(Location tile, BlockFace facing, int x, int y, int rows) {
        validateCoordinates(facing, x, y, rows);
        return tile.clone().add(-facing.getModZ() * x, y - rows + 1, facing.getModX() * x);
    }

    private static void validateCoordinates(BlockFace facing, int x, int y, int rows) {
        if (facing != BlockFace.NORTH && facing != BlockFace.SOUTH && facing != BlockFace.WEST && facing != BlockFace.EAST) {
            throw new IllegalArgumentException(Language.imageMapEvent.notSupportedDirection);
        }
        if (rows <= 0 || x < 0 || y < 0 || y >= rows) {
            throw new IllegalArgumentException("Invalid image tile coordinates.");
        }
    }

    public static ItemFrame[][] getItemFrameMatrix(ItemFrame leftBottom, Integer columns, Integer rows) throws Exception {
        if (columns == null || rows == null || columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("Invalid image dimensions.");
        }
        ItemFrame[][] frames = new ItemFrame[columns][rows];
        for (int x = 0; x < columns; x++) {
            for (int y = 0; y < rows; y++) {
                Location location = tileLocation(leftBottom.getLocation(), leftBottom.getFacing(), x, y, rows);
                ItemFrame frame = getItemFrame(location, leftBottom.getFacing());
                if (frame == null) {
                    throw new IllegalStateException(Language.imageMapEvent.incompleteItemFrameArray.formatted(columns, rows));
                }
                frames[x][y] = frame;
            }
        }
        return frames;
    }

    private static ItemFrame getItemFrame(Location location, BlockFace facing) {
        requireOwned(location);
        if (location.getWorld() == null) {
            return null;
        }
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            throw new UnavailableFrameAreaException(Language.imageMapEvent.unloadedChunk);
        }
        for (Entity entity : location.getWorld().getNearbyEntities(location, 0.25, 0.25, 0.25)) {
            if (entity instanceof ItemFrame frame && frame.getFacing() == facing
                    && entity.getLocation().getBlockX() == location.getBlockX()
                    && entity.getLocation().getBlockY() == location.getBlockY()
                    && entity.getLocation().getBlockZ() == location.getBlockZ()) {
                return frame;
            }
        }
        return null;
    }

    private static void requireOwned(Location location) {
        if (FOLIA && !Bukkit.isOwnedByCurrentRegion(location)) {
            throw new UnavailableFrameAreaException(Language.imageMapEvent.unownedRegion);
        }
    }

    private static final class UnavailableFrameAreaException extends IllegalStateException {
        private UnavailableFrameAreaException(String message) {
            super(message);
        }
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static MapStorage storage() {
        return ReColorfulMap.getInstance().getMapStorage();
    }

    private static void reportFailure(Player player, Exception failure) {
        String message = Language.imageMapEvent.operationFailed.formatted(failure.getMessage());
        if (player != null) {
            Notification.error(player, message);
        }
        XLogger.warn(message);
    }
}
