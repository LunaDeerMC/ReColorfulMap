package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ImageCacheTest {
    @TempDir Path directory;

    @Test
    void aliasesSharePendingDecodeAndPixelsUntilCapacityEvictsThem() throws Exception {
        MapStorage storage = spy(LegacyMapFixture.copyTo(directory));
        List<Runnable> workers = new ArrayList<>();
        List<ImageReference> failures = new ArrayList<>();
        ImageCache cache = new ImageCache(2, workers::add, (reference, error) -> failures.add(reference));
        ImageReference original = ImageReference.thumbnail(LegacyMapFixture.ARTWORK);
        ImageReference alias = new ImageReference(LegacyMapFixture.ARTWORK, -1, -1);
        ImageReference second = ImageReference.tile(LegacyMapFixture.ARTWORK, 0, 0);
        ImageReference third = ImageReference.tile(LegacyMapFixture.ARTWORK, 1, 0);

        assertNull(cache.getOrRequest(storage, original));
        assertNull(cache.getOrRequest(storage, alias));
        assertEquals(1, workers.size(), "Two map IDs pointing to one PNG should share one pending decode");
        workers.remove(0).run();
        BufferedImage firstPixels = cache.getOrRequest(storage, original);
        assertSame(firstPixels, cache.getOrRequest(storage, alias));
        verify(storage).read(original);

        BufferedImage secondPixels = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        BufferedImage thirdPixels = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        cache.put(storage, second, secondPixels);
        cache.put(storage, third, thirdPixels);
        assertSame(secondPixels, cache.getOrRequest(storage, second));
        assertSame(thirdPixels, cache.getOrRequest(storage, third));
        assertNull(cache.getOrRequest(storage, original), "Preloaded images must obey the same two-image capacity limit");
        assertEquals(1, workers.size());
        workers.remove(0).run();
        verify(storage, times(2)).read(original);
        assertEquals(0xffff0000, cache.getOrRequest(storage, original).getRGB(0, 0));
        assertTrue(failures.isEmpty());
    }

    @Test
    void missingImageIsReportedOnceWithoutRepeatedDiskReadsOnEveryRenderTick() throws Exception {
        MapStorage storage = spy(LegacyMapFixture.copyTo(directory));
        ImageReference reference = ImageReference.thumbnail(LegacyMapFixture.ARTWORK);
        Files.delete(directory.resolve(reference.path()));
        List<Runnable> workers = new ArrayList<>();
        List<ImageReference> failures = new ArrayList<>();
        ImageCache cache = new ImageCache(2, workers::add, (missing, error) -> failures.add(missing));

        cache.getOrRequest(storage, reference);
        workers.remove(0).run();
        for (int tick = 0; tick < 20; tick++) {
            assertNull(cache.getOrRequest(storage, reference));
        }

        assertTrue(workers.isEmpty());
        assertEquals(List.of(reference), failures);
        verify(storage).read(reference);
        assertTrue(Files.isRegularFile(directory.resolve("maps/" + reference.artwork() + "/0_0.png")));
        verify(storage, never()).deleteArtwork(reference.artwork());
    }
    @Test
    void deletionInvalidatesCachedAndQueuedTilesButPreservesOtherArtworks() throws Exception {
        MapStorage storage = spy(LegacyMapFixture.copyTo(directory));
        List<Runnable> workers = new ArrayList<>();
        List<ImageReference> failures = new ArrayList<>();
        ImageCache cache = new ImageCache(8, workers::add, (reference, error) -> failures.add(reference));
        ImageReference removed = ImageReference.thumbnail(LegacyMapFixture.ARTWORK);
        ImageReference queued = ImageReference.tile(LegacyMapFixture.ARTWORK, 0, 0);
        ImageReference kept = ImageReference.thumbnail(LegacyMapFixture.OTHER_ARTWORK);
        BufferedImage removedPixels = storage.read(removed);
        BufferedImage keptPixels = storage.read(kept);
        cache.put(storage, removed, removedPixels);
        cache.put(storage, kept, keptPixels);
        assertNull(cache.getOrRequest(storage, queued));

        assertTrue(storage.deleteArtwork(removed.artwork()));

        assertNull(cache.getOrRequest(storage, removed));
        assertNull(cache.getOrRequest(storage, queued));
        cache.put(storage, removed, removedPixels);
        assertNull(cache.getOrRequest(storage, removed), "Late preloading cannot restore a deleted artwork");
        assertSame(keptPixels, cache.getOrRequest(storage, kept));
        workers.forEach(Runnable::run);
        verify(storage, never()).read(queued);
        assertTrue(failures.isEmpty());
    }

    @Test
    void deletionDuringBackgroundReadCannotPublishStalePixels() throws Exception {
        MapStorage storage = spy(LegacyMapFixture.copyTo(directory));
        List<Runnable> workers = new ArrayList<>();
        List<ImageReference> failures = new ArrayList<>();
        ImageCache cache = new ImageCache(2, workers::add, (reference, error) -> failures.add(reference));
        ImageReference reference = ImageReference.thumbnail(LegacyMapFixture.ARTWORK);
        BufferedImage pixels = storage.read(reference);
        doAnswer(call -> {
            storage.deleteArtwork(reference.artwork());
            return pixels;
        }).when(storage).read(reference);

        assertNull(cache.getOrRequest(storage, reference));
        workers.remove(0).run();

        assertNull(cache.getOrRequest(storage, reference));
        assertTrue(workers.isEmpty());
        assertTrue(failures.isEmpty());
    }

    @Test
    void wallLargerThanCacheDecodesEachTileOnceBeforeItIsConsumed() throws Exception {
        MapStorage storage = mock(MapStorage.class);
        Map<ImageReference, Integer> reads = new HashMap<>();
        when(storage.read(any())).thenAnswer(call -> {
            ImageReference reference = call.getArgument(0);
            reads.merge(reference, 1, Integer::sum);
            return new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        });
        List<Runnable> workers = new ArrayList<>();
        ImageCache cache = new ImageCache(128, workers::add, (reference, error) -> { });
        List<ImageReference> wall = references(576);
        for (ImageReference reference : wall) {
            assertNull(cache.getOrRequest(storage, reference));
            assertTrue(workers.size() <= 2);
        }
        drain(workers);
        assertEquals(128, reads.size(), "Unread pixels must stop background decoding at the pixel limit");

        Set<ImageReference> rendered = new HashSet<>();
        for (int frame = 0; frame < 10 && rendered.size() < wall.size(); frame++) {
            for (ImageReference reference : wall) {
                if (!rendered.contains(reference) && cache.getOrRequest(storage, reference) != null) {
                    rendered.add(reference);
                }
                assertTrue(workers.size() <= 2);
            }
            drain(workers);
        }
        assertEquals(576, rendered.size());
        assertEquals(576, reads.size());
        assertTrue(reads.values().stream().allMatch(count -> count == 1),
                "A ready but unread tile must never be evicted by another queued tile");
    }

    @Test
    void pendingQueueIsBoundedAndOverflowCanRetryOnALaterRender() throws Exception {
        MapStorage storage = mock(MapStorage.class);
        Set<ImageReference> reads = new HashSet<>();
        when(storage.read(any())).thenAnswer(call -> {
            reads.add(call.getArgument(0));
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        });
        List<Runnable> workers = new ArrayList<>();
        ImageCache cache = new ImageCache(128, workers::add, (reference, error) -> { });
        List<ImageReference> references = references(1300);
        references.forEach(reference -> cache.getOrRequest(storage, reference));
        Set<ImageReference> rendered = new HashSet<>();
        for (int frame = 0; frame < 12 && rendered.size() < 1024; frame++) {
            drain(workers);
            for (ImageReference reference : references.subList(0, 1024)) {
                if (!rendered.contains(reference) && cache.getOrRequest(storage, reference) != null) {
                    rendered.add(reference);
                }
            }
        }
        drain(workers);
        assertEquals(1024, rendered.size());
        assertEquals(1024, reads.size(), "Overflow requests must not remain in an unbounded hidden queue");
        assertNull(cache.getOrRequest(storage, references.get(1024)));
        drain(workers);
        assertTrue(cache.getOrRequest(storage, references.get(1024)) != null);
        assertEquals(1025, reads.size());
    }

    @Test
    void abandonedReadyPixelsDoNotBlockNewlyVisibleMapsForever() throws Exception {
        MapStorage storage = mock(MapStorage.class);
        when(storage.read(any())).thenReturn(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        List<Runnable> workers = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        ImageCache cache = new ImageCache(1, workers::add, (reference, error) -> { }, clock::get);
        List<ImageReference> references = references(2);
        cache.getOrRequest(storage, references.get(0));
        drain(workers);
        cache.getOrRequest(storage, references.get(1));
        assertTrue(workers.isEmpty(), "Unread pixels remain protected while their request is recent");
        clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
        cache.getOrRequest(storage, references.get(1));
        drain(workers);
        assertTrue(cache.getOrRequest(storage, references.get(1)) != null);
        verify(storage).read(references.get(1));
    }

    @Test
    void abandonedQueuedRequestsAreDiscardedWithoutReadingTheirFiles() throws Exception {
        MapStorage storage = mock(MapStorage.class);
        when(storage.read(any())).thenReturn(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        List<Runnable> workers = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        ImageCache cache = new ImageCache(1, workers::add, (reference, error) -> { }, clock::get);
        List<ImageReference> references = references(3);
        cache.getOrRequest(storage, references.get(0));
        drain(workers);
        cache.getOrRequest(storage, references.get(1));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
        cache.getOrRequest(storage, references.get(2));
        drain(workers);
        assertTrue(cache.getOrRequest(storage, references.get(2)) != null);
        verify(storage, never()).read(references.get(1));
    }

    @Test
    void failureBookkeepingIsBoundedWhileRecentFailuresAreNotRetried() throws Exception {
        MapStorage storage = mock(MapStorage.class);
        when(storage.read(any())).thenThrow(new java.io.IOException("Missing test PNG"));
        List<Runnable> workers = new ArrayList<>();
        List<ImageReference> failures = new ArrayList<>();
        ImageCache cache = new ImageCache(2, workers::add, (reference, error) -> failures.add(reference));
        List<ImageReference> references = references(1100);
        for (ImageReference reference : references) {
            cache.getOrRequest(storage, reference);
            drain(workers);
        }
        cache.getOrRequest(storage, references.get(1099));
        assertTrue(workers.isEmpty());
        cache.getOrRequest(storage, references.get(0));
        drain(workers);
        assertEquals(1101, failures.size(), "An old failure must eventually leave bounded bookkeeping");
        verify(storage, times(2)).read(references.get(0));
        verify(storage, times(1)).read(references.get(1099));
    }

    @Test
    void clearingStorageDuringReadRevokesThatReadAndKeepsOtherStoragePixels() throws Exception {
        MapStorage storage = mock(MapStorage.class);
        MapStorage otherStorage = mock(MapStorage.class);
        List<Runnable> workers = new ArrayList<>();
        ImageCache cache = new ImageCache(2, workers::add, (reference, error) -> { });
        ImageReference reference = references(1).get(0);
        BufferedImage pixels = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        cache.put(otherStorage, reference, pixels);
        when(storage.read(reference)).thenAnswer(call -> {
            cache.clear(storage);
            return pixels;
        });
        cache.getOrRequest(storage, reference);
        drain(workers);
        assertSame(pixels, cache.getOrRequest(otherStorage, reference));
        assertNull(cache.getOrRequest(storage, reference), "A cleared in-flight read must not republish pixels");
        verify(storage).read(reference);
    }

    @Test
    void preloadCannotEvictUnreadPixelsOrExceedReservedCapacity() throws Exception {
        MapStorage storage = mock(MapStorage.class);
        BufferedImage decoded = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        BufferedImage preloaded = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        when(storage.read(any())).thenReturn(decoded);
        List<Runnable> workers = new ArrayList<>();
        ImageCache cache = new ImageCache(1, workers::add, (reference, error) -> { });
        List<ImageReference> references = references(2);
        cache.getOrRequest(storage, references.get(0));
        drain(workers);
        cache.put(storage, references.get(1), preloaded);
        assertSame(decoded, cache.getOrRequest(storage, references.get(0)));
        assertNull(cache.getOrRequest(storage, references.get(1)));
        drain(workers);
        assertSame(decoded, cache.getOrRequest(storage, references.get(1)));
    }

    @Test
    void concurrentReadersReserveCapacityBeforeDecoding() throws Exception {
        MapStorage storage = mock(MapStorage.class);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger total = new AtomicInteger();
        when(storage.read(any())).thenAnswer(call -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            total.incrementAndGet();
            started.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            } finally {
                active.decrementAndGet();
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ImageCache cache = new ImageCache(2, executor::execute, (reference, error) -> { });
        try {
            for (ImageReference reference : references(100)) {
                cache.getOrRequest(storage, reference);
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, maximum.get());
            assertEquals(2, total.get(), "Both decoded results are unread, so further decoding must stop");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void clearingCancelledScheduledReadersAllowsReuseAndRejectsTheirLateCallbacks() throws Exception {
        MapStorage oldStorage = mock(MapStorage.class);
        MapStorage newStorage = mock(MapStorage.class);
        when(newStorage.read(any())).thenReturn(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        List<Runnable> workers = new ArrayList<>();
        ImageCache cache = new ImageCache(2, workers::add, (reference, error) -> { });
        List<ImageReference> references = references(3);
        cache.getOrRequest(oldStorage, references.get(0));
        cache.getOrRequest(oldStorage, references.get(1));
        assertEquals(2, workers.size());
        List<Runnable> oldCallbacks = List.copyOf(workers);

        cache.clear(oldStorage);
        workers.clear(); // The scheduler cancels queued plugin tasks without entering their runnables.
        cache.getOrRequest(newStorage, references.get(0));
        cache.getOrRequest(newStorage, references.get(1));
        assertEquals(2, workers.size(), "Cancelled readers must not permanently occupy the global read slots");

        oldCallbacks.forEach(Runnable::run); // A callback already dispatched at shutdown may still arrive.
        verify(oldStorage, never()).read(any());
        verify(newStorage, never()).read(any());
        assertEquals(2, workers.size(), "Stale tasks must neither consume new requests nor release new leases");
        drain(workers);
        assertTrue(cache.getOrRequest(newStorage, references.get(0)) != null);
        assertTrue(cache.getOrRequest(newStorage, references.get(1)) != null);
        cache.getOrRequest(newStorage, references.get(2));
        drain(workers);
        assertTrue(cache.getOrRequest(newStorage, references.get(2)) != null);
        verify(newStorage, times(3)).read(any());
    }

    private static List<ImageReference> references(int count) {
        UUID artwork = UUID.randomUUID();
        List<ImageReference> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(ImageReference.tile(artwork, index % 32, index / 32));
        }
        return result;
    }

    private static void drain(List<Runnable> workers) {
        while (!workers.isEmpty()) {
            workers.remove(0).run();
        }
    }

}
