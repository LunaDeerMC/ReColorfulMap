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
    void unavailableFileDoesNotDeleteItsLegacyReferenceOrTouchCanvas() throws Exception {
        MapStorage storage = spy(LegacyMapFixture.copyTo(directory));
        MapStorage.ImageReference reference = MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK);
        Files.delete(directory.resolve(reference.path()));
        String indexBefore = Files.readString(directory.resolve("maps/maps.yml"));
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

            assertTrue(storage.find("world", 123).isPresent());
            assertEquals(indexBefore, Files.readString(directory.resolve("maps/maps.yml")));
            verify(storage, never()).register(any());
            verify(canvas, never()).drawImage(anyInt(), anyInt(), any(Image.class));
        }
    }
}
