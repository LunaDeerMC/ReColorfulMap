package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainedMapArtworksTest {
    @Test
    void findsMapsInShulkerBoxesAndNestedBundlesAndDeduplicatesArtwork() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ItemStack root = shulker(map(first), bundle(map(first), shulker(map(second))));

        var result = ContainedMapArtworks.collect(root, true);

        assertEquals(Set.of(first, second), result.artworks());
        assertFalse(result.truncated());
        assertThrows(UnsupportedOperationException.class, () -> result.artworks().add(UUID.randomUUID()));
    }

    @Test
    void excludesContentsWhenDamageCanReleaseThem() {
        UUID artwork = UUID.randomUUID();
        assertEquals(Set.of(), ContainedMapArtworks.collect(shulker(bundle(map(artwork))), false).artworks());
        assertEquals(Set.of(artwork), ContainedMapArtworks.collect(map(artwork), false).artworks());
    }

    @Test
    void ignoresNullAirOrdinaryMapsAndMalformedReferences() {
        ItemStack air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        ItemStack stone = mock(ItemStack.class);
        when(stone.getType()).thenReturn(Material.STONE);
        ItemStack ordinaryMap = mock(ItemStack.class);
        when(ordinaryMap.getType()).thenReturn(Material.FILLED_MAP);
        ItemStack malformed = map(UUID.randomUUID());
        when(malformed.getItemMeta().getPersistentDataContainer()
                .get(MapItemData.IMAGE_PATH, PersistentDataType.STRING)).thenReturn("../../other/data.png");

        var result = ContainedMapArtworks.collect(shulker(null, air, stone, ordinaryMap, malformed), true);

        assertTrue(result.artworks().isEmpty());
        assertFalse(result.truncated());
        assertEquals(Set.of(), ContainedMapArtworks.collect(null, true).artworks());
    }

    @Test
    void includesMapsAtDepthLimitButKeepsDeeperData() {
        UUID artwork = UUID.randomUUID();
        ItemStack root = map(artwork);
        for (int depth = 0; depth < ContainedMapArtworks.MAX_DEPTH; depth++) {
            root = bundle(root);
        }
        assertEquals(Set.of(artwork), ContainedMapArtworks.collect(root, true).artworks());
        var beyondLimit = ContainedMapArtworks.collect(bundle(root), true);
        assertTrue(beyondLimit.artworks().isEmpty());
        assertTrue(beyondLimit.truncated());
    }

    @Test
    void aCyclicMetadataSnapshotCannotLoopForever() {
        ItemStack root = mock(ItemStack.class);
        BundleMeta meta = mock(BundleMeta.class);
        when(root.getType()).thenReturn(Material.BUNDLE);
        when(root.getItemMeta()).thenReturn(meta);
        when(meta.getItems()).thenReturn(List.of(root));

        var result = ContainedMapArtworks.collect(root, true);

        assertTrue(result.artworks().isEmpty());
        assertTrue(result.truncated());
    }

    @Test
    void nodeBudgetRetainsUnvisitedArtwork() {
        UUID included = UUID.randomUUID();
        UUID unvisited = UUID.randomUUID();
        List<ItemStack> contents = new ArrayList<>(Collections.nCopies(ContainedMapArtworks.MAX_ITEMS, map(included)));
        contents.add(map(unvisited));
        ItemStack root = bundle(contents.toArray(ItemStack[]::new));

        var result = ContainedMapArtworks.collect(root, true);

        assertEquals(Set.of(included), result.artworks());
        assertTrue(result.truncated());
    }

    static ItemStack bundle(ItemStack... contents) {
        ItemStack stack = mock(ItemStack.class);
        BundleMeta meta = mock(BundleMeta.class);
        when(stack.getType()).thenReturn(Material.BUNDLE);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getItems()).thenReturn(Arrays.asList(contents));
        return stack;
    }

    static ItemStack shulker(ItemStack... contents) {
        ItemStack stack = mock(ItemStack.class);
        BlockStateMeta meta = mock(BlockStateMeta.class);
        ShulkerBox box = mock(ShulkerBox.class);
        Inventory snapshot = mock(Inventory.class);
        when(stack.getType()).thenReturn(Material.SHULKER_BOX);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.hasBlockState()).thenReturn(true);
        when(meta.getBlockState()).thenReturn(box);
        when(box.getSnapshotInventory()).thenReturn(snapshot);
        when(snapshot.getContents()).thenReturn(contents);
        return stack;
    }

    static ItemStack map(UUID artwork) {
        ItemStack stack = mock(ItemStack.class);
        MapMeta meta = mock(MapMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(stack.getType()).thenReturn(Material.FILLED_MAP);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(MapItemData.IMAGE_PATH, PersistentDataType.STRING)).thenReturn(true);
        when(pdc.get(MapItemData.IMAGE_PATH, PersistentDataType.STRING))
                .thenReturn(MapStorage.ImageReference.thumbnail(artwork).path());
        return stack;
    }
}
