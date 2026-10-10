package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reuses native map IDs discovered on items; PNG paths remain the only persisted index. */
public final class MapViewRegistry {
    private record Key(UUID world, ImageReference reference) { }

    private final MapStorage storage;
    private final Map<Key, MapView> views = new HashMap<>();
    private final Map<MapView, ImageReference> attached = new IdentityHashMap<>();

    public MapViewRegistry(MapStorage storage) {
        this.storage = Objects.requireNonNull(storage);
    }

    /** Caller owns the world/entity region. Creation for the same path is serialized across regions. */
    public synchronized MapView getOrCreate(World world, ImageReference reference, BufferedImage prepared) {
        Objects.requireNonNull(world, "world");
        if (storage.isDeleted(reference.artwork())) {
            throw new IllegalStateException("Artwork was deleted: " + reference.artwork());
        }
        Key key = new Key(world.getUID(), reference);
        MapView view = views.get(key);
        if (view == null) {
            view = Bukkit.createMap(world);
            views.put(key, view);
        }
        attach(view, reference, prepared);
        return view;
    }

    /** Restores every existing alias while choosing the first observed ID for future item creation. */
    public void register(ItemStack item) {
        MapItemData.reference(item).ifPresent(reference -> {
            MapView view = ((MapMeta) item.getItemMeta()).getMapView();
            if (view != null) {
                register(view, reference);
            }
        });
    }

    public synchronized void register(MapView view, ImageReference reference) {
        World world = view.getWorld();
        if (world != null && !storage.isDeleted(reference.artwork())) {
            views.putIfAbsent(new Key(world.getUID(), reference), view);
        }
        attach(view, reference, null);
    }

    private void attach(MapView view, ImageReference reference, BufferedImage prepared) {
        // Existing native IDs may be visible from multiple Folia regions.
        synchronized (view) {
            attached.put(view, reference);
            List<MapRenderer> existing = List.copyOf(view.getRenderers());
            for (MapRenderer renderer : existing) {
                if (renderer instanceof ImageRenderer image && image.matches(storage, reference)) {
                    return;
                }
            }
            for (MapRenderer renderer : existing) {
                view.removeRenderer(renderer);
            }
            view.addRenderer(prepared == null ? new ImageRenderer(storage, reference)
                    : new ImageRenderer(storage, reference, prepared));
        }
    }

    public synchronized void clear() {
        for (Map.Entry<MapView, ImageReference> entry : attached.entrySet()) {
            MapView view = entry.getKey();
            synchronized (view) {
                for (MapRenderer renderer : List.copyOf(view.getRenderers())) {
                    if (renderer instanceof ImageRenderer image && image.matches(storage, entry.getValue())) {
                        view.removeRenderer(renderer);
                    }
                }
            }
        }
        attached.clear();
        views.clear();
        ImageRenderer.clearCache(storage);
    }
}
