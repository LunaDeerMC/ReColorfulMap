package cn.lunadeer.reColorfulMap.services;

import cn.lunadeer.reColorfulMap.*;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.storage.PendingDeliveryStore;
import cn.lunadeer.reColorfulMap.storage.PendingDeliveryStore.Entry;
import cn.lunadeer.reColorfulMap.storage.PendingDeliveryStore.Delivery;
import cn.lunadeer.reColorfulMap.storage.PendingDeliveryStore.Payment;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import cn.lunadeer.reColorfulMap.utils.VaultConnect.VaultConnect;
import cn.lunadeer.reColorfulMap.utils.VaultConnect.TransactionRejectedException;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns bounded download jobs and durable, conservatively recoverable item delivery. */
public final class GenerationService implements AutoCloseable {
    @FunctionalInterface
    interface Generator {
        MapStorage.Artwork generate(String url, float scale, Configuration.Snapshot settings) throws Exception;
    }
    interface Platform {
        void schedule(Player player, Runnable task, Runnable retired);
        ItemStack item(Player player, MapStorage.Artwork artwork, BufferedImage thumbnail) throws Exception;
        void deliver(Player player, ItemStack item) throws Exception;
        double balance(Player player);
        void withdraw(Player player, double amount);
        void refund(Player player, double amount);
        void info(Player player, String message, Object... arguments);
        void error(Player player, String message, Object... arguments);
    }

    private final MapStorage storage;
    private final PendingDeliveryStore receipts;
    private final ThreadPoolExecutor executor;
    private final Generator generator;
    private final Platform platform;
    private final ConcurrentMap<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Set<UUID> claims = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object ownerWaitLock = new Object();
    private final Set<CompletableFuture<?>> ownerWaits = new HashSet<>();

    public GenerationService(MapStorage storage, Path pluginDataDirectory, Configuration.GenerationSettings settings) throws IOException {
        this(storage, openReceipts(pluginDataDirectory), executor(settings.workers(), settings.queueCapacity()),
                (url, scale, config) -> ImageMapGenerator.generate(storage, url, scale, config.maxFrameX(), config.maxFrameY(),
                        config.addressWhiteList(), config.download()), new BukkitPlatform());
    }

    private static PendingDeliveryStore openReceipts(Path directory) throws IOException {
        PendingDeliveryStore store = new PendingDeliveryStore(directory);
        store.pruneCompleted(id -> java.nio.file.Files.notExists(directory.resolve("maps").resolve(id.toString()),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
        return store;
    }

    GenerationService(MapStorage storage, PendingDeliveryStore receipts, ThreadPoolExecutor executor, Generator generator, Platform platform) {
        this.storage = storage; this.receipts = receipts; this.executor = executor; this.generator = generator; this.platform = platform;
    }

    static ThreadPoolExecutor executor(int workers, int queueCapacity) {
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                queueCapacity == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "ReColorfulMap-generation");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Called on the player's owning thread; each job keeps one coherent configuration snapshot. */
    public void submit(Player player, String url, float scale) {
        Configuration.Snapshot settings = Configuration.snapshot();
        Job job = new Job(player.getUniqueId());
        if (closed.get()) { platform.error(player, Language.toMapCommand.serverBusy); return; }
        if (jobs.putIfAbsent(job.owner, job) != null) { platform.info(player, Language.toMapCommand.generatingMap); return; }
        job.future = new FutureTask<Void>(() -> { generate(player, job, url, scale, settings); return null; }) {
            @Override public void run() {
                job.started = true;
                try { super.run(); } finally { jobs.remove(job.owner, job); }
            }
        };
        try {
            executor.execute(job.future);
            platform.info(player, Language.toMapCommand.generatingMap);
        } catch (RejectedExecutionException failure) {
            jobs.remove(job.owner, job);
            platform.error(player, Language.toMapCommand.serverBusy);
        }
    }

    public String status(UUID owner) {
        Job job = jobs.get(owner);
        return job == null ? Language.mapManage.idle : job.cancelled.get() ? Language.mapManage.cancelling
                : job.started ? Language.mapManage.running : Language.mapManage.queued;
    }

    public boolean cancel(UUID owner) {
        Job job = jobs.get(owner);
        if (job == null) return false;
        synchronized (job) {
            if (!job.acceptingCancellation.compareAndSet(true, false)) return false;
            job.cancelled.set(true);
            FutureTask<Void> future = job.future;
            if (future != null && !job.persisting) {
                future.cancel(true);
                if (executor.remove(future)) jobs.remove(owner, job);
            }
        }
        return true;
    }

    public List<Entry> pending(UUID owner) { return receipts.list(owner); }
    public Optional<Entry> inspect(UUID artwork) { return receipts.find(artwork); }

    private void generate(Player player, Job job, String url, float scale, Configuration.Snapshot settings) {
        try {
            if (job.cancelled.get() || closed.get()) return;
            MapStorage.Artwork artwork = generator.generate(url, scale, settings);
            double amount = settings.economy().enable() ? (double) artwork.columns() * artwork.rows() * settings.economy().costPerMap() : 0;
            if (!Double.isFinite(amount)) throw new IOException("Generation price exceeds the supported range");
            // Even cancellation immediately after PNG commit must leave a durable recovery record.
            final boolean interrupted;
            synchronized (job) {
                // Cancellation only interrupts the download/decode phase. No file commit runs under this lock.
                job.persisting = true;
                interrupted = Thread.interrupted();
            }
            try { receipts.create(artwork.id(), job.owner, amount); }
            catch (IOException failure) { throw new IOException("Could not save delivery receipt for artwork " + artwork.id(), failure); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            if (job.cancelled.get() || closed.get() || !job.acceptingCancellation.compareAndSet(true, false)) return;
            BufferedImage thumbnail = storage.read(MapStorage.ImageReference.thumbnail(artwork.id()));
            if (!claims.add(artwork.id())) return;
            deliver(player, artwork, thumbnail);
        } catch (Exception failure) {
            if (!job.cancelled.get() && !closed.get()) report(player, failure);
        }
    }

    public void claim(Player player, UUID artworkId) {
        Entry entry = receipts.find(artworkId).orElse(null);
        if (entry == null || !entry.owner().equals(player.getUniqueId())) {
            platform.error(player, Language.mapManage.notOwned); return;
        }
        if (entry.delivery() == Delivery.DELIVERED) { platform.error(player, Language.mapManage.alreadyDelivered); return; }
        if (entry.uncertain()) { platform.error(player, Language.mapManage.needsReview, artworkId); return; }
        if (!claims.add(artworkId)) { platform.info(player, Language.mapManage.claimInProgress); return; }
        try {
            executor.execute(() -> {
                try {
                    if (closed.get()) { claims.remove(artworkId); return; }
                    MapStorage.Artwork artwork = storage.artwork(artworkId);
                    BufferedImage thumbnail = storage.read(MapStorage.ImageReference.thumbnail(artworkId));
                    deliver(player, artwork, thumbnail);
                } catch (Exception failure) { claims.remove(artworkId); report(player, failure); }
            });
        } catch (RejectedExecutionException failure) {
            claims.remove(artworkId); platform.error(player, Language.toMapCommand.serverBusy);
        }
    }

    /** Waits only on a worker thread. Retired, interrupted or late owner callbacks cannot start mutations. */
    private <T> T onPlayer(Player player, Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicBoolean allowed = new AtomicBoolean(true);
        synchronized (ownerWaitLock) {
            if (closed.get()) throw new IOException("The plugin is stopping; delivery was postponed");
            ownerWaits.add(result);
        }
        try {
        platform.schedule(player, () -> {
            if (!allowed.compareAndSet(true, false) || closed.get()) {
                result.completeExceptionally(new IOException("Delivery postponed until the player returns"));
                return;
            }
            try { result.complete(action.call()); }
            catch (Exception failure) { result.completeExceptionally(failure); }
        }, () -> {
            allowed.set(false);
            result.completeExceptionally(new IOException("Delivery postponed until the player returns"));
        });
        try { return result.get(); }
        catch (InterruptedException interrupted) {
            allowed.set(false); Thread.currentThread().interrupt(); throw interrupted;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception exception) throw exception;
            throw new IOException("Player operation failed", failed.getCause());
        }
        } finally {
            synchronized (ownerWaitLock) { ownerWaits.remove(result); }
        }
    }

    /** All receipt writes run on a bounded worker; only Bukkit/Vault operations cross to the owner. */
    private void deliver(Player player, MapStorage.Artwork artwork, BufferedImage thumbnail) {
        Entry receipt = receipts.find(artwork.id()).orElseThrow();
        AtomicBoolean itemMutationStarted = new AtomicBoolean();
        try {
            if (closed.get() || receipt.delivery() != Delivery.PENDING || receipt.uncertain()) return;
            if (storage.isDeleted(artwork.id())) throw new IOException(Language.imageMapItem.imageCorrupt);
            Entry quoted = receipt;
            ItemStack item = onPlayer(player, () -> {
                if (quoted.amount() > 0 && quoted.payment() != Payment.PAID) {
                    double balance = platform.balance(player);
                    if (!Double.isFinite(balance)) throw new IOException(Language.mapManage.economyUnavailable);
                    if (balance < quoted.amount()) {
                        platform.error(player, Language.mapManage.insufficientFunds, quoted.amount(), artwork.id());
                        return null;
                    }
                }
                return platform.item(player, artwork, thumbnail);
            });
            if (item == null) return;
            receipt = receipts.update(receipt, e -> e.delivery(Delivery.DELIVERING));
            if (receipt.amount() > 0 && receipt.payment() != Payment.PAID) {
                receipt = receipts.update(receipt, e -> e.payment(Payment.CHARGING));
                double amount = receipt.amount();
                onPlayer(player, () -> { platform.withdraw(player, amount); return null; });
                receipt = receipts.update(receipt, e -> e.payment(Payment.PAID));
            }
            onPlayer(player, () -> {
                if (storage.isDeleted(artwork.id())) throw new IOException(Language.imageMapItem.imageCorrupt);
                itemMutationStarted.set(true);
                platform.deliver(player, item);
                return null;
            });
            receipts.update(receipt, e -> e.delivery(Delivery.DELIVERED));
            platform.schedule(player, () -> platform.info(player, Language.toMapCommand.generatedMap,
                    artwork.columns(), artwork.rows()), () -> { });
        } catch (Exception failure) {
            // Inventory listeners may have moved an item even when its ground spawn was cancelled.
            Entry latest = receipts.find(artwork.id()).orElse(receipt);
            if (!closed.get() && failure instanceof TransactionRejectedException && latest.payment() == Payment.CHARGING) {
                try { latest = receipts.update(latest, e -> e.payment(Payment.UNPAID).delivery(Delivery.PENDING)); }
                catch (IOException saveFailure) { failure.addSuppressed(saveFailure); }
            }
            if (!closed.get() && latest.payment() == Payment.PAID && latest.delivery() != Delivery.DELIVERED
                    && !itemMutationStarted.get()) {
                try {
                    latest = receipts.update(latest, e -> e.payment(Payment.REFUNDING));
                    double amount = latest.amount();
                    onPlayer(player, () -> { platform.refund(player, amount); return null; });
                    latest = receipts.update(latest, e -> e.payment(Payment.REFUNDED));
                } catch (Exception refundFailure) {
                    if (refundFailure instanceof TransactionRejectedException) {
                        try {
                            Entry rejectedRefund = receipts.find(artwork.id()).orElseThrow();
                            if (rejectedRefund.payment() == Payment.REFUNDING) latest = receipts.update(rejectedRefund, e -> e.payment(Payment.PAID));
                        } catch (Exception saveFailure) { refundFailure.addSuppressed(saveFailure); }
                    }
                    failure.addSuppressed(refundFailure);
                }
            }
            if (!closed.get() && !itemMutationStarted.get() && latest.payment() != Payment.CHARGING
                    && latest.payment() != Payment.REFUNDING && latest.delivery() != Delivery.DELIVERED) {
                try { receipts.update(latest, e -> e.delivery(Delivery.PENDING)); }
                catch (IOException saveFailure) { failure.addSuppressed(saveFailure); }
            }
            XLogger.warn("Delivery for artwork %s (owner %s) stopped with state %s/%s: %s",
                    artwork.id(), receipt.owner(), latest.delivery(), latest.payment(), failure.toString());
            for (Throwable detail : failure.getSuppressed()) {
                XLogger.warn("Additional delivery error for artwork %s: %s", artwork.id(), detail.toString());
            }
            if (!closed.get()) report(player, failure);
        } finally { claims.remove(artwork.id()); }
    }

    /** Administrator recovery is explicit; an existing receipt can never be reassigned or reissued. */
    public void recover(Player owner, UUID artworkId) {
        Entry existing = receipts.find(artworkId).orElse(null);
        if (existing != null) { claim(owner, artworkId); return; }
        if (!claims.add(artworkId)) { platform.info(owner, Language.mapManage.claimInProgress); return; }
        UUID ownerId = owner.getUniqueId();
        try {
            executor.execute(() -> {
                try {
                    MapStorage.Artwork artwork = storage.artwork(artworkId);
                    BufferedImage thumbnail = storage.read(MapStorage.ImageReference.thumbnail(artworkId));
                    receipts.create(artworkId, ownerId, 0);
                    deliver(owner, artwork, thumbnail);
                } catch (Exception failure) { claims.remove(artworkId); report(owner, failure); }
            });
        } catch (RejectedExecutionException failure) { claims.remove(artworkId); platform.error(owner, Language.toMapCommand.serverBusy); }
    }

    /** Only explicit administrator confirmation can release a crash-ambiguous receipt. */
    Entry resolveNow(UUID artwork, String resolution) throws IOException {
        if (!claims.add(artwork)) throw new IOException(Language.mapManage.claimInProgress);
        try {
        Entry receipt = receipts.find(artwork).orElseThrow(() -> new IOException(Language.mapManage.notFound));
        if (receipt.delivery() == Delivery.DELIVERED) throw new IOException(Language.mapManage.alreadyDelivered);
        return receipts.update(receipt, entry -> switch (resolution) {
            case "pending" -> {
                if (entry.payment() == Payment.CHARGING || entry.payment() == Payment.REFUNDING) {
                    throw new IllegalArgumentException(Language.mapManage.resolvePaymentFirst);
                }
                yield entry.delivery(Delivery.PENDING);
            }
            case "delivered" -> entry.delivery(Delivery.DELIVERED);
            case "paid" -> {
                if (entry.payment() != Payment.CHARGING && entry.payment() != Payment.REFUNDING) throw new IllegalArgumentException(Language.mapManage.invalidResolution);
                yield entry.payment(Payment.PAID);
            }
            case "unpaid" -> {
                if (entry.payment() != Payment.CHARGING) throw new IllegalArgumentException(Language.mapManage.invalidResolution);
                yield entry.payment(Payment.UNPAID);
            }
            case "refunded" -> {
                if (entry.payment() != Payment.REFUNDING) throw new IllegalArgumentException(Language.mapManage.invalidResolution);
                yield entry.payment(Payment.REFUNDED);
            }
            default -> throw new IllegalArgumentException(Language.mapManage.invalidResolution);
        });
        } finally { claims.remove(artwork); }
    }

    public void resolve(UUID artwork, String resolution, java.util.function.Consumer<Exception> completion) {
        try {
            executor.execute(() -> {
                Exception problem = null;
                try { resolveNow(artwork, resolution); }
                catch (Exception failure) { problem = failure; }
                completion.accept(problem);
            });
        } catch (RejectedExecutionException failure) { completion.accept(new IOException(Language.toMapCommand.serverBusy)); }
    }

    private void report(Player player, Exception failure) {
        XLogger.warn("Map generation/delivery failed: %s", failure.toString());
        if (!closed.get()) platform.schedule(player,
                () -> platform.error(player, Language.toMapCommand.generateMapFailed, failure.getMessage()), () -> { });
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Job job : jobs.values()) {
            synchronized (job) {
                job.cancelled.set(true);
                if (job.future != null && !job.persisting) job.future.cancel(true);
            }
        }
        // Do not interrupt a receipt's FileChannel: interruption would poison an otherwise healthy store.
        executor.shutdown();
        executor.getQueue().clear();
        synchronized (ownerWaitLock) {
            for (CompletableFuture<?> waiting : ownerWaits) {
                waiting.completeExceptionally(new IOException("The plugin is stopping; delivery was postponed"));
            }
        }
        jobs.clear(); claims.clear();
    }

    private static final class Job {
        private final UUID owner;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean acceptingCancellation = new AtomicBoolean(true);
        private volatile FutureTask<Void> future;
        private volatile boolean started;
        private boolean persisting; // guarded by this Job's short cancellation lock
        private Job(UUID owner) { this.owner = owner; }
    }

    static final class BukkitPlatform implements Platform {
        @Override public void schedule(Player player, Runnable task, Runnable retired) { Scheduler.runTask(player, task, retired); }
        @Override public ItemStack item(Player player, MapStorage.Artwork artwork, BufferedImage thumbnail) throws Exception {
            return new ImageMapItem(player.getWorld(), artwork, thumbnail);
        }
        @Override public void deliver(Player player, ItemStack item) throws Exception {
            for (ItemStack leftover : player.getInventory().addItem(item).values()) {
                if (!player.getWorld().dropItemNaturally(player.getLocation(), leftover).isValid()) {
                    throw new IOException(Language.mapManage.deliveryUncertain);
                }
            }
        }
        private VaultConnect vault() {
            VaultConnect vault = VaultConnect.instance;
            if (vault == null || !vault.economyAvailable()) throw new IllegalStateException(Language.mapManage.economyUnavailable);
            return vault;
        }
        @Override public double balance(Player player) { return vault().getBalance(player); }
        @Override public void withdraw(Player player, double amount) { vault().withdrawPlayer(player, amount); }
        @Override public void refund(Player player, double amount) { vault().depositPlayer(player, amount); }
        @Override public void info(Player player, String message, Object... arguments) { Notification.info(player, message, arguments); }
        @Override public void error(Player player, String message, Object... arguments) { Notification.error(player, message, arguments); }
    }
}
