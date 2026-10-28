package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

/** Native copy recipes cannot give every result a separate image identity; use the copy command. */
public final class MapCopyEvent implements Listener {
    public static class CopyText extends ConfigurationPart {
        public String usage = "Use /mapmanage copy while holding an image map.";
        public String space = "Leave one empty inventory slot for the new map.";
        public String changed = "Copy cancelled because the held map or destination slot changed. The original was kept.";
        public String unavailable = "An image copy is already running or the queue is full. Please wait.";
        public String failed = "The map could not be copied. The original was kept; check the server log.";
        public String uncertain = "The copy result could not be confirmed. Its image files were kept; check your inventory and the server log.";
        public String copied = "An independent copy was added to your inventory.";
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean image = false, blank = false;
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (item == null || item.getType().isAir()) continue;
            if (item.getType() == Material.MAP) blank = true;
            else if (!image && MapItemData.hasImage(item)) image = true;
            else return;
        }
        if (image && blank) {
            event.setCancelled(true);
            player.sendMessage("[ReColorfulMap] " + Language.mapCopy.usage);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCartography(InventoryClickEvent event) {
        if (event instanceof CraftItemEvent || event.getRawSlot() != 2
                || event.getInventory().getType() != InventoryType.CARTOGRAPHY
                || !(event.getWhoClicked() instanceof Player player)) return;
        ItemStack source = event.getInventory().getItem(0);
        ItemStack blank = event.getInventory().getItem(1);
        if (MapItemData.hasImage(source) && blank != null && blank.getType() == Material.MAP) {
            event.setCancelled(true);
            player.sendMessage("[ReColorfulMap] " + Language.mapCopy.usage);
        }
    }
}
