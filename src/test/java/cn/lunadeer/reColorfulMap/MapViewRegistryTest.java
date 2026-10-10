package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MapViewRegistryTest {
    @Test
    void repeatedPlacementAndRecoveryReuseTheSameWorldAndImageId() {
        MapStorage storage = mock(MapStorage.class);
        MapViewRegistry registry = new MapViewRegistry(storage);
        World world = world();
        MapView view = view(world);
        ImageReference reference = ImageReference.thumbnail(UUID.randomUUID());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createMap(world)).thenReturn(view);
            for (int move = 0; move < 100; move++) {
                assertSame(view, registry.getOrCreate(world, reference, null));
            }
            bukkit.verify(() -> Bukkit.createMap(world), times(1));
        }
        assertEquals(1, view.getRenderers().size());
        assertInstanceOf(ImageRenderer.class, view.getRenderers().get(0));
        verify(view, times(1)).addRenderer(any());
    }

    @Test
    void nativeMapsRemainSeparateAcrossWorldsAndImagePaths() {
        MapStorage storage = mock(MapStorage.class);
        MapViewRegistry registry = new MapViewRegistry(storage);
        World firstWorld = world();
        World otherWorld = world();
        UUID artwork = UUID.randomUUID();
        ImageReference thumbnail = ImageReference.thumbnail(artwork);
        ImageReference tile = ImageReference.tile(artwork, 0, 0);
        MapView firstThumbnail = view(firstWorld);
        MapView firstTile = view(firstWorld);
        MapView otherThumbnail = view(otherWorld);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createMap(firstWorld)).thenReturn(firstThumbnail, firstTile);
            bukkit.when(() -> Bukkit.createMap(otherWorld)).thenReturn(otherThumbnail);
            assertSame(firstThumbnail, registry.getOrCreate(firstWorld, thumbnail, null));
            assertSame(firstTile, registry.getOrCreate(firstWorld, tile, null));
            assertSame(otherThumbnail, registry.getOrCreate(otherWorld, thumbnail, null));
            assertSame(firstThumbnail, registry.getOrCreate(firstWorld, thumbnail, null));
            bukkit.verify(() -> Bukkit.createMap(firstWorld), times(2));
            bukkit.verify(() -> Bukkit.createMap(otherWorld), times(1));
        }
    }

    @Test
    void restoredMapIdsAndAliasesAreRenderedWithoutAllocatingNewNativeMaps() {
        MapStorage storage = mock(MapStorage.class);
        MapViewRegistry registry = new MapViewRegistry(storage);
        World world = world();
        MapView original = view(world);
        MapView alias = view(world);
        ImageReference reference = ImageReference.tile(UUID.randomUUID(), 2, 1);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            registry.register(original, reference);
            registry.register(alias, reference);
            assertSame(original, registry.getOrCreate(world, reference, null));
            bukkit.verify(() -> Bukkit.createMap(any(World.class)), never());
        }
        for (MapView restored : List.of(original, alias)) {
            assertEquals(1, restored.getRenderers().size());
            ImageRenderer renderer = assertInstanceOf(ImageRenderer.class, restored.getRenderers().get(0));
            assertTrue(renderer.matches(storage, reference));
        }
    }

    @Test
    void repeatedBindingPreservesTheRendererAndItsCompletedCanvases() {
        MapStorage storage = mock(MapStorage.class);
        MapViewRegistry registry = new MapViewRegistry(storage);
        World world = world();
        MapView view = view(world);
        ImageReference reference = ImageReference.thumbnail(UUID.randomUUID());
        registry.register(view, reference);
        MapRenderer firstRenderer = view.getRenderers().get(0);
        for (int event = 0; event < 100; event++) {
            registry.register(view, reference);
        }
        assertSame(firstRenderer, view.getRenderers().get(0));
        verify(view, never()).removeRenderer(any());
        verify(view, times(1)).addRenderer(any());
    }

    @Test
    void deletedArtworkCannotAllocateOrReuseAViewForNewItems() {
        MapStorage storage = mock(MapStorage.class);
        MapViewRegistry registry = new MapViewRegistry(storage);
        World world = world();
        ImageReference reference = ImageReference.thumbnail(UUID.randomUUID());
        when(storage.isDeleted(reference.artwork())).thenReturn(true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            assertThrows(IllegalStateException.class, () -> registry.getOrCreate(world, reference, null));
            bukkit.verify(() -> Bukkit.createMap(any(World.class)), never());
        }
    }

    @Test
    void clearingRegistryDetachesItsRenderersFromEveryAliasAndPreservesOtherPlugins() {
        MapStorage storage = mock(MapStorage.class);
        MapViewRegistry registry = new MapViewRegistry(storage);
        World world = world();
        MapView original = view(world);
        MapView alias = view(world);
        ImageReference reference = ImageReference.thumbnail(UUID.randomUUID());
        registry.register(original, reference);
        registry.register(alias, reference);
        MapRenderer originalRenderer = original.getRenderers().get(0);
        MapRenderer aliasRenderer = alias.getRenderers().get(0);
        MapRenderer foreign = mock(MapRenderer.class);
        original.addRenderer(foreign);
        alias.addRenderer(foreign);

        registry.clear();

        assertEquals(List.of(foreign), original.getRenderers());
        assertEquals(List.of(foreign), alias.getRenderers());
        verify(original).removeRenderer(originalRenderer);
        verify(alias).removeRenderer(aliasRenderer);
        verify(original, never()).removeRenderer(foreign);
        verify(alias, never()).removeRenderer(foreign);
        MapView fresh = view(world);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createMap(world)).thenReturn(fresh);
            assertSame(fresh, registry.getOrCreate(world, reference, null));
            bukkit.verify(() -> Bukkit.createMap(world), times(1));
        }
    }

    private static World world() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        return world;
    }

    private static MapView view(World world) {
        MapView view = mock(MapView.class);
        List<MapRenderer> renderers = new ArrayList<>();
        when(view.getWorld()).thenReturn(world);
        when(view.getRenderers()).thenAnswer(call -> List.copyOf(renderers));
        doAnswer(call -> {
            renderers.add(call.getArgument(0));
            return null;
        }).when(view).addRenderer(any());
        doAnswer(call -> renderers.remove(call.<MapRenderer>getArgument(0)))
                .when(view).removeRenderer(any());
        return view;
    }
}
