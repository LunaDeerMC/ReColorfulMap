package cn.lunadeer.reColorfulMap.services;

import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Copies persistent files before a region-thread inventory transaction publishes independent items. */
public final class ArtworkCopyService implements AutoCloseable {
    interface Platform {
        void schedule(Player player, Runnable action, Runnable retired);
        ItemStack item(Player player, ItemStack source, MapStorage.ImageReference reference) throws Exception;
        void error(Player player, String message);
    }

    private enum State { WAITING, COMMITTING, DONE, CANCELLED }
    private final MapStorage storage;
    private final Executor executor;
    private final ThreadPoolExecutor ownedExecutor;
    private final Platform platform;
    private final Logger logger;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Set<OwnerCall> ownerCalls = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ArtworkCopyService(ReColorfulMap plugin) {
        this(plugin.getMapStorage(), new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), action -> {
                    Thread thread = new Thread(action, "ReColorfulMap-copy");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()), new Platform() {
            public void schedule(Player player, Runnable action, Runnable retired) {
                Scheduler.runTask(player, action, retired);
            }
            public ItemStack item(Player player, ItemStack source, MapStorage.ImageReference reference) {
                ItemStack result = source.clone();
                result.setAmount(1);
                MapMeta meta = (MapMeta) result.getItemMeta();
                MapItemData.setReference(meta, reference);
                meta.setMapView(plugin.getMapViewRegistry().getOrCreate(player.getWorld(), reference, null));
                result.setItemMeta(meta);
                return result;
            }
            public void error(Player player, String message) { Notification.error(player, message); }
        }, plugin.getLogger(), true);
        ownedExecutor.allowCoreThreadTimeOut(true);
    }

    ArtworkCopyService(MapStorage storage, Executor executor, Platform platform, Logger logger) {
        this(storage, executor, platform, logger, false);
    }

    private ArtworkCopyService(MapStorage storage, Executor executor, Platform platform, Logger logger, boolean own) {
        this.storage = storage;
        this.executor = executor;
        this.platform = platform;
        this.logger = logger;
        this.ownedExecutor = own ? (ThreadPoolExecutor) executor : null;
    }

    /**
     * Call on the player's thread. The callback must revalidate its captured inputs and either
     * publish every supplied item or leave the original inventory unchanged and return false.
     * Existing items/materials must not be consumed before that callback.
     */
    public boolean copy(Player player, ItemStack source, int count, Function<List<ItemStack>, Boolean> commit) {
        return copy(player, source, count, () -> true, commit);
    }

    public boolean copy(Player player, ItemStack source, int count, BooleanSupplier ready,
                        Function<List<ItemStack>, Boolean> commit) {
        if (count < 1 || count > 64) throw new IllegalArgumentException("Copy count must be between 1 and 64");
        MapStorage.ImageReference reference = MapItemData.reference(source).orElse(null);
        UUID owner = player.getUniqueId();
        if (reference == null || closed) return false;
        ItemStack snapshot = source.clone();
        if (!pending.add(owner)) return false;
        try {
            executor.execute(() -> prepare(player, owner, snapshot, reference, count, ready, commit));
            return true;
        } catch (RuntimeException failure) {
            pending.remove(owner);
            if (!(failure instanceof RejectedExecutionException)) throw failure;
            return false;
        }
    }

    private void prepare(Player player, UUID owner, ItemStack source, MapStorage.ImageReference reference,
                         int count, BooleanSupplier ready, Function<List<ItemStack>, Boolean> commit) {
        List<MapStorage.Artwork> copies = new ArrayList<>();
        // If an inventory callback throws, its partial effects are unknown: keep its image files.
        boolean safeToRemove = true;
        boolean deliveryUncertain = false;
        try {
            for (int i = 0; i < count; i++) {
                if (closed) return;
                copies.add(storage.copyImage(reference));
            }
            OwnerCall call = new OwnerCall();
            ownerCalls.add(call);
            try {
                if (closed) call.cancel();
                try { platform.schedule(player, () -> {
                    if (closed || !call.state.compareAndSet(State.WAITING, State.COMMITTING)) {
                        call.cancel();
                        return;
                    }
                    try {
                        if (!ready.getAsBoolean()) {
                            call.result.complete(false);
                            platform.error(player, Language.mapCopy.changed);
                            return;
                        }
                        List<ItemStack> items = new ArrayList<>();
                        for (MapStorage.Artwork copy : copies) {
                            items.add(platform.item(player, source,
                                    reference.thumbnail() ? MapStorage.ImageReference.thumbnail(copy.id())
                                            : MapStorage.ImageReference.tile(copy.id(), 0, 0)));
                        }
                        // Item construction has no inventory side effects; exceptions here are safe.
                        call.callbackStarted = true;
                        call.result.complete(Boolean.TRUE.equals(commit.apply(List.copyOf(items))));
                    } catch (Exception failure) {
                        call.result.completeExceptionally(failure);
                    } finally {
                        call.state.set(State.DONE);
                    }
                }, call::cancel); }
                catch (RuntimeException scheduling) {
                    safeToRemove = !call.callbackStarted;
                    deliveryUncertain = call.callbackStarted;
                    throw scheduling;
                }
                try {
                    safeToRemove = !call.result.get(30, TimeUnit.SECONDS);
                } catch (TimeoutException waiting) {
                    if (!call.cancel()) {
                        // A callback already committing cannot safely be interrupted or undone here.
                        safeToRemove = false;
                        logger.warning("An artwork-copy inventory transaction is still running; its files were kept.");
                    }
                } catch (ExecutionException failure) {
                    safeToRemove = !call.callbackStarted;
                    deliveryUncertain = call.callbackStarted;
                    throw failure;
                } catch (InterruptedException interrupted) {
                    safeToRemove = call.cancel();
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            } finally {
                ownerCalls.remove(call);
            }
        } catch (Exception failure) {
            logger.log(Level.WARNING, "Cannot complete independent artwork copy; source files were kept", failure);
            if (!closed) {
                String message = deliveryUncertain
                        ? Language.mapCopy.uncertain : Language.mapCopy.failed;
                try { platform.schedule(player, () -> platform.error(player,
                        message), () -> { }); }
                catch (RuntimeException ignored) { }
            }
        } finally {
            if (safeToRemove) {
                for (MapStorage.Artwork copy : copies) {
                    try { storage.deleteArtwork(copy.id()); }
                    catch (Exception failure) { logger.log(Level.WARNING, "Cannot remove an unpublished artwork copy", failure); }
                }
            }
            pending.remove(owner);
        }
    }

    private static final class OwnerCall {
        private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private volatile boolean callbackStarted;
        boolean cancel() {
            if (state.compareAndSet(State.WAITING, State.CANCELLED)) {
                result.complete(false);
                return true;
            }
            return state.get() == State.CANCELLED;
        }
    }

    @Override public void close() {
        closed = true;
        ownerCalls.forEach(OwnerCall::cancel);
        // Queued jobs observe closed and finish without publishing or consuming an inventory.
        if (ownedExecutor != null) ownedExecutor.shutdown();
    }
}
