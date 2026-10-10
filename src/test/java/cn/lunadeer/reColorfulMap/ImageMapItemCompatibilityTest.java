package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static cn.lunadeer.reColorfulMap.LegacyMapFixture.ARTWORK;
import static cn.lunadeer.reColorfulMap.LegacyMapFixture.OTHER_ARTWORK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageMapItemCompatibilityTest {
    @TempDir Path directory;
    private MapStorage storage;

    @BeforeEach
    void copyLegacyDirectory() throws Exception {
        storage = LegacyMapFixture.copyTo(directory);
    }

    @Test
    void recognizesExact23LoreWithUuidInHoverWithoutIndexEntry() {
        ItemStack item = map("world", 800, List.of(legacyLore()));

        assertEquals(Optional.of(ARTWORK), ImageMapItem.identify(item, storage));
    }

    @Test
    void recognizesSerializedTextChildrenInLegacySizeAndHover() {
        Component lore = Component.empty().append(Component.text("size: "))
                .append(Component.text("3x2"))
                .hoverEvent(Component.empty().append(Component.text(ARTWORK.toString())));

        assertEquals(Optional.of(ARTWORK), ImageMapItem.legacyArtwork(List.of(lore)));
    }

    @Test
    void resolvesLegacyThumbnailWithOnlyWorldAndMapId() {
        ItemStack item = map("world", 123, null);

        assertEquals(Optional.of(ARTWORK), ImageMapItem.identify(item, storage));
        verify(item.getItemMeta(), never()).getPersistentDataContainer();
        verify(((MapMeta) item.getItemMeta()).getMapView().getWorld(), never()).getPersistentDataContainer();
    }

    @Test
    void resolvesUsingMapViewsOwnWorldName() {
        assertEquals(Optional.of(OTHER_ARTWORK), ImageMapItem.identify(map("world_nether", 123, null), storage));
    }

    @Test
    void indexedTileCannotBecomeWholeArtworkByCopyingThumbnailLore() {
        assertTrue(ImageMapItem.identify(map("world", 125, List.of(legacyLore())), storage).isEmpty());
    }

    @Test
    void ordinaryMapAndOrdinaryLoreRemainVanilla() {
        assertTrue(ImageMapItem.identify(map("world", 700, null), storage).isEmpty());
        assertTrue(ImageMapItem.identify(map("world", 700, List.of(Component.text("My home"))), storage).isEmpty());
        assertTrue(ImageMapItem.identify(map("world", 700, List.of(Component.text("size: 3x2"))), storage).isEmpty());
        assertTrue(ImageMapItem.identify(null, storage).isEmpty());
        ItemStack stone = mock(ItemStack.class);
        when(stone.getType()).thenReturn(Material.STONE);
        assertTrue(ImageMapItem.identify(stone, storage).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"size: 0x2", "size: -3x2", "size: 3x0", "size: 3x2147483648", "size: 3x2 suffix", "size: 03x2"})
    void malformedLegacyDimensionsDoNotClaimOrdinaryMaps(String text) {
        Component lore = Component.text(text).hoverEvent(Component.text(ARTWORK.toString()));

        assertTrue(ImageMapItem.identify(map("world", 700, List.of(lore)), storage).isEmpty());
    }

    @Test
    void invalidOrAbbreviatedUuidAndMultipleLoreLinesAreRejected() {
        assertTrue(ImageMapItem.legacyArtwork(List.of(Component.text("size: 3x2")
                .hoverEvent(Component.text("not-a-uuid")))).isEmpty());
        assertTrue(ImageMapItem.legacyArtwork(List.of(Component.text("size: 3x2")
                .hoverEvent(Component.text("1-1-1-1-1")))).isEmpty());
        assertTrue(ImageMapItem.legacyArtwork(List.of(legacyLore(), Component.text("My map"))).isEmpty());
    }

    private static Component legacyLore() {
        return Component.text("size: 3x2").hoverEvent(Component.text(ARTWORK.toString()));
    }

    private static ItemStack map(String worldName, int id, List<Component> lore) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(worldName);
        MapView view = mock(MapView.class);
        when(view.getWorld()).thenReturn(world);
        when(view.getId()).thenReturn(id);
        MapMeta meta = mock(MapMeta.class);
        when(meta.getMapView()).thenReturn(view);
        when(meta.lore()).thenReturn(lore);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.FILLED_MAP);
        when(item.getItemMeta()).thenReturn(meta);
        return item;
    }
}
