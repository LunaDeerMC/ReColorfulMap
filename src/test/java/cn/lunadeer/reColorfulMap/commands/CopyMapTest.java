package cn.lunadeer.reColorfulMap.commands;

import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.services.ArtworkCopyService;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CopyMapTest {
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final ArtworkCopyService service = mock(ArtworkCopyService.class);
    private final ItemStack[] contents = new ItemStack[36];
    private ItemStack source;
    private BooleanSupplier ready;
    private Function<List<ItemStack>, Boolean> commit;

    @BeforeEach void setup() {
        source = image(); contents[0] = source;
        when(player.getInventory()).thenReturn(inventory);
        when(player.isOnline()).thenReturn(true);
        when(inventory.getItemInMainHand()).thenAnswer(call -> contents[0]);
        when(inventory.getStorageContents()).thenAnswer(call -> contents.clone());
        when(inventory.getItem(anyInt())).thenAnswer(call -> contents[call.getArgument(0)]);
        doAnswer(call -> { contents[call.getArgument(0)] = call.getArgument(1); return null; }).when(inventory).setItem(anyInt(), any());
        when(service.copy(eq(player), any(), eq(1), any(), any())).thenAnswer(call -> {
            ready = call.getArgument(3); commit = call.getArgument(4); return true;
        });
    }

    @Test void successfulCopyPublishesOneNewItemAndLeavesTheOriginalUntouched() {
        CopyMap.copy(player, service);
        assertTrue(ready.getAsBoolean());
        ItemStack copy = image();
        assertTrue(commit.apply(List.of(copy)));
        assertSame(source, contents[0]); assertSame(copy, contents[1]);
        verify(source, never()).setAmount(anyInt());
        verify(inventory, never()).setItem(eq(0), any());
    }

    @Test void fullInventoryRejectsBeforeAnyFileCopy() {
        Arrays.fill(contents, source);
        CopyMap.copy(player, service);
        verifyNoInteractions(service);
        verify(inventory, never()).setItem(anyInt(), any());
        verify(player).sendMessage(contains("empty inventory slot"));
    }

    @Test void changedHeldItemOrDestinationPreventsPublishing() {
        CopyMap.copy(player, service);
        contents[0] = image();
        assertFalse(ready.getAsBoolean()); assertFalse(commit.apply(List.of(image())));
        assertNull(contents[1]);
        contents[0] = source; contents[1] = image();
        assertFalse(ready.getAsBoolean()); assertFalse(commit.apply(List.of(image())));
        assertSame(source, contents[0]);
    }

    @Test void unrelatedInventoryChangesDoNotCancelTheCopy() {
        CopyMap.copy(player, service);
        contents[10] = image();
        assertTrue(ready.getAsBoolean());
        assertTrue(commit.apply(List.of(image())));
        assertSame(source, contents[0]); assertNotNull(contents[10]);
    }

    @Test void disconnectPreventsPublishingWithoutRemovingSource() {
        CopyMap.copy(player, service);
        when(player.isOnline()).thenReturn(false);
        assertFalse(ready.getAsBoolean()); assertFalse(commit.apply(List.of(image())));
        assertSame(source, contents[0]); assertNull(contents[1]);
    }

    @Test void rejectedQueueDoesNotChangeInventory() {
        when(service.copy(eq(player), any(), eq(1), any(), any())).thenReturn(false);
        CopyMap.copy(player, service);
        verify(inventory, never()).setItem(anyInt(), any());
        assertSame(source, contents[0]);
        verify(player).sendMessage(contains("queue is full"));
    }

    @Test void partialWriteFailureRollsBackOnlyDestination() {
        CopyMap.copy(player, service);
        doThrow(new IllegalStateException("network update failed")).doNothing().when(player).updateInventory();
        assertFalse(commit.apply(List.of(image())));
        assertSame(source, contents[0]); assertNull(contents[1]);
    }

    @Test void rollbackFailureIsUncertainSoTheServiceMustKeepImageFiles() {
        CopyMap.copy(player, service);
        doThrow(new IllegalStateException("update failed")).when(player).updateInventory();
        assertThrows(IllegalStateException.class, () -> commit.apply(List.of(image())));
        assertSame(source, contents[0]);
    }

    private ItemStack image() {
        ItemStack item = mock(ItemStack.class);
        MapMeta meta = mock(MapMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.FILLED_MAP);
        when(item.getAmount()).thenReturn(1);
        when(item.clone()).thenReturn(item);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(MapItemData.IMAGE_PATH, PersistentDataType.STRING)).thenReturn(true);
        when(pdc.get(MapItemData.IMAGE_PATH, PersistentDataType.STRING)).thenReturn(ImageReference.thumbnail(UUID.randomUUID()).path());
        return item;
    }
}
