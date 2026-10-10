package cn.lunadeer.reColorfulMap.services;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BukkitDeliveryTest {
    @Test void fullInventoryAcceptsOnlyAValidDroppedEntity() throws Exception {
        Player player = mock(Player.class); World world = mock(World.class);
        PlayerInventory inventory = mock(PlayerInventory.class); ItemStack stack = mock(ItemStack.class);
        Item drop = mock(Item.class); Location location = new Location(world, 1, 2, 3);
        when(player.getInventory()).thenReturn(inventory); when(player.getWorld()).thenReturn(world); when(player.getLocation()).thenReturn(location);
        HashMap<Integer, ItemStack> leftovers = new HashMap<>(); leftovers.put(0, stack);
        when(inventory.addItem(stack)).thenReturn(leftovers); when(world.dropItemNaturally(location, stack)).thenReturn(drop);
        GenerationService.BukkitPlatform platform = new GenerationService.BukkitPlatform();
        assertThrows(IOException.class, () -> platform.deliver(player, stack));
        verify(drop, never()).remove();
        when(drop.isValid()).thenReturn(true);
        assertDoesNotThrow(() -> platform.deliver(player, stack));
    }
    @Test void inventoryDeliveryDoesNotSpawnASecondCopy() throws Exception {
        Player player = mock(Player.class); PlayerInventory inventory = mock(PlayerInventory.class); ItemStack stack = mock(ItemStack.class);
        when(player.getInventory()).thenReturn(inventory); when(inventory.addItem(stack)).thenReturn(new HashMap<>());
        new GenerationService.BukkitPlatform().deliver(player, stack);
        verify(inventory).addItem(stack); verify(player, never()).getWorld();
    }
}
