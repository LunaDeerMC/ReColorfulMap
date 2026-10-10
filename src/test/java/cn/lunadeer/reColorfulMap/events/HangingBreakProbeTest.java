package cn.lunadeer.reColorfulMap.events;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent.RemoveCause;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class HangingBreakProbeTest {
    @Test
    void olderApiPreservesRemoverAndCauseWhileTargetingEachProtectedFrame() {
        Entity remover = mock(Entity.class);
        ItemFrame target = mock(ItemFrame.class);
        HangingBreakByEntityEvent original = new HangingBreakByEntityEvent(
                mock(ItemFrame.class), remover, RemoveCause.EXPLOSION);
        original.setCancelled(true);

        HangingBreakByEntityEvent probe = ImageMapEvent.createHangingBreakProbe(
                HangingBreakByEntityEvent.class, original, target);

        assertSame(target, probe.getEntity());
        assertSame(remover, probe.getRemover());
        assertEquals(RemoveCause.EXPLOSION, probe.getCause());
        assertFalse(probe.isCancelled());
    }

    @Test
    void modernApiAlsoPreservesTheExactDamageSource() {
        Entity remover = mock(Entity.class);
        ItemFrame target = mock(ItemFrame.class);
        DamageSource source = new DamageSource();
        ModernBreakEvent original = new ModernBreakEvent(mock(ItemFrame.class), remover, source, RemoveCause.ENTITY);
        original.setCancelled(true);

        ModernBreakEvent probe = ImageMapEvent.createHangingBreakProbe(ModernBreakEvent.class, original, target);

        assertSame(target, probe.getEntity());
        assertSame(remover, probe.getRemover());
        assertSame(source, probe.getDamageSource());
        assertEquals(RemoveCause.ENTITY, probe.getCause());
        assertFalse(probe.isCancelled());
    }

    @Test
    void unsupportedModernConstructorFailsClosedInsteadOfDroppingProtectionMetadata() {
        MissingModernConstructor original = new MissingModernConstructor(
                mock(ItemFrame.class), mock(Entity.class), RemoveCause.ENTITY);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ImageMapEvent.createHangingBreakProbe(MissingModernConstructor.class,
                        original, mock(ItemFrame.class)));

        assertInstanceOf(NoSuchMethodException.class, failure.getCause());
    }

    public static final class DamageSource { }

    /** Mirrors the 26.2 event signature while the test suite compiles against the minimum API. */
    public static final class ModernBreakEvent extends HangingBreakByEntityEvent {
        private final DamageSource source;

        public ModernBreakEvent(Hanging target, Entity remover, DamageSource source, RemoveCause cause) {
            super(target, remover, cause);
            this.source = source;
        }

        public DamageSource getDamageSource() {
            return source;
        }
    }

    public static final class MissingModernConstructor extends HangingBreakByEntityEvent {
        public MissingModernConstructor(Hanging target, Entity remover, RemoveCause cause) {
            super(target, remover, cause);
        }

        public DamageSource getDamageSource() {
            return new DamageSource();
        }
    }
}
