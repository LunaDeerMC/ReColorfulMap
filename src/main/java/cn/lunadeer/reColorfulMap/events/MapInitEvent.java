package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.ImageRenderer;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.map.MapView;

public class MapInitEvent implements Listener {
    @EventHandler
    public void onMapInitEvent(MapInitializeEvent event) {
        MapView view = event.getMap();
        World world = view.getWorld();
        if (world == null) {
            return;
        }
        MapStorage storage = ReColorfulMap.getInstance().getMapStorage();
        storage.find(world.getName(), view.getId()).ifPresent(reference -> {
            for (var renderer : view.getRenderers()) {
                view.removeRenderer(renderer);
            }
            // Register a lazy renderer: startup reads the index, not every saved PNG.
            view.addRenderer(new ImageRenderer(storage, reference));
        });
    }
}
