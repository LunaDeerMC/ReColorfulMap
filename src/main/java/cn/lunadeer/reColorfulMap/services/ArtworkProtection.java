package cn.lunadeer.reColorfulMap.services;

import cn.lunadeer.reColorfulMap.Language;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import java.lang.reflect.Method;

/** Dispatches matching protection events for every frame, preserving modern damage attribution. */
public final class ArtworkProtection {
    private final ThreadLocal<Boolean> protectionProbe = ThreadLocal.withInitial(() -> false);
    public boolean isProbe() { return protectionProbe.get(); }
    public void checkPlacementPermission(Player player, ItemFrame[][] frames) {
        protectionProbe.set(true);
        try {
            for (ItemFrame[] column : frames) {
                for (ItemFrame frame : column) {
                    PlayerInteractEntityEvent probe = new PlayerInteractEntityEvent(player, frame, EquipmentSlot.HAND);
                    Bukkit.getPluginManager().callEvent(probe);
                    if (probe.isCancelled()) {
                        throw new IllegalStateException(Language.imageMapEvent.protectedItemFrame);
                    }
                }
            }
        } finally {
            protectionProbe.remove();
        }
    }

    public void checkBreakPermission(HangingBreakEvent original, ItemFrame[][] frames) {
        protectionProbe.set(true);
        try {
            for (ItemFrame[] column : frames) {
                for (ItemFrame frame : column) {
                    if (frame.isFixed()) {
                        throw new IllegalStateException(Language.imageMapEvent.protectedItemFrame);
                    }
                    HangingBreakEvent probe = original instanceof HangingBreakByEntityEvent byEntity
                            ? createHangingBreakProbe(HangingBreakByEntityEvent.class, byEntity, frame)
                            : new HangingBreakEvent(frame, original.getCause());
                    Bukkit.getPluginManager().callEvent(probe);
                    if (probe.isCancelled()) {
                        throw new IllegalStateException(Language.imageMapEvent.protectedItemFrame);
                    }
                }
            }
        } finally {
            protectionProbe.remove();
        }
    }

    /** Paper 26.2 requires a DamageSource; older versions expose only the three-argument constructor. */
    public static <T extends HangingBreakByEntityEvent> T createHangingBreakProbe(
            Class<T> eventType, HangingBreakByEntityEvent original, Hanging target) {
        try {
            Method damageSource;
            try {
                damageSource = eventType.getMethod("getDamageSource");
            } catch (NoSuchMethodException legacyApi) {
                return eventType.getConstructor(Hanging.class, Entity.class, HangingBreakEvent.RemoveCause.class)
                        .newInstance(target, original.getRemover(), original.getCause());
            }
            // Preserve the actual source for protection plugins instead of fabricating generic damage.
            return eventType.getConstructor(Hanging.class, Entity.class, damageSource.getReturnType(),
                            HangingBreakEvent.RemoveCause.class)
                    .newInstance(target, original.getRemover(), damageSource.invoke(original), original.getCause());
        } catch (ReflectiveOperationException failure) {
            // An unsupported event shape must leave the painting in place, never skip protection checks.
            throw new IllegalStateException("Cannot create an item frame protection event on this server", failure);
        }
    }

    @SuppressWarnings("deprecation")
    public void checkDamagePermission(EntityDamageByEntityEvent original, ItemFrame[][] frames) {
        protectionProbe.set(true);
        try {
            for (ItemFrame[] column : frames) {
                for (ItemFrame frame : column) {
                    if (frame.isFixed()) {
                        throw new IllegalStateException(Language.imageMapEvent.protectedItemFrame);
                    }
                    EntityDamageByEntityEvent probe = createDamageProbe(EntityDamageByEntityEvent.class, original, frame);
                    Bukkit.getPluginManager().callEvent(probe);
                    if (probe.isCancelled()) {
                        throw new IllegalStateException(Language.imageMapEvent.protectedItemFrame);
                    }
                }
            }
        } finally {
            protectionProbe.remove();
        }
    }

    /** Retains modern damage attribution for protection plugins while supporting the 1.20.1 API. */
    public static <T extends EntityDamageByEntityEvent> T createDamageProbe(
            Class<T> eventType, EntityDamageByEntityEvent original, Entity target) {
        try {
            Method damageSource;
            try {
                damageSource = eventType.getMethod("getDamageSource");
            } catch (NoSuchMethodException legacyApi) {
                return eventType.getConstructor(Entity.class, Entity.class,
                                EntityDamageByEntityEvent.DamageCause.class, double.class)
                        .newInstance(original.getDamager(), target, original.getCause(), original.getDamage());
            }
            return eventType.getConstructor(Entity.class, Entity.class, EntityDamageByEntityEvent.DamageCause.class,
                            damageSource.getReturnType(), double.class)
                    .newInstance(original.getDamager(), target, original.getCause(),
                            damageSource.invoke(original), original.getDamage());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot create an item frame damage protection event on this server", failure);
        }
    }

}
