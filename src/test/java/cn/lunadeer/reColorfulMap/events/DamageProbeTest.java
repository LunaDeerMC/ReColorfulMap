package cn.lunadeer.reColorfulMap.events;

import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DamageProbeTest {
    @Test
    void olderApiPreservesAttackerCauseAndDamageWhenCheckingAnotherFrame() {
        Entity attacker = mock(Entity.class);
        ItemFrame target = mock(ItemFrame.class);
        EntityDamageByEntityEvent original = new EntityDamageByEntityEvent(
                attacker, mock(ItemFrame.class), DamageCause.PROJECTILE, 3.5);
        original.setCancelled(true);

        EntityDamageByEntityEvent probe = ImageMapEvent.createDamageProbe(
                EntityDamageByEntityEvent.class, original, target);

        assertSame(attacker, probe.getDamager());
        assertSame(target, probe.getEntity());
        assertEquals(DamageCause.PROJECTILE, probe.getCause());
        assertEquals(3.5, probe.getDamage());
        assertFalse(probe.isCancelled(), "Protection plugins must be able to independently evaluate each frame");
    }

    @Test
    void modernApiPreservesActualDamageSourceAndItsPlayerAttribution() {
        Entity projectile = mock(Entity.class);
        Entity player = mock(Entity.class);
        ItemFrame target = mock(ItemFrame.class);
        DamageSource source = new DamageSource(projectile, player);
        ModernDamageEvent original = new ModernDamageEvent(projectile, mock(ItemFrame.class),
                DamageCause.PROJECTILE, source, 7);
        original.setCancelled(true);

        ModernDamageEvent probe = ImageMapEvent.createDamageProbe(ModernDamageEvent.class, original, target);

        assertSame(target, probe.getEntity());
        assertSame(projectile, probe.getDamager());
        assertEquals(DamageCause.PROJECTILE, probe.getCause());
        assertEquals(7, probe.getDamage());
        assertSame(source, probe.getDamageSource());
        assertSame(player, probe.getDamageSource().causingEntity());
        assertSame(projectile, probe.getDamageSource().directEntity());
        assertFalse(probe.isCancelled());
    }

    @Test
    void unsupportedModernConstructorFailsClosedInsteadOfReplacingTheDamageSource() {
        MissingModernConstructor original = new MissingModernConstructor(mock(Entity.class),
                mock(ItemFrame.class), DamageCause.ENTITY_ATTACK, 1);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ImageMapEvent.createDamageProbe(MissingModernConstructor.class, original, mock(ItemFrame.class)));

        assertInstanceOf(NoSuchMethodException.class, failure.getCause());
    }

    public record DamageSource(Entity directEntity, Entity causingEntity) { }

    /** Models the modern signature while the project still compiles against the minimum Bukkit API. */
    public static final class ModernDamageEvent extends EntityDamageByEntityEvent {
        private final DamageSource damageSource;

        public ModernDamageEvent(Entity damager, Entity target, DamageCause cause, DamageSource damageSource, double damage) {
            super(damager, target, cause, damage);
            this.damageSource = damageSource;
        }

        public DamageSource getDamageSource() {
            return damageSource;
        }
    }

    public static final class MissingModernConstructor extends EntityDamageByEntityEvent {
        public MissingModernConstructor(Entity damager, Entity target, DamageCause cause, double damage) {
            super(damager, target, cause, damage);
        }

        public DamageSource getDamageSource() {
            return new DamageSource(getDamager(), null);
        }
    }
}
