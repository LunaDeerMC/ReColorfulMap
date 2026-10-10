package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.ImageMapItem;
import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.services.ArtworkWallService;
import cn.lunadeer.reColorfulMap.services.ArtworkWallGeometry;
import cn.lunadeer.reColorfulMap.services.ArtworkProtection;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Event recognition only; wall transactions and file preparation live in ArtworkWallService. */
public final class ImageMapEvent implements Listener {
    public static class ImageMapEventText extends ConfigurationPart {
        public String notSupportedDirection = "Don't support place image map in up or down direction";
        public String incompleteItemFrameArray = "Incomplete item frame array need (%d x %d)";
        public String occupiedItemFrame = "All item frames must be empty.";
        public String inconsistentItemFrameArray = "The item frame array is incomplete or contains a different image.";
        public String protectedItemFrame = "You cannot modify one or more of these item frames.";
        public String operationBusy = "Too many image wall operations are running. Please try again shortly.";
        public String imageLoading = "Loading image metadata. Please try again in a moment.";
        public String mapChanged = "The held map or item frames changed. Please try again.";
        public String unownedRegion = "The image crosses a server region that cannot be modified from here.";
        public String unloadedChunk = "Some item frames are in unloaded chunks. Load the entire image area and try again.";
        public String operationFailed = "Failed to update image map: %s";
    }


    private final ArtworkWallService walls;

    public ImageMapEvent() { this(ReColorfulMap.getInstance().getArtworkWallService()); }
    public ImageMapEvent(ArtworkWallService walls) { this.walls = java.util.Objects.requireNonNull(walls); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void putImageMapsOnItemFrame(PlayerInteractEntityEvent event) {
        if (event.isCancelled() || walls.isProtectionProbe() || event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof ItemFrame clicked)) return;
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand().clone();
        if (!ImageMapItem.isImageMap(held)) return;
        event.setCancelled(true);
        walls.place(event.getPlayer(), clicked, held);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void itemFrameBroken(HangingBreakEvent event) {
        if (event.isCancelled() || walls.isProtectionProbe() || !(event.getEntity() instanceof ItemFrame clicked)) return;
        MapItemData.reference(clicked.getItem()).filter(reference -> !reference.thumbnail())
                .ifPresent(reference -> walls.pickup(event, clicked, reference));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakImageMapsFromItemFrame(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || walls.isProtectionProbe() || !(event.getEntity() instanceof ItemFrame clicked)) return;
        MapItemData.reference(clicked.getItem()).filter(reference -> !reference.thumbnail())
                .ifPresent(reference -> walls.pickup(event, clicked, reference));
    }

    public static Location tileLocation(Location origin, BlockFace facing, int x, int y, int rows) {
        return ArtworkWallGeometry.tileLocation(origin, facing, x, y, rows);
    }
    public static Location originLocation(Location tile, BlockFace facing, int x, int y, int rows) {
        return ArtworkWallGeometry.originLocation(tile, facing, x, y, rows);
    }
    public static ItemFrame[][] getItemFrameMatrix(ItemFrame origin, Integer columns, Integer rows) throws Exception {
        return ArtworkWallGeometry.getItemFrameMatrix(origin, columns, rows);
    }
    public static boolean isExpectedTile(MapStorage.ImageReference reference, MapStorage.Artwork artwork, int x, int y) {
        return ArtworkWallService.isExpectedTile(reference, artwork, x, y);
    }
    static <T extends HangingBreakByEntityEvent> T createHangingBreakProbe(
            Class<T> type, HangingBreakByEntityEvent original, Hanging target) {
        return ArtworkProtection.createHangingBreakProbe(type, original, target);
    }
    static <T extends EntityDamageByEntityEvent> T createDamageProbe(
            Class<T> type, EntityDamageByEntityEvent original, Entity target) {
        return ArtworkProtection.createDamageProbe(type, original, target);
    }
}
