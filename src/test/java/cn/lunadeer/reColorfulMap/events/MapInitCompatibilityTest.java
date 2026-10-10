package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.ImageRenderer;
import cn.lunadeer.reColorfulMap.LegacyMapFixture;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import org.bukkit.World;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MapInitCompatibilityTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"world", "world_nether"})
    void restoresCopiedIndexUsingMapViewsWorldWithoutDecodingImages(String worldName) throws Exception {
        MapStorage storage = spy(LegacyMapFixture.copyTo(directory));
        ReColorfulMap plugin = mock(ReColorfulMap.class);
        when(plugin.getMapStorage()).thenReturn(storage);
        World world = mock(World.class);
        when(world.getName()).thenReturn(worldName);
        MapView view = mock(MapView.class);
        when(view.getWorld()).thenReturn(world);
        when(view.getId()).thenReturn(123);
        MapRenderer defaultRenderer = mock(MapRenderer.class);
        // CraftMapView returns a snapshot; clearing this list cannot detach the real renderer.
        when(view.getRenderers()).thenAnswer(call -> new ArrayList<>(List.of(defaultRenderer)));
        List<List<?>> constructorArguments = new ArrayList<>();

        try (MockedStatic<ReColorfulMap> pluginAccess = mockStatic(ReColorfulMap.class);
             MockedConstruction<ImageRenderer> renderers = mockConstruction(ImageRenderer.class,
                     (renderer, context) -> constructorArguments.add(context.arguments()))) {
            pluginAccess.when(ReColorfulMap::getInstance).thenReturn(plugin);

            new MapInitEvent().onMapInitEvent(initialization(view));

            verify(view).removeRenderer(defaultRenderer);
            assertEquals(1, renderers.constructed().size());
            assertEquals(List.of(storage, MapStorage.ImageReference.thumbnail(
                            worldName.equals("world") ? LegacyMapFixture.ARTWORK : LegacyMapFixture.OTHER_ARTWORK)),
                    constructorArguments.get(0));
            verify(view).addRenderer(renderers.constructed().get(0));
            verify(storage, never()).read(any());
            verify(world, never()).getPersistentDataContainer();
        }
    }

    @Test
    void missingLegacyPngDoesNotEraseTheIndexOrFallBackToVanillaRenderer() throws Exception {
        MapStorage storage = spy(LegacyMapFixture.copyTo(directory));
        Files.delete(directory.resolve("maps/" + LegacyMapFixture.ARTWORK + "/thumb.png"));
        ReColorfulMap plugin = mock(ReColorfulMap.class);
        when(plugin.getMapStorage()).thenReturn(storage);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        MapView view = mock(MapView.class);
        when(view.getWorld()).thenReturn(world);
        when(view.getId()).thenReturn(123);
        when(view.getRenderers()).thenReturn(new ArrayList<>());

        try (MockedStatic<ReColorfulMap> pluginAccess = mockStatic(ReColorfulMap.class);
             MockedConstruction<ImageRenderer> renderers = mockConstruction(ImageRenderer.class)) {
            pluginAccess.when(ReColorfulMap::getInstance).thenReturn(plugin);

            new MapInitEvent().onMapInitEvent(initialization(view));

            assertEquals(1, renderers.constructed().size());
            verify(storage, never()).read(any());
            assertEquals(MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK),
                    storage.find("world", 123).orElseThrow());
        }
    }

    @Test
    void ordinaryAndWorldlessMapsKeepTheirRenderers() throws Exception {
        MapStorage storage = LegacyMapFixture.copyTo(directory);
        ReColorfulMap plugin = mock(ReColorfulMap.class);
        when(plugin.getMapStorage()).thenReturn(storage);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        MapView ordinary = mock(MapView.class);
        when(ordinary.getWorld()).thenReturn(world);
        when(ordinary.getId()).thenReturn(5000);
        MapView worldless = mock(MapView.class);

        try (MockedStatic<ReColorfulMap> pluginAccess = mockStatic(ReColorfulMap.class)) {
            pluginAccess.when(ReColorfulMap::getInstance).thenReturn(plugin);
            MapInitEvent listener = new MapInitEvent();

            listener.onMapInitEvent(initialization(ordinary));
            listener.onMapInitEvent(initialization(worldless));

            verify(ordinary, never()).getRenderers();
            verify(ordinary, never()).addRenderer(any());
            verify(worldless, never()).getRenderers();
            verify(worldless, never()).addRenderer(any());
        }
    }

    private static MapInitializeEvent initialization(MapView view) {
        MapInitializeEvent event = mock(MapInitializeEvent.class);
        when(event.getMap()).thenReturn(view);
        return event;
    }
}
