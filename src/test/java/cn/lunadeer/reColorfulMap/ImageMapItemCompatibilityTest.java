package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static cn.lunadeer.reColorfulMap.LegacyMapFixture.ARTWORK;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runtime recognition depends exclusively on the item's PDC; offline migration has separate tests. */
class ImageMapItemCompatibilityTest {
    @Test
    void identifiesTaggedThumbnailWithoutMapViewOrLore() {
        ItemStack item = map(ImageReference.thumbnail(ARTWORK));

        assertEquals(Optional.of(ARTWORK), ImageMapItem.identify(item));
        assertTrue(ImageMapItem.isImageMap(item));
        verify((MapMeta) item.getItemMeta(), never()).getMapView();
        verify(item.getItemMeta(), never()).lore();
    }

    @Test
    void taggedTileCannotBecomeWholeArtworkByCopyingThumbnailLore() {
        ItemStack item = map(ImageReference.tile(ARTWORK, 1, 0));
        ((MapMeta) item.getItemMeta()).lore(List.of(legacyLore()));

        assertTrue(ImageMapItem.identify(item).isEmpty());
        assertFalse(ImageMapItem.isImageMap(item));
        assertTrue(MapItemData.hasImage(item));
    }

    @Test
    void legacyLoreAloneDoesNotClaimMapWithoutMigratedPdc() {
        ItemStack item = map(null);
        ((MapMeta) item.getItemMeta()).lore(List.of(legacyLore()));

        assertTrue(ImageMapItem.identify(item).isEmpty());
        assertFalse(ImageMapItem.isImageMap(item));
    }

    @Test
    void changingOrRemovingLorePreservesTaggedArtworkIdentity() {
        ItemStack item = map(ImageReference.thumbnail(ARTWORK));
        ((MapMeta) item.getItemMeta()).lore(List.of(Component.text("My renamed artwork")));
        assertEquals(Optional.of(ARTWORK), ImageMapItem.identify(item));
        ((MapMeta) item.getItemMeta()).lore(null);
        assertEquals(Optional.of(ARTWORK), ImageMapItem.identify(item));
    }

    @Test
    void malformedTaggedPathIsClaimedSoVanillaCannotConsumeTheBrokenArtwork() {
        ItemStack item = map(null);
        item.getItemMeta().getPersistentDataContainer().set(MapItemData.IMAGE_PATH,
                PersistentDataType.STRING, "maps/../../outside.png");

        assertTrue(ImageMapItem.isImageMap(item));
        assertTrue(ImageMapItem.identify(item).isEmpty());
    }

    @Test
    void ordinaryMapAndNonMapItemsRemainVanilla() {
        assertTrue(ImageMapItem.identify(map(null)).isEmpty());
        assertFalse(ImageMapItem.isImageMap(map(null)));
        assertTrue(ImageMapItem.identify(null).isEmpty());
        assertFalse(ImageMapItem.isImageMap(null));
        ItemStack stone = mock(ItemStack.class);
        when(stone.getType()).thenReturn(Material.STONE);
        assertTrue(ImageMapItem.identify(stone).isEmpty());
        assertFalse(ImageMapItem.isImageMap(stone));
    }

    @Test
    void metadataClonePreservesPathWithoutSharingMutablePdc() {
        MapMeta original = LegacyMapFixture.mapMeta(null);
        MapItemData.setReference(original, ImageReference.thumbnail(ARTWORK));
        MapMeta copy = original.clone();
        MapItemData.setReference(copy, ImageReference.tile(ARTWORK, 2, 1));

        assertEquals(ImageReference.thumbnail(ARTWORK).path(),
                original.getPersistentDataContainer().get(MapItemData.IMAGE_PATH, PersistentDataType.STRING));
        assertEquals(ImageReference.tile(ARTWORK, 2, 1).path(),
                copy.getPersistentDataContainer().get(MapItemData.IMAGE_PATH, PersistentDataType.STRING));
    }

    private static Component legacyLore() {
        return Component.text("size: 3x2").hoverEvent(Component.text(ARTWORK.toString()));
    }

    private static ItemStack map(ImageReference reference) {
        MapMeta meta = LegacyMapFixture.mapMeta(null);
        if (reference != null) {
            MapItemData.setReference(meta, reference);
        }
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.FILLED_MAP);
        when(item.getItemMeta()).thenReturn(meta);
        return item;
    }
}
