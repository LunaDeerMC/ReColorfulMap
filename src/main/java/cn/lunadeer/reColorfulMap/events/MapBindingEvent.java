package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.ImageRenderer;
import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Restores renderers from actual map items, without an index of map IDs or startup PNG reads. */
public final class MapBindingEvent implements Listener {
    private final ReColorfulMap plugin;

    public MapBindingEvent(ReColorfulMap plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** The caller must own the item/inventory's region. This only attaches a lazy renderer. */
    public void bind(ItemStack item) {
        if (!MapItemData.hasImage(item)) {
            return;
        }
        Optional<ImageReference> reference = MapItemData.reference(item);
        if (reference.isEmpty()) {
            XLogger.warn("Cannot restore image map with an invalid image_path PDC value.");
            return;
        }
        MapView view = ((MapMeta) item.getItemMeta()).getMapView();
        if (view == null) {
            XLogger.warn("Cannot restore image map without a MapView: %s", reference.get().path());
            return;
        }
        MapStorage storage = plugin.getMapStorage();
        // A map can be visible from several regions. Attach its renderer only once.
        synchronized (view) {
            List<MapRenderer> existing = List.copyOf(view.getRenderers());
            for (MapRenderer renderer : existing) {
                if (renderer instanceof ImageRenderer image && image.matches(storage, reference.get())) {
                    return;
                }
            }
            // CraftMapView returns a snapshot, so clearing getRenderers() is insufficient.
            for (MapRenderer renderer : existing) {
                view.removeRenderer(renderer);
            }
            view.addRenderer(new ImageRenderer(storage, reference.get()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        bindPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventorySlotChange(PlayerInventorySlotChangeEvent event) {
        bind(event.getNewItemStack());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        bindInventory(event.getView().getTopInventory());
        bindInventory(event.getView().getBottomInventory());
        bind(event.getView().getCursor());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            bindEntityOnOwner(entity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAdd(EntityAddToWorldEvent event) {
        bindEntityOnOwner(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemFrameChange(PlayerItemFrameChangeEvent event) {
        bind(event.getItemStack());
    }

    /** Supports enabling with online players/loaded chunks, without loading any saved chunks. */
    public void bootstrap() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            bindEntityOnOwner(player);
        }
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                int x = chunk.getX();
                int z = chunk.getZ();
                plugin.getServer().getRegionScheduler().run(plugin, world, x, z, task -> {
                    // A chunk may have unloaded while this task was waiting for its region.
                    if (!world.isChunkLoaded(x, z) || !chunk.isEntitiesLoaded()) {
                        return;
                    }
                    for (Entity entity : chunk.getEntities()) {
                        bindEntityOnOwner(entity);
                    }
                });
            }
        }
    }

    private void bindEntityOnOwner(Entity entity) {
        if (!(entity instanceof ItemFrame || entity instanceof Item || entity instanceof ItemDisplay
                || entity instanceof InventoryHolder)) {
            return;
        }
        if (plugin.getServer().isOwnedByCurrentRegion(entity)) {
            bindEntity(entity);
        } else {
            entity.getScheduler().run(plugin, task -> bindEntity(entity), null);
        }
    }

    private void bindEntity(Entity entity) {
        if (entity instanceof ItemFrame frame) {
            bind(frame.getItem());
        } else if (entity instanceof Item dropped) {
            bind(dropped.getItemStack());
        } else if (entity instanceof ItemDisplay display) {
            bind(display.getItemStack());
        } else if (entity instanceof Player player) {
            bindPlayer(player);
        } else if (entity instanceof InventoryHolder holder) {
            bindInventory(holder.getInventory());
        }
    }

    private void bindPlayer(Player player) {
        bindInventory(player.getInventory());
        bindInventory(player.getEnderChest());
        bind(player.getItemOnCursor());
    }

    private void bindInventory(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            bind(item);
        }
    }
}
