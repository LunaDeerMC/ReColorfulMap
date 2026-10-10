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
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MapBindingEventTest {
    private static final ImageReference IMAGE = ImageReference.tile(
            UUID.fromString("bc1ea1d8-3a9f-4386-a0fa-1d6096d0d817"), 1, 2);

    private final ReColorfulMap plugin = mock(ReColorfulMap.class);
    private final MapStorage storage = mock(MapStorage.class);
    private final Server server = mock(Server.class);
    private final MapBindingEvent listener = new MapBindingEvent(plugin);

    @BeforeEach
    void setUp() {
        when(plugin.getMapStorage()).thenReturn(storage);
        when(plugin.getServer()).thenReturn(server);
        when(server.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 123, 100000})
    void bindsFromItemPathWithoutMapIdWorldIndexOrImmediatePngReads(int mapId) {
        MapView view = view();
        when(view.getId()).thenReturn(mapId);
        MapRenderer vanilla = mock(MapRenderer.class);
        view.addRenderer(vanilla);

        listener.bind(item(view));

        assertEquals(1, view.getRenderers().size());
        ImageRenderer renderer = assertInstanceOf(ImageRenderer.class, view.getRenderers().get(0));
        assertTrue(renderer.matches(storage, IMAGE));
        verify(view).removeRenderer(vanilla);
        verify(view, never()).getWorld();
        verify(view, never()).getId();
        verifyNoInteractions(storage);
    }

    @Test
    void repeatedBindingKeepsTheExistingRenderer() {
        MapView view = view();
        ItemStack item = item(view);

        listener.bind(item);
        MapRenderer first = view.getRenderers().get(0);
        listener.bind(item);

        assertEquals(List.of(first), view.getRenderers());
        verify(view, times(1)).addRenderer(any());
        verify(view, never()).removeRenderer(any());
    }

    @Test
    void changedPathReplacesAnOldBinding() {
        MapView view = view();
        ImageRenderer old = new ImageRenderer(storage, ImageReference.thumbnail(IMAGE.artwork()));
        view.addRenderer(old);

        listener.bind(item(view));

        verify(view).removeRenderer(old);
        assertEquals(1, view.getRenderers().size());
        assertTrue(((ImageRenderer) view.getRenderers().get(0)).matches(storage, IMAGE));
    }

    @Test
    void ordinaryMapsAndOtherItemsAreUntouched() {
        MapView view = view();
        ItemStack ordinary = item(view, null);
        ItemStack other = mock(ItemStack.class);
        when(other.getType()).thenReturn(Material.PAPER);

        listener.bind(ordinary);
        listener.bind(other);
        listener.bind(null);

        verifyNoInteractions(view, storage);
        verify(other, never()).getItemMeta();
    }

    @Test
    void malformedPathsNeverReachStorageOrReplaceVanillaRendering() {
        MapView view = view();
        try (MockedStatic<XLogger> logger = mockStatic(XLogger.class)) {
            listener.bind(item(view, "maps/../../outside.png"));

            logger.verify(() -> XLogger.warn("Cannot restore image map with an invalid image_path PDC value."));
            verifyNoInteractions(view, storage);
        }
    }

    @Test
    void absentMapViewDoesNotReadTheImage() {
        try (MockedStatic<XLogger> logger = mockStatic(XLogger.class)) {
            listener.bind(item(null));

            logger.verify(() -> XLogger.warn("Cannot restore image map without a MapView: %s", IMAGE.path()));
            verifyNoInteractions(storage);
        }
    }

    @Test
    void joinRestoresInventoryEnderChestAndCursorMaps() {
        MapView inventoryMap = view();
        MapView enderMap = view();
        MapView cursorMap = view();
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenAnswer(call -> new ItemStack[]{item(inventoryMap), null});
        when(player.getEnderChest()).thenAnswer(call -> inventory(item(enderMap)));
        when(player.getItemOnCursor()).thenAnswer(call -> item(cursorMap));
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerJoin(event);

        for (MapView view : List.of(inventoryMap, enderMap, cursorMap)) {
            assertEquals(1, view.getRenderers().size());
        }
    }

    @Test
    void slotChangesBindTheNewStack() {
        MapView previous = view();
        MapView current = view();
        PlayerInventorySlotChangeEvent event = mock(PlayerInventorySlotChangeEvent.class);
        when(event.getOldItemStack()).thenAnswer(call -> item(previous));
        when(event.getNewItemStack()).thenAnswer(call -> item(current));

        listener.onInventorySlotChange(event);

        verifyNoInteractions(previous);
        assertEquals(1, current.getRenderers().size());
    }

    @Test
    void inventoryOpeningRestoresBothInventoriesAndCursor() {
        MapView top = view();
        MapView bottom = view();
        MapView cursor = view();
        InventoryView inventories = mock(InventoryView.class);
        when(inventories.getTopInventory()).thenAnswer(call -> inventory(item(top)));
        when(inventories.getBottomInventory()).thenAnswer(call -> inventory(item(bottom)));
        when(inventories.getCursor()).thenAnswer(call -> item(cursor));
        InventoryOpenEvent event = mock(InventoryOpenEvent.class);
        when(event.getView()).thenReturn(inventories);

        listener.onInventoryOpen(event);

        for (MapView view : List.of(top, bottom, cursor)) {
            assertEquals(1, view.getRenderers().size());
        }
    }

    @Test
    void entityLoadingRestoresFramesDroppedItemsDisplaysAndContainerEntities() {
        MapView frameView = view();
        MapView droppedView = view();
        MapView displayView = view();
        MapView cartView = view();
        ItemFrame frame = mock(ItemFrame.class);
        when(frame.getItem()).thenAnswer(call -> item(frameView));
        Item dropped = mock(Item.class);
        when(dropped.getItemStack()).thenAnswer(call -> item(droppedView));
        ItemDisplay display = mock(ItemDisplay.class);
        when(display.getItemStack()).thenAnswer(call -> item(displayView));
        StorageMinecart cart = mock(StorageMinecart.class);
        when(cart.getInventory()).thenAnswer(call -> inventory(item(cartView)));
        EntitiesLoadEvent event = mock(EntitiesLoadEvent.class);
        when(event.getEntities()).thenReturn(List.of(frame, dropped, display, cart, mock(Entity.class)));

        listener.onEntitiesLoad(event);

        for (MapView view : List.of(frameView, droppedView, displayView, cartView)) {
            assertEquals(1, view.getRenderers().size());
        }
        verifyNoInteractions(storage);
    }

    @Test
    void entityReadsWaitForTheOwningRegion() {
        MapView view = view();
        ItemFrame frame = mock(ItemFrame.class);
        when(frame.getItem()).thenAnswer(call -> item(view));
        when(server.isOwnedByCurrentRegion(frame)).thenReturn(false);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(frame.getScheduler()).thenReturn(scheduler);
        List<Consumer<ScheduledTask>> pending = new ArrayList<>();
        when(scheduler.run(eq(plugin), any(), isNull())).thenAnswer(call -> {
            pending.add(call.getArgument(1));
            return mock(ScheduledTask.class);
        });
        EntityAddToWorldEvent event = mock(EntityAddToWorldEvent.class);
        when(event.getEntity()).thenReturn(frame);

        listener.onEntityAdd(event);

        verify(frame, never()).getItem();
        assertEquals(1, pending.size());
        pending.get(0).accept(mock(ScheduledTask.class));
        assertEquals(1, view.getRenderers().size());
    }

    @Test
    void frameInteractionRestoresItsMap() {
        MapView view = view();
        PlayerItemFrameChangeEvent event = mock(PlayerItemFrameChangeEvent.class);
        when(event.getItemStack()).thenAnswer(call -> item(view));

        listener.onItemFrameChange(event);

        assertEquals(1, view.getRenderers().size());
    }

    @Test
    void bootstrapVisitsOnlyAlreadyLoadedEntityChunksOnTheirRegions() {
        World world = mock(World.class);
        Chunk loaded = mock(Chunk.class);
        Chunk unloaded = mock(Chunk.class);
        Chunk entitiesNotLoaded = mock(Chunk.class);
        when(loaded.getX()).thenReturn(1);
        when(unloaded.getX()).thenReturn(2);
        when(entitiesNotLoaded.getX()).thenReturn(3);
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{loaded, unloaded, entitiesNotLoaded});
        when(world.isChunkLoaded(1, 0)).thenReturn(true);
        when(world.isChunkLoaded(3, 0)).thenReturn(true);
        when(loaded.isEntitiesLoaded()).thenReturn(true);
        ItemFrame frame = mock(ItemFrame.class);
        MapView view = view();
        when(frame.getItem()).thenAnswer(call -> item(view));
        when(loaded.getEntities()).thenReturn(new Entity[]{frame});
        when(server.getWorlds()).thenReturn(List.of(world));
        RegionScheduler scheduler = mock(RegionScheduler.class);
        when(server.getRegionScheduler()).thenReturn(scheduler);
        List<Consumer<ScheduledTask>> pending = new ArrayList<>();
        when(scheduler.run(eq(plugin), eq(world), anyInt(), anyInt(), any()))
                .thenAnswer(call -> {
                    pending.add(call.getArgument(4));
                    return mock(ScheduledTask.class);
                });

        listener.bootstrap();

        verify(loaded, never()).getEntities();
        assertEquals(3, pending.size());
        pending.forEach(task -> task.accept(mock(ScheduledTask.class)));
        assertEquals(1, view.getRenderers().size());
        verify(unloaded, never()).getEntities();
        verify(entitiesNotLoaded, never()).getEntities();
        verify(world, never()).getChunkAt(anyInt(), anyInt());
        verify(loaded, never()).load();
    }

    @Test
    void bootstrapSchedulesOnlinePlayersBeforeReadingTheirInventories() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        MapView view = view();
        when(inventory.getContents()).thenAnswer(call -> new ItemStack[]{item(view)});
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenAnswer(call -> inventory());
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(player));
        when(server.isOwnedByCurrentRegion(player)).thenReturn(false);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        List<Consumer<ScheduledTask>> pending = new ArrayList<>();
        when(scheduler.run(eq(plugin), any(), isNull())).thenAnswer(call -> {
            pending.add(call.getArgument(1));
            return mock(ScheduledTask.class);
        });

        listener.bootstrap();

        verify(player, never()).getInventory();
        assertEquals(1, pending.size());
        pending.get(0).accept(mock(ScheduledTask.class));
        assertEquals(1, view.getRenderers().size());
    }

    private static Inventory inventory(ItemStack... contents) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContents()).thenReturn(contents);
        return inventory;
    }

    private static ItemStack item(MapView view) {
        return item(view, IMAGE.path());
    }

    private static ItemStack item(MapView view, String path) {
        ItemStack item = mock(ItemStack.class);
        MapMeta meta = mock(MapMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.FILLED_MAP);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getMapView()).thenReturn(view);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(MapItemData.IMAGE_PATH, PersistentDataType.STRING)).thenReturn(path != null);
        when(data.get(MapItemData.IMAGE_PATH, PersistentDataType.STRING)).thenReturn(path);
        return item;
    }

    private static MapView view() {
        MapView view = mock(MapView.class);
        List<MapRenderer> renderers = new ArrayList<>();
        // Match CraftMapView: getRenderers returns a copy, not the mutable underlying list.
        when(view.getRenderers()).thenAnswer(call -> new ArrayList<>(renderers));
        doAnswer(call -> {
            renderers.add(call.getArgument(0));
            return null;
        }).when(view).addRenderer(any());
        when(view.removeRenderer(any())).thenAnswer(call -> renderers.remove(call.getArgument(0)));
        return view;
    }
}
