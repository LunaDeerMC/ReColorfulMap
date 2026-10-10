package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MapItemDataTest {
    @Test
    void writesOnlyAStringPathAndRestoresThumbnailOrTileWithoutMapViewOrLore() throws Exception {
        Fixture fixture = fixture();
        UUID id = UUID.randomUUID();
        ImageReference thumbnail = ImageReference.thumbnail(id);

        MapItemData.setReference(fixture.meta(), thumbnail);

        assertEquals(Map.of(MapItemData.IMAGE_PATH, thumbnail.path()), fixture.values());
        assertTrue(MapItemData.hasImage(fixture.item()));
        assertEquals(thumbnail, MapItemData.requireReference(fixture.item()));
        ImageReference tile = ImageReference.tile(id, 5, 12);
        MapItemData.setReference(fixture.meta(), tile);
        assertEquals(tile, MapItemData.reference(fixture.item()).orElseThrow());
        assertEquals(Map.of(MapItemData.IMAGE_PATH, tile.path()), fixture.values());
        verify(fixture.meta(), never()).getMapView();
        verify(fixture.meta(), never()).lore();
    }

    @Test
    void malformedStringStillClaimsItemButDoesNotResolve() {
        Fixture fixture = fixture();
        fixture.values().put(MapItemData.IMAGE_PATH, "maps/../../server.properties");

        assertTrue(MapItemData.hasImage(fixture.item()));
        assertTrue(MapItemData.reference(fixture.item()).isEmpty());
        assertThrows(IOException.class, () -> MapItemData.requireReference(fixture.item()));
    }

    @Test
    void emptyStringStillClaimsItemAsDamaged() {
        Fixture fixture = fixture();
        fixture.values().put(MapItemData.IMAGE_PATH, "");

        assertTrue(MapItemData.hasImage(fixture.item()));
        assertTrue(MapItemData.reference(fixture.item()).isEmpty());
    }

    @Test
    void missingTagAndWrongDataTypeDoNotClaimVanillaMap() {
        Fixture fixture = fixture();

        assertFalse(MapItemData.hasImage(fixture.item()));
        assertTrue(MapItemData.reference(fixture.item()).isEmpty());
        fixture.values().put(MapItemData.IMAGE_PATH, new byte[]{1, 2, 3});
        assertFalse(MapItemData.hasImage(fixture.item()));
        assertTrue(MapItemData.reference(fixture.item()).isEmpty());
    }

    @Test
    void nullNonMapAndMissingMetaCannotBeAnImageMap() {
        assertFalse(MapItemData.hasImage(null));
        assertTrue(MapItemData.reference(null).isEmpty());
        assertThrows(IOException.class, () -> MapItemData.requireReference(null));
        Fixture fixture = fixture();
        fixture.values().put(MapItemData.IMAGE_PATH, ImageReference.thumbnail(UUID.randomUUID()).path());
        when(fixture.item().getType()).thenReturn(Material.STONE);
        assertFalse(MapItemData.hasImage(fixture.item()));
        assertTrue(MapItemData.reference(fixture.item()).isEmpty());
        when(fixture.item().getType()).thenReturn(Material.FILLED_MAP);
        when(fixture.item().getItemMeta()).thenReturn(null);
        assertFalse(MapItemData.hasImage(fixture.item()));
        assertTrue(MapItemData.reference(fixture.item()).isEmpty());
    }

    private record Fixture(ItemStack item, MapMeta meta, Map<NamespacedKey, Object> values) {
    }

    private static Fixture fixture() {
        Map<NamespacedKey, Object> values = new HashMap<>();
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.has(MapItemData.IMAGE_PATH, PersistentDataType.STRING))
                .thenAnswer(call -> values.get(MapItemData.IMAGE_PATH) instanceof String);
        when(pdc.get(MapItemData.IMAGE_PATH, PersistentDataType.STRING))
                .thenAnswer(call -> values.get(MapItemData.IMAGE_PATH) instanceof String value ? value : null);
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(pdc).set(eq(MapItemData.IMAGE_PATH), eq(PersistentDataType.STRING), anyString());
        MapMeta meta = mock(MapMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.FILLED_MAP);
        when(item.getItemMeta()).thenReturn(meta);
        return new Fixture(item, meta, values);
    }
}
