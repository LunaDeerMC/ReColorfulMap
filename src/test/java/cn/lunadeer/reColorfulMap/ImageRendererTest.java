package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ImageRendererTest {
    @TempDir Path directory;

    @Test
    void lazyRendererDecodesOnceOffThreadThenDrawsEachCanvasOnce() throws Exception {
        MapStorage storage = spy(LegacyMapFixture.copyTo(directory));
        MapStorage.ImageReference reference = MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK);
        MapView view = mock(MapView.class);
        MapCanvas canvas = mock(MapCanvas.class);
        Player player = mock(Player.class);
        List<Runnable> backgroundTasks = new ArrayList<>();
        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            scheduler.when(() -> Scheduler.runTaskAsync(any(Runnable.class))).thenAnswer(call -> {
                backgroundTasks.add(call.getArgument(0));
                return null;
            });
            ImageRenderer renderer = new ImageRenderer(storage, reference);
            verify(storage, never()).read(any());

            renderer.render(view, canvas, player);
            renderer.render(view, canvas, player);

            assertEquals(1, backgroundTasks.size());
            verify(storage, never()).read(any());
            verify(canvas, never()).drawImage(anyInt(), anyInt(), any(Image.class));
            backgroundTasks.get(0).run();
            verify(storage).read(reference);
            verify(canvas, never()).drawImage(anyInt(), anyInt(), any(Image.class));

            renderer.render(view, canvas, player);
            renderer.render(view, canvas, player);

            ArgumentCaptor<Image> drawn = ArgumentCaptor.forClass(Image.class);
            verify(canvas).drawImage(eq(0), eq(0), drawn.capture());
            assertEquals(0xffff0000, ((BufferedImage) drawn.getValue()).getRGB(0, 0));
            MapCanvas anotherCanvas = mock(MapCanvas.class);
            renderer.render(view, anotherCanvas, player);
            verify(anotherCanvas).drawImage(0, 0, drawn.getValue());
            scheduler.verify(() -> Scheduler.runTaskAsync(any(Runnable.class)), times(1));
        }
    }

    @Test
    void unavailableFileDoesNotDeleteOtherArtworkFilesOrTouchCanvas() throws Exception {
        MapStorage storage = spy(LegacyMapFixture.copyTo(directory));
        MapStorage.ImageReference reference = MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK);
        Files.delete(directory.resolve(reference.path()));
        MapView view = mock(MapView.class);
        MapCanvas canvas = mock(MapCanvas.class);
        Player player = mock(Player.class);
        List<Runnable> backgroundTasks = new ArrayList<>();
        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class);
             MockedStatic<XLogger> logger = mockStatic(XLogger.class)) {
            scheduler.when(() -> Scheduler.runTaskAsync(any(Runnable.class))).thenAnswer(call -> {
                backgroundTasks.add(call.getArgument(0));
                return null;
            });
            ImageRenderer renderer = new ImageRenderer(storage, reference);
            renderer.render(view, canvas, player);
            backgroundTasks.get(0).run();
            renderer.render(view, canvas, player);

            assertTrue(Files.isRegularFile(directory.resolve("maps/" + LegacyMapFixture.ARTWORK + "/0_0.png")));
            org.junit.jupiter.api.Assertions.assertFalse(storage.isDeleted(LegacyMapFixture.ARTWORK));
            verify(storage, never()).deleteArtwork(any());
            verify(canvas, never()).drawImage(anyInt(), anyInt(), any(Image.class));
        }
    }
    @Test
    @SuppressWarnings("deprecation")
    void deletingArtworkClearsEveryPreviouslyDrawnCanvasOnceAndDoesNotRedraw() throws Exception {
        MapStorage storage = LegacyMapFixture.copyTo(directory);
        MapStorage.ImageReference reference = MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK);
        BufferedImage pixels = storage.read(reference);
        ImageRenderer renderer = new ImageRenderer(storage, reference, pixels);
        MapView view = mock(MapView.class);
        Player player = mock(Player.class);
        MapCanvas canvas = mock(MapCanvas.class);
        renderer.render(view, canvas, player);
        verify(canvas).drawImage(0, 0, pixels);

        assertTrue(storage.deleteArtwork(reference.artwork()));
        renderer.render(view, canvas, player);
        renderer.render(view, canvas, player);

        verify(canvas, times(128 * 128)).setPixel(anyInt(), anyInt(), eq((byte) 0));
        verify(canvas).setPixel(0, 0, (byte) 0);
        verify(canvas).setPixel(127, 127, (byte) 0);
        verify(canvas, times(1)).drawImage(anyInt(), anyInt(), any(Image.class));
        MapCanvas anotherCanvas = mock(MapCanvas.class);
        renderer.render(view, anotherCanvas, player);
        renderer.render(view, anotherCanvas, player);
        verify(anotherCanvas, times(128 * 128)).setPixel(anyInt(), anyInt(), eq((byte) 0));
        verify(anotherCanvas, never()).drawImage(anyInt(), anyInt(), any(Image.class));
    }

    @Test
    void shutdownClearsPreloadedPixelsFromTheSharedCache() throws Exception {
        MapStorage storage = spy(LegacyMapFixture.copyTo(directory));
        MapStorage.ImageReference reference = MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK);
        BufferedImage pixels = storage.read(reference);
        ImageRenderer renderer = new ImageRenderer(storage, reference, pixels);
        MapView view = mock(MapView.class);
        Player player = mock(Player.class);
        MapCanvas canvas = mock(MapCanvas.class);
        List<Runnable> backgroundTasks = new ArrayList<>();
        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            scheduler.when(() -> Scheduler.runTaskAsync(any(Runnable.class))).thenAnswer(call -> {
                backgroundTasks.add(call.getArgument(0));
                return null;
            });
            ImageRenderer.clearCache(storage);
            renderer.render(view, canvas, player);
            verify(canvas, never()).drawImage(anyInt(), anyInt(), any(Image.class));
            assertEquals(1, backgroundTasks.size());
            backgroundTasks.remove(0).run();
            renderer.render(view, canvas, player);
            verify(canvas).drawImage(anyInt(), anyInt(), any(Image.class));
            verify(storage, times(2)).read(reference);
        } finally {
            ImageRenderer.clearCache(storage);
        }
    }

}
