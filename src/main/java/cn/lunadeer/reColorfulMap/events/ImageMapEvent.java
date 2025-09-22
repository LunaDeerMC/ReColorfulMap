package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.*;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.VaultConnect.VaultConnect;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.util.Collection;

import static cn.lunadeer.reColorfulMap.utils.ImageUtils.decodeBase64ToImage;

public class ImageMapEvent implements Listener {

    public static class ImageMapEventText extends ConfigurationPart {
        public String notSupportedDirection = "Don't support place image map in up or down direction";
        public String incompleteItemFrameArray = "Incomplete item frame array need (%d x %d)";
        public String hookEconomyFailed = "Failed to hook economy plugin.";
        public String balanceNotEnough = "Not enough balance to place image map.";
        public String priceInfo = "Matrix size: %d x %d, unit price: %f, total price: %f, your balance: %f";
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void putImageMapsOnItemFrame(PlayerInteractEntityEvent event) {
        if (event.isCancelled()) {
            return;
        }
        // if not item frame return
        Entity entity = event.getRightClicked();
        if (!(entity instanceof ItemFrame item_frame)) {    // where to place
            return;
        }
        Player player = event.getPlayer();  // who placed
        ImageMapItem imageMapItem;  // what to place
        try {
            imageMapItem = new ImageMapItem(event.getPlayer().getInventory().getItemInMainHand());
            XLogger.debug("ImageMapItem size: %d x %d", imageMapItem.getXCount(), imageMapItem.getYCount());
        } catch (Exception e) {
            XLogger.debug("Not a image map: %s", e.getMessage());
            return;
        }
        event.setCancelled(true); // cancel event we need to take over the logic

        try {
            // get item frame matrix
            ItemFrame[][] item_frames = getItemFrameMatrix(item_frame, imageMapItem.getXCount(), imageMapItem.getYCount());

            // handle economy
            if (Configuration.economy.enable) {
                double cost = Configuration.economy.costPerMap * imageMapItem.getXCount() * imageMapItem.getYCount();
                if (!VaultConnect.instance.economyAvailable()) {
                    Notification.error(player, Language.imageMapEvent.hookEconomyFailed);
                    return;
                }
                if (VaultConnect.instance.getBalance(player) < cost) {
                    Notification.error(player, Language.imageMapEvent.balanceNotEnough);
                    Notification.error(player, Language.imageMapEvent.priceInfo,
                            imageMapItem.getXCount(),
                            imageMapItem.getYCount(),
                            Configuration.economy.costPerMap,
                            cost,
                            VaultConnect.instance.getBalance(player));
                    return;
                }
                VaultConnect.instance.withdrawPlayer(player, cost);
            }

            // place them

            // check if every item frame can be interacted
            for (int j = 0; j < imageMapItem.getYCount(); j++) {
                for (int i = 0; i < imageMapItem.getXCount(); i++) {
                    XLogger.debug("Checking item frame [%d, %d] %s", i, j, item_frames[i][j].getLocation().toString());
                    player.getInventory().setItemInMainHand(new ItemStack(Material.FILLED_MAP));
                    PlayerInteractEntityEvent event_put_map = new PlayerInteractEntityEvent(player, item_frames[i][j]);
                    Bukkit.getPluginManager().callEvent(event_put_map);
                    if (event_put_map.isCancelled()) {
                        player.getInventory().setItemInMainHand(imageMapItem);
                        XLogger.debug("Item frame [%d, %d] is not interactable (cancelled)", i, j);
                        return;
                    }
                }
            }
            player.getInventory().setItemInMainHand(imageMapItem);
            // generate each map item and place them
            ItemStack[][] tileMatrix = getTileMatrix(item_frame, imageMapItem);
            for (int j = 0; j < imageMapItem.getYCount(); j++) {
                for (int i = 0; i < imageMapItem.getXCount(); i++) {
                    item_frames[i][j].setItem(new ItemStack(Material.AIR));
                    // place
                    item_frames[i][j].setItem(tileMatrix[i][j]);
                }
            }
        } catch (Exception e) {
            Notification.error(player, e.getMessage());
            return;
        }

        // postprocess remove item from player's hand and remove thumbnail from world
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        PDCImageManager.removeThumbnailFromWorldPDC(imageMapItem);
    }

    private static ItemStack[][] getTileMatrix(ItemFrame originItemFrame, ImageMapItem imageMapItem) throws Exception {
        World world = originItemFrame.getWorld();
        ItemStack[][] tileMatrix = new ItemStack[imageMapItem.getXCount()][imageMapItem.getYCount()];
        for (int j = imageMapItem.getYCount() - 1; j >= 0; j--) {
            for (int i = 0; i < imageMapItem.getXCount(); i++) {
                byte[] tile_b64 = imageMapItem.getTileBase64(i, j);
                ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) mapItem.getItemMeta();
                if (i == 0 && j == imageMapItem.getYCount() - 1) {
                    // copy origin item frame's PDC to the map item
                    meta = (MapMeta) imageMapItem.getItemMeta();
                }
                meta.getPersistentDataContainer().set(originMapLocationX, PersistentDataType.INTEGER, originItemFrame.getLocation().getBlockX());
                meta.getPersistentDataContainer().set(originMapLocationY, PersistentDataType.INTEGER, originItemFrame.getLocation().getBlockY());
                meta.getPersistentDataContainer().set(originMapLocationZ, PersistentDataType.INTEGER, originItemFrame.getLocation().getBlockZ());
                meta.getPersistentDataContainer().set(tileBase64, PersistentDataType.BYTE_ARRAY, tile_b64);
                BufferedImage image = decodeBase64ToImage(tile_b64);
                MapView mapView = Bukkit.createMap(world);
                meta.setMapView(mapView);
                ImageRenderer.renderOnMeta(meta, image);
                mapItem.setItemMeta(meta);
                tileMatrix[i][j] = mapItem;
                // add the tile to world PDC for rendering, and remove the tile cache
                PDCImageManager.addImageBase64WorldPDC(world, mapView.getId(), tile_b64);
                imageMapItem.removeTileFromWorldPDC(i, j);
            }
        }
        return tileMatrix;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void itemFrameBroken(HangingBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        ItemFrame originItemFrame = TryGetOriginItemFrame(event.getEntity());
        if (originItemFrame == null) {
            return;
        }
        ImageMapItem imageMapItem = TryGetImageMapItem(originItemFrame.getItem());
        if (imageMapItem == null) {
            return;
        }
        // drop imageMapItem
        HandleBroken(originItemFrame, imageMapItem);
        event.getEntity().getWorld().dropItemNaturally(event.getEntity().getLocation(), imageMapItem);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void breakImageMapsFromItemFrame(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) {
            return;
        }
        ItemFrame originItemFrame = TryGetOriginItemFrame(event.getEntity());
        if (originItemFrame == null) {
            return;
        }
        ImageMapItem imageMapItem = TryGetImageMapItem(originItemFrame.getItem());
        if (imageMapItem == null) {
            return;
        }
        // drop imageMapItem
        event.setCancelled(true);
        HandleBroken(originItemFrame, imageMapItem);
        event.getDamager().getWorld().dropItemNaturally(event.getEntity().getLocation(), imageMapItem);
    }


    private static @Nullable ItemFrame TryGetOriginItemFrame(Entity entity) {
        if (!(entity instanceof ItemFrame clicked_item_frame)) {
            return null;
        }
        if (clicked_item_frame.getItem().getType() != Material.FILLED_MAP) {
            return null;
        }
        ItemStack mapItemStack = clicked_item_frame.getItem();
        if (!mapItemStack.getItemMeta().getPersistentDataContainer().has(originMapLocationX) ||
                !mapItemStack.getItemMeta().getPersistentDataContainer().has(originMapLocationY) ||
                !mapItemStack.getItemMeta().getPersistentDataContainer().has(originMapLocationZ)) {
            return null;
        }
        Integer origin_x = mapItemStack.getItemMeta().getPersistentDataContainer().get(originMapLocationX, PersistentDataType.INTEGER);
        Integer origin_y = mapItemStack.getItemMeta().getPersistentDataContainer().get(originMapLocationY, PersistentDataType.INTEGER);
        Integer origin_z = mapItemStack.getItemMeta().getPersistentDataContainer().get(originMapLocationZ, PersistentDataType.INTEGER);
        if (origin_x == null || origin_y == null || origin_z == null) {
            return null;
        }
        Location origin = new Location(clicked_item_frame.getWorld(), origin_x, origin_y, origin_z);
        return getItemFrame(origin, clicked_item_frame.getFacing());
    }

    private static @Nullable ImageMapItem TryGetImageMapItem(ItemStack itemStack) {
        try {
            ImageMapItem imageMapItem = new ImageMapItem(itemStack.clone());
            imageMapItem.setUpThumbnail(); // regenerate thumbnail
            return imageMapItem;
        } catch (Exception e) {
            XLogger.debug("Not a image map: %s", e.getMessage());
            return null;
        }
    }

    private static void HandleBroken(ItemFrame originItemFrame, ImageMapItem imageMapItem) {
        ItemFrame[][] item_frames;
        try {
            item_frames = getItemFrameMatrix(originItemFrame, imageMapItem.getXCount(), imageMapItem.getYCount());
        } catch (Exception e) {
            // todo ignore for now, but should not happen or handle with a better way
            return;
        }
        for (int j = 0; j < imageMapItem.getYCount(); j++) {
            for (int i = 0; i < imageMapItem.getXCount(); i++) {
                ItemStack mapItem = item_frames[i][j].getItem();
                byte[] tile_b64 = mapItem.getItemMeta().getPersistentDataContainer().get(tileBase64, PersistentDataType.BYTE_ARRAY);
                imageMapItem.setTileBase64(i, j, tile_b64);
                imageMapItem.saveTileToWorldPDC(i, j);
                item_frames[i][j].setItem(new ItemStack(Material.AIR));
                MapMeta meta = (MapMeta) mapItem.getItemMeta();
                MapView mapView = meta.getMapView();
                if (mapView == null) {
                    continue;
                }
                int mapviewId = mapView.getId();
                PDCImageManager.removeImageBase64WorldPDC(item_frames[i][j].getWorld(), mapviewId);
            }
        }
    }

    private static final NamespacedKey originMapLocationX = new NamespacedKey("re_colorful_map", "origin_map_location_x");
    private static final NamespacedKey originMapLocationY = new NamespacedKey("re_colorful_map", "origin_map_location_y");
    private static final NamespacedKey originMapLocationZ = new NamespacedKey("re_colorful_map", "origin_map_location_z");
    private static final NamespacedKey tileBase64 = new NamespacedKey("re_colorful_map", "tile_base64");

    /**
     * Retrieves a matrix of ItemFrame objects starting from the specified bottom-left ItemFrame.
     *
     * @param left_bottom The bottom-left ItemFrame from which to start the matrix.
     * @param x           The number of ItemFrames along the x-axis.
     * @param y           The number of ItemFrames along the y-axis.
     * @return A 2D array of ItemFrame objects. top-left is [0][0]
     * @throws Exception If the direction of the ItemFrame is UP or DOWN, or if the matrix is incomplete.
     */
    public static ItemFrame[][] getItemFrameMatrix(ItemFrame left_bottom, Integer x, Integer y) throws Exception {
        ItemFrame[][] item_frames = new ItemFrame[x][y];
        Location corner = left_bottom.getLocation();
        BlockFace facing = left_bottom.getFacing();

        if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
            throw new Exception(Language.imageMapEvent.notSupportedDirection);
        }

        Location top_left = corner.add(0, y - 1, 0);

        for (int j = 0; j < y; j++) {
            for (int i = 0; i < x; i++) {
                Location loc = top_left.clone();
                if (facing == BlockFace.NORTH) {
                    loc.add(-i, 0, 0);
                } else if (facing == BlockFace.SOUTH) {
                    loc.add(i, 0, 0);
                } else if (facing == BlockFace.WEST) {
                    loc.add(0, 0, i);
                } else if (facing == BlockFace.EAST) {
                    loc.add(0, 0, -i);
                }
                loc.add(0, -j, 0);
                ItemFrame item_frame = getItemFrame(loc, facing);
                if (item_frame == null) {
                    throw new Exception(String.format(Language.imageMapEvent.incompleteItemFrameArray, x, y));
                }
                item_frames[i][j] = item_frame;
            }
        }
        return item_frames;
    }

    private static ItemFrame getItemFrame(Location loc, BlockFace facing) {
        Collection<Entity> entities = loc.getWorld().getNearbyEntities(loc, 1, 1, 1);
        for (Entity entity : entities) {
            if (entity.getLocation().getBlockX() != loc.getBlockX() || entity.getLocation().getBlockY() != loc.getBlockY() || entity.getLocation().getBlockZ() != loc.getBlockZ()) {
                continue;
            }
            if (entity instanceof ItemFrame itemFrame) {
                if (itemFrame.getFacing() != facing) {
                    continue;
                }
                return itemFrame;
            }
        }
        return null;
    }
}
