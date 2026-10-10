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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
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
        assertTrue(storage.find("world", 123).isPresent());
    }
}
