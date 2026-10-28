package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.MapItemData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MapCopyEventTest {
    private final MapCopyEvent listener = new MapCopyEvent();
    private final Player player = mock(Player.class);

    @Test void nativeWorkbenchImageCopyIsCancelledWithoutConsumingAnyInput() {
        CraftingInventory inventory = mock(CraftingInventory.class);
        doReturn(new ItemStack[]{image(), item(Material.MAP), item(Material.MAP)}).when(inventory).getMatrix();
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        listener.onCraft(event);
        verify(event).setCancelled(true);
        verify(player).sendMessage(contains("/mapmanage copy"));
        verify(inventory, never()).setMatrix(any());
        verify(inventory, never()).setResult(any());
    }

    @Test void ordinaryVanillaMapsAndNonCopyRecipesAreNotChanged() {
        for (ItemStack[] matrix : new ItemStack[][]{
                {item(Material.FILLED_MAP), item(Material.MAP)}, {image(), item(Material.PAPER)},
                {image(), item(Material.GLASS_PANE)}, {image(), item(Material.MAP), item(Material.DIAMOND)}}) {
            CraftingInventory inventory = mock(CraftingInventory.class);
            when(inventory.getMatrix()).thenReturn(matrix);
            CraftItemEvent event = mock(CraftItemEvent.class);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getInventory()).thenReturn(inventory);
            listener.onCraft(event);
            verify(event, never()).setCancelled(anyBoolean());
        }
    }

    @Test void cartographyImageCopyIsCancelledAndKeepsInputs() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getType()).thenReturn(InventoryType.CARTOGRAPHY);
        doReturn(image()).when(inventory).getItem(0);
        doReturn(item(Material.MAP)).when(inventory).getItem(1);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getRawSlot()).thenReturn(2);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        listener.onCartography(event);
        verify(event).setCancelled(true);
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test void cartographyInputMovementAndLockingAreUnchanged() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getType()).thenReturn(InventoryType.CARTOGRAPHY);
        doReturn(image()).when(inventory).getItem(0);
        doReturn(item(Material.MAP)).when(inventory).getItem(1);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getRawSlot()).thenReturn(0);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        listener.onCartography(event);
        when(event.getRawSlot()).thenReturn(2);
        doReturn(item(Material.GLASS_PANE)).when(inventory).getItem(1);
        listener.onCartography(event);
        verify(event, never()).setCancelled(anyBoolean());
    }

    private ItemStack image() {
        ItemStack item = item(Material.FILLED_MAP);
        MapMeta meta = mock(MapMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(MapItemData.IMAGE_PATH, PersistentDataType.STRING)).thenReturn(true);
        return item;
    }
    private ItemStack item(Material material) {
        ItemStack result = mock(ItemStack.class);
        when(result.getType()).thenReturn(material);
        return result;
    }
}
