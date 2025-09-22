package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.ImageRenderer;
import cn.lunadeer.reColorfulMap.PDCImageManager;
import cn.lunadeer.reColorfulMap.utils.ImageUtils;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;

public class MapInitEvent implements Listener {
    @EventHandler
    public void onMapInitEvent(MapInitializeEvent event) {
        MapView view = event.getMap();
        World world = view.getWorld();
        if (world == null) {
            return;
        }
        byte[] base64 = PDCImageManager.getImageBase64WorldPDC(world, view.getId());
        if (base64 == null) {
            return;
        }
        try {
            view.getRenderers().clear();
            BufferedImage image = ImageUtils.decodeBase64ToImage(base64);
            view.addRenderer(new ImageRenderer(image));
        } catch (Exception e) {
            XLogger.warn(e.getMessage());
        }
    }
}
