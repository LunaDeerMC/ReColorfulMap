package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MapDestructionEventTest {
    private final UUID artwork = UUID.randomUUID();
    private MapStorage storage;
    private Logger logger;
    private ReColorfulMap plugin;
    private MapDestructionEvent listener;

    @BeforeEach
    void setUp() {
        storage = mock(MapStorage.class);
        logger = mock(Logger.class);
        plugin = mock(ReColorfulMap.class);
        when(plugin.getMapStorage()).thenReturn(storage);
        when(plugin.getLogger()).thenReturn(logger);
        listener = new MapDestructionEvent(plugin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEATH", "DESPAWN", "OUT_OF_WORLD"})
    void trueDestructionQueuesDeletionWithoutDoingFileIoInTheEvent(String cause) throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        Item item = imageItem(MapStorage.ImageReference.thumbnail(artwork));
        try (MockedStatic<Scheduler> scheduler = queue(tasks)) {
            assertTrue(MapDestructionEvent.isDestructionCause(cause));
            listener.removed(item, cause);
            assertEquals(1, tasks.size());
            verify(storage, never()).deleteArtwork(any());
            tasks.get(0).run();
            verify(storage).deleteArtwork(artwork);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PICKUP", "MERGE", "UNLOAD", "PLUGIN", "DISCARD", "TRANSFORMATION",
            "DROP", "PLAYER_QUIT", "ENTER_BLOCK", "HIT", "EXPLODE", "UNKNOWN"})
    void transfersUnloadsAndUncertainRemovalCausesNeverDelete(String cause) {
        Item item = mock(Item.class);
        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            assertFalse(MapDestructionEvent.isDestructionCause(cause));
            listener.removed(item, cause);
            verifyNoInteractions(item, storage);
            scheduler.verifyNoInteractions();
        }
    }

    @Test
    void regularItemsOrdinaryMapsAndFramesNeverDeleteFiles() {
        Item ordinaryItem = mock(Item.class);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.STONE);
        when(ordinaryItem.getItemStack()).thenReturn(stack);
        Item ordinaryMap = imageItem(null);
        ItemFrame frame = mock(ItemFrame.class);
        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            listener.removed(ordinaryItem, "DEATH");
            listener.removed(ordinaryMap, "DESPAWN");
            listener.removed(frame, "DEATH");
            verifyNoInteractions(storage, frame);
            scheduler.verifyNoInteractions();
        }
    }

    @Test
    void thumbnailAndLooseTilesOfOneArtworkShareOnePendingDeletion() throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        Item thumbnail = imageItem(MapStorage.ImageReference.thumbnail(artwork));
        Item tile = imageItem(MapStorage.ImageReference.tile(artwork, 2, 1));
        try (MockedStatic<Scheduler> scheduler = queue(tasks)) {
            listener.removed(thumbnail, "DESPAWN");
            listener.removed(tile, "DEATH");
            assertEquals(1, tasks.size());
            tasks.get(0).run();
            verify(storage).deleteArtwork(artwork);
            listener.removed(tile, "OUT_OF_WORLD");
            assertEquals(2, tasks.size());
        }
    }

    @Test
    void fileFailureIsLoggedAndReleasesPendingDeletion() throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        Item item = imageItem(MapStorage.ImageReference.thumbnail(artwork));
        IOException failure = new IOException("read-only filesystem");
        doThrow(failure).when(storage).deleteArtwork(artwork);
        try (MockedStatic<Scheduler> scheduler = queue(tasks)) {
            listener.removed(item, "DEATH");
            tasks.get(0).run();
            verify(logger).log(eq(Level.WARNING), contains(artwork.toString()), eq(failure));
            listener.removed(item, "DESPAWN");
            assertEquals(2, tasks.size());
        }
    }

    @Test
    void schedulerFailureAlsoReleasesPendingDeletion() {
        Item item = imageItem(MapStorage.ImageReference.thumbnail(artwork));
        IllegalStateException failure = new IllegalStateException("plugin disabled");
        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            scheduler.when(() -> Scheduler.runTaskAsync(any())).thenThrow(failure);
            listener.removed(item, "DEATH");
            listener.removed(item, "DESPAWN");
            scheduler.verify(() -> Scheduler.runTaskAsync(any()), times(2));
            verify(logger, times(2)).log(eq(Level.WARNING), contains(artwork.toString()), eq(failure));
            verifyNoInteractions(storage);
        }
    }

    @Test
    void oldApiWithoutCauseEventKeepsDataAndReportsTheLimitation() {
        listener.register();
        verify(logger).warning(contains("Automatic artwork deletion is unavailable"));
        verifyNoInteractions(storage);
    }

    @Test
    void reflectiveRegistrationDispatchesTheFinalRemovalCause() throws Exception {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        ArgumentCaptor<EventExecutor> executor = ArgumentCaptor.forClass(EventExecutor.class);
        listener.register(RemovalEvent.class);
        verify(manager).registerEvent(eq(RemovalEvent.class), eq(listener), eq(EventPriority.MONITOR),
                executor.capture(), eq(plugin), eq(true));
        Item item = imageItem(MapStorage.ImageReference.tile(artwork, 0, 0));
        List<Runnable> tasks = new ArrayList<>();
        try (MockedStatic<Scheduler> scheduler = queue(tasks)) {
            executor.getValue().execute(listener, new RemovalEvent(item, RemovalCause.PICKUP));
            assertTrue(tasks.isEmpty());
            executor.getValue().execute(listener, new RemovalEvent(item, RemovalCause.DEATH));
            assertEquals(1, tasks.size());
            tasks.get(0).run();
            verify(storage).deleteArtwork(artwork);
        }
    }

    public enum RemovalCause { DEATH, PICKUP }

    /** Exercises reflection against the newer event shape while compiling against the old API. */
    public static final class RemovalEvent extends EntityEvent {
        private static final HandlerList HANDLERS = new HandlerList();
        private final RemovalCause cause;

        public RemovalEvent(Item item, RemovalCause cause) {
            super(item);
            this.cause = cause;
        }

        public RemovalCause getCause() {
            return cause;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    private static MockedStatic<Scheduler> queue(List<Runnable> tasks) {
        MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class);
        scheduler.when(() -> Scheduler.runTaskAsync(any())).thenAnswer(call -> {
            tasks.add(call.getArgument(0));
            return null;
        });
        return scheduler;
    }

    private static Item imageItem(MapStorage.ImageReference reference) {
        Item item = mock(Item.class);
        ItemStack stack = mock(ItemStack.class);
        MapMeta meta = mock(MapMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.getItemStack()).thenReturn(stack);
        when(stack.getType()).thenReturn(Material.FILLED_MAP);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        if (reference != null) {
            when(pdc.has(MapItemData.IMAGE_PATH, PersistentDataType.STRING)).thenReturn(true);
            when(pdc.get(MapItemData.IMAGE_PATH, PersistentDataType.STRING)).thenReturn(reference.path());
        }
        return item;
    }
}
