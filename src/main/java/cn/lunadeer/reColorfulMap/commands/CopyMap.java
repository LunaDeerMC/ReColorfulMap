package cn.lunadeer.reColorfulMap.commands;

import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.services.ArtworkCopyService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.function.BooleanSupplier;

/** Copies the held image once, without consuming the original or making an economy transaction. */
public final class CopyMap {
    private CopyMap() { }

    public static void copy(Player player, ArtworkCopyService copies) {
        PlayerInventory inventory = player.getInventory();
        ItemStack held = inventory.getItemInMainHand();
        if (MapItemData.reference(held).isEmpty()) {
            message(player, Language.mapCopy.usage);
            return;
        }
        int empty = -1;
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (empty(contents[i])) { empty = i; break; }
        }
        if (empty < 0) {
            message(player, Language.mapCopy.space);
            return;
        }
        final int destination = empty;
        int selected = inventory.getHeldItemSlot();
        ItemStack source = held.clone();
        BooleanSupplier ready = () -> player.isOnline() && inventory.getHeldItemSlot() == selected
                && source.equals(inventory.getItemInMainHand()) && empty(inventory.getItem(destination));
        if (!copies.copy(player, source, 1, ready, created -> {
            if (!ready.getAsBoolean() || created.size() != 1) {
                message(player, Language.mapCopy.changed);
                return false;
            }
            try {
                inventory.setItem(destination, created.get(0));
                player.updateInventory();
            } catch (RuntimeException failure) {
                try {
                    inventory.setItem(destination, null);
                    player.updateInventory();
                } catch (RuntimeException rollbackFailure) {
                    if (rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
                    throw failure; // Its images must survive uncertain partial delivery.
                }
                message(player, Language.mapCopy.failed);
                return false;
            }
            // A failed notification cannot turn a completed inventory commit into a failed copy.
            try { message(player, Language.mapCopy.copied); } catch (RuntimeException ignored) { }
            return true;
        })) message(player, Language.mapCopy.unavailable);
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static void message(Player player, String message) {
        player.sendMessage("[ReColorfulMap] " + message);
    }
}
