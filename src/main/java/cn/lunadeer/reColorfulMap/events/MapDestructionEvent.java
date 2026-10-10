package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Removes artwork files only after the server reports actual destruction of a dropped item. */
public final class MapDestructionEvent implements Listener {
    private final ReColorfulMap plugin;
    private final MapStorage storage;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public MapDestructionEvent(ReColorfulMap plugin) {
        this.plugin = plugin;
        this.storage = plugin.getMapStorage();
    }

    /**
     * Use the modern event's explicit cause without making older API versions fail to load.
     * The older EntityRemoveFromWorldEvent also fires for pickups and chunk unloads and
     * must never serve as evidence that an artwork has been destroyed.
     */
    public void register() {
        try {
            Class<? extends EntityEvent> eventType = Class.forName(
                    "org.bukkit.event.entity.EntityRemoveEvent", false, plugin.getClass().getClassLoader())
                    .asSubclass(EntityEvent.class);
            register(eventType);
        } catch (ReflectiveOperationException | ClassCastException failure) {
            plugin.getLogger().warning("Automatic artwork deletion is unavailable: this server has no "
                    + "EntityRemoveEvent with an explicit cause. Artwork files will be kept.");
        }
    }

    void register(Class<? extends EntityEvent> eventType) throws NoSuchMethodException {
        Method causeMethod = eventType.getMethod("getCause");
        plugin.getServer().getPluginManager().registerEvent(eventType, this, EventPriority.MONITOR,
                (listener, event) -> {
                    if (!eventType.isInstance(event)) {
                        return;
                    }
                    try {
                        Object cause = causeMethod.invoke(event);
                        if (cause instanceof Enum<?> removalCause) {
                            removed(((EntityEvent) event).getEntity(), removalCause.name());
                        }
                    } catch (ReflectiveOperationException failure) {
                        throw new EventException(failure);
                    }
                }, plugin, true);
    }

    public static boolean isDestructionCause(String cause) {
        return "DEATH".equals(cause) || "DESPAWN".equals(cause) || "OUT_OF_WORLD".equals(cause);
    }

    // All entity/item access stays on the event's owning thread. The worker receives only a UUID.
    void removed(Entity entity, String cause) {
        if (!isDestructionCause(cause) || !(entity instanceof Item item)) {
            return;
        }
        // Damage can release a bundle's or shulker box's contents as surviving item entities.
        // Despawn and falling out of the world actually discard the complete item tree.
        boolean includeContents = "DESPAWN".equals(cause) || "OUT_OF_WORLD".equals(cause);
        ContainedMapArtworks.Result result = ContainedMapArtworks.collect(item.getItemStack(), includeContents);
        result.artworks().forEach(this::delete);
        if (result.truncated()) {
            plugin.getLogger().fine("Destroyed container exceeded artwork traversal limits; "
                    + "unvisited artwork files were kept.");
        }
    }

    private void delete(UUID artwork) {
        if (!pending.add(artwork)) {
            return;
        }
        try {
            Scheduler.runTaskAsync(() -> {
                try {
                    storage.deleteArtwork(artwork);
                } catch (Exception failure) {
                    plugin.getLogger().log(Level.WARNING, "Cannot delete destroyed artwork " + artwork, failure);
                } finally {
                    pending.remove(artwork);
                }
            });
        } catch (RuntimeException failure) {
            pending.remove(artwork);
            plugin.getLogger().log(Level.WARNING, "Cannot schedule deletion of destroyed artwork " + artwork, failure);
        }
    }
}
