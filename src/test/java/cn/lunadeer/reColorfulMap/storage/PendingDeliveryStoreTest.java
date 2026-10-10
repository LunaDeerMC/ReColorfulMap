package cn.lunadeer.reColorfulMap.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static cn.lunadeer.reColorfulMap.storage.PendingDeliveryStore.*;

class PendingDeliveryStoreTest {
    @TempDir Path root;
    UUID artwork = UUID.randomUUID(), owner = UUID.randomUUID();

    @Test void pendingAndChargeSurviveRestartWithoutAnImageIndex() throws Exception {
        PendingDeliveryStore store = new PendingDeliveryStore(root);
        Entry pending = store.create(artwork, owner, 125.5);
        Entry charging = store.update(pending, e -> e.delivery(Delivery.DELIVERING).payment(Payment.CHARGING));
        PendingDeliveryStore reopened = new PendingDeliveryStore(root);
        assertEquals(charging, reopened.find(artwork).orElseThrow());
        assertTrue(reopened.find(artwork).orElseThrow().uncertain());
        assertEquals(1, reopened.list(owner).size());
        assertTrue(reopened.list(UUID.randomUUID()).isEmpty());
        assertFalse(Files.exists(root.resolve("maps.yml")));
    }
    @Test void concurrentCompareAndSetAllowsExactlyOneClaim() throws Exception {
        PendingDeliveryStore store = new PendingDeliveryStore(root);
        Entry original = store.create(artwork, owner, 0);
        AtomicInteger won = new AtomicInteger();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> claim = () -> {
            start.await();
            try { store.update(original, e -> e.delivery(Delivery.DELIVERING)); won.incrementAndGet(); }
            catch (IOException expected) { }
            return null;
        };
        Future<Void> first = threads.submit(claim), second = threads.submit(claim);
        start.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS); threads.shutdownNow();
        assertEquals(1, won.get());
    }
    @Test void deliveredAuditPreventsAnotherCreateButDoesNotClutterPendingList() throws Exception {
        PendingDeliveryStore store = new PendingDeliveryStore(root);
        Entry original = store.create(artwork, owner, 0);
        store.update(original, e -> e.delivery(Delivery.DELIVERED));
        assertTrue(store.list(owner).isEmpty());
        assertThrows(IOException.class, () -> store.create(artwork, owner, 0));
        assertEquals(Delivery.DELIVERED, new PendingDeliveryStore(root).find(artwork).orElseThrow().delivery());
    }
    @Test void receiptCannotReassignOwnerOrChangeQuotedPrice() throws Exception {
        PendingDeliveryStore store = new PendingDeliveryStore(root);
        Entry original = store.create(artwork, owner, 5);
        assertThrows(IOException.class, () -> store.update(original, e -> new Entry(e.artwork(), UUID.randomUUID(), e.delivery(), e.payment(), e.amount(), e.createdAt())));
        assertThrows(IOException.class, () -> store.update(original, e -> new Entry(e.artwork(), e.owner(), e.delivery(), e.payment(), 0, e.createdAt())));
        assertEquals(original, store.find(artwork).orElseThrow());
    }
    @Test void symlinkDirectoryAndReceiptAreRejectedWithoutChangingTarget() throws Exception {
        Path external = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(root.resolve("deliveries"), external);
        assertThrows(IOException.class, () -> new PendingDeliveryStore(root));
        Files.delete(root.resolve("deliveries"));
        PendingDeliveryStore store = new PendingDeliveryStore(root);
        Path target = external.resolve("target"); Files.writeString(target, "keep");
        Files.createSymbolicLink(root.resolve("deliveries/" + artwork + ".properties"), target);
        assertThrows(IOException.class, () -> store.create(artwork, owner, 0));
        assertEquals("keep", Files.readString(target));
        assertThrows(IOException.class, () -> new PendingDeliveryStore(root));
    }
    @Test void ownerThreadInspectionNeverWaitsForTheDiskWritersMonitor() throws Exception {
        PendingDeliveryStore store = new PendingDeliveryStore(root);
        Entry pending = store.create(artwork, owner, 0);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            synchronized (store) {
                // The writer holds this same monitor across its atomic move/fsync.
                Future<Entry> inspected = reader.submit(() -> store.find(artwork).orElseThrow());
                assertEquals(pending, inspected.get(1, TimeUnit.SECONDS));
                Future<Integer> listed = reader.submit(() -> store.list(owner).size());
                assertEquals(1, listed.get(1, TimeUnit.SECONDS));
            }
        } finally { reader.shutdownNow(); }
    }
    @Test void startupPruningOnlyDropsCompletedReceiptsWhoseArtworkNoLongerExists() throws Exception {
        PendingDeliveryStore store = new PendingDeliveryStore(root);
        Entry complete = store.create(artwork, owner, 0);
        store.update(complete, e -> e.delivery(Delivery.DELIVERED));
        UUID pending = UUID.randomUUID(); store.create(pending, owner, 0);
        UUID live = UUID.randomUUID(); Entry retained = store.create(live, owner, 0);
        store.update(retained, e -> e.delivery(Delivery.DELIVERED));
        assertEquals(1, store.pruneCompleted(id -> !id.equals(live)));
        PendingDeliveryStore reopened = new PendingDeliveryStore(root);
        assertTrue(reopened.find(artwork).isEmpty()); assertTrue(reopened.find(pending).isPresent()); assertTrue(reopened.find(live).isPresent());
    }
    @Test void malformedOrMismatchedReceiptFailsClosed() throws Exception {
        PendingDeliveryStore store = new PendingDeliveryStore(root);
        store.create(artwork, owner, 0);
        Files.move(root.resolve("deliveries/" + artwork + ".properties"), root.resolve("deliveries/" + UUID.randomUUID() + ".properties"));
        assertThrows(IOException.class, () -> new PendingDeliveryStore(root));
    }
}
