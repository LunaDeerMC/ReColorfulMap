package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.Configuration;
import cn.lunadeer.reColorfulMap.LegacyMapFixture;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import cn.lunadeer.reColorfulMap.utils.VaultConnect.VaultConnect;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ImageMapLifecycleTest {
    @TempDir Path directory;
    private MapStorage storage;
    private World world;
    private Player player;
    private PlayerInventory inventory;
    private PluginManager pluginManager;
    private VaultConnect economy;
    private ItemStack held;
    private List<ItemFrame> frames;
    private MockedStatic<ReColorfulMap> pluginAccess;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<Notification> notifications;
    private MockedStatic<XLogger> logger;
    private MockedStatic<Scheduler> scheduler;
    private Configuration.Economy previousEconomyConfiguration;
    private VaultConnect previousEconomy;
    private ItemFactory factory;
    private final List<String> errors = new ArrayList<>();

    @BeforeEach
    void setupLegacyWorld() throws Exception {
        storage = spy(LegacyMapFixture.copyTo(directory));
        ReColorfulMap plugin = mock(ReColorfulMap.class);
        when(plugin.getMapStorage()).thenReturn(storage);
        pluginAccess = mockStatic(ReColorfulMap.class);
        pluginAccess.when(ReColorfulMap::getInstance).thenReturn(plugin);
        bukkit = mockStatic(Bukkit.class);
        notifications = mockStatic(Notification.class);
        notifications.when(() -> Notification.error(any(Player.class), anyString())).thenAnswer(call -> {
            errors.add(call.getArgument(1));
            return null;
        });
        logger = mockStatic(XLogger.class);
        scheduler = mockStatic(Scheduler.class);
        scheduler.when(() -> Scheduler.runTaskAsync(any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return null;
        });
        scheduler.when(() -> Scheduler.runTask(any(Player.class), any(Runnable.class), any(Runnable.class)))
                .thenAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                });
        pluginManager = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        // Let Bukkit's real ItemStack copy constructor copy the legacy MapMeta.
        factory = mock(ItemFactory.class);
        bukkit.when(Bukkit::getItemFactory).thenReturn(factory);
        when(factory.isApplicable(any(ItemMeta.class), any(Material.class))).thenReturn(true);
        when(factory.asMetaFor(any(ItemMeta.class), any(Material.class))).thenAnswer(call -> call.getArgument(0));
        when(factory.updateMaterial(any(ItemMeta.class), any(Material.class))).thenAnswer(call -> call.getArgument(1));
        when(factory.equals(any(), any())).thenAnswer(call -> call.getArgument(0) == call.getArgument(1));
        when(factory.getItemMeta(Material.FILLED_MAP)).thenAnswer(call -> emptyMapMeta());
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        AtomicInteger nextMapId = new AtomicInteger(300);
        bukkit.when(() -> Bukkit.createMap(world)).thenAnswer(call -> {
            MapView view = mock(MapView.class);
            when(view.getWorld()).thenReturn(world);
            when(view.getId()).thenReturn(nextMapId.getAndIncrement());
            when(view.getRenderers()).thenReturn(new ArrayList<>());
            return view;
        });
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.fromString("031d25c2-1320-4310-9905-f5fdf91fc2e7"));
        when(player.getWorld()).thenReturn(world);
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        held = legacyMap(123);
        when(inventory.getItemInMainHand()).thenReturn(held);
        frames = wall(BlockFace.NORTH, new int[][]{
                {10, 65, 20}, {9, 65, 20}, {8, 65, 20},
                {10, 64, 20}, {9, 64, 20}, {8, 64, 20}});
        previousEconomyConfiguration = Configuration.economy;
        Configuration.economy = new Configuration.Economy();
        Configuration.economy.enable = true;
        previousEconomy = VaultConnect.instance;
        economy = mock(VaultConnect.class);
        VaultConnect.instance = economy;
        when(economy.economyAvailable()).thenReturn(true);
        when(economy.getBalance(player)).thenReturn(10000d);
    }

    @AfterEach
    void resetGlobalServices() {
        Configuration.economy = previousEconomyConfiguration;
        VaultConnect.instance = previousEconomy;
        scheduler.close();
        logger.close();
        notifications.close();
        bukkit.close();
        pluginAccess.close();
    }

    @Test
    void occupiedFrameDoesNotConsumeMapChargePlayerOrReplaceItsItem() throws Exception {
        when(frames.get(5).getItem()).thenReturn(new ItemStack(Material.DIAMOND));
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertTrue(event.isCancelled());
        notifications.verify(() -> Notification.error(eq(player), contains("All item frames must be empty")));
        assertUnchanged();
        verify(pluginManager, never()).callEvent(any(Event.class));
    }

    @Test
    void protectionCancellationChecksEveryAffectedFrameBeforeChargingOrPlacing() throws Exception {
        doAnswer(call -> {
            PlayerInteractEntityEvent probe = call.getArgument(0);
            if (probe.getRightClicked() == frames.get(1)) {
                probe.setCancelled(true);
            }
            return null;
        }).when(pluginManager).callEvent(any(Event.class));
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertTrue(event.isCancelled());
        notifications.verify(() -> Notification.error(eq(player), contains("cannot modify")));
        assertUnchanged();
        verify(pluginManager, times(3)).callEvent(any(Event.class));
        verify(economy, never()).getBalance(any(Player.class));
    }

    @Test
    void missingLegacyTileClaimsTheInteractionAndPreservesTheHeldMap() throws Exception {
        Files.delete(directory.resolve("maps/" + LegacyMapFixture.ARTWORK + "/1_1.png"));
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertTrue(event.isCancelled(), "Vanilla must not consume an artwork whose files need repair");
        notifications.verify(() -> Notification.error(eq(player), contains("Incomplete tile matrix")));
        assertUnchanged();
    }

    @Test
    void corruptLegacyPngFailsBeforeAnyPaymentOrFrameChange() throws Exception {
        Files.writeString(directory.resolve("maps/" + LegacyMapFixture.ARTWORK + "/1_1.png"), "damaged PNG");
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertTrue(event.isCancelled());
        verify(storage).read(MapStorage.ImageReference.tile(LegacyMapFixture.ARTWORK, 1, 1));
        verify(economy).getBalance(player);
        notifications.verify(() -> Notification.error(eq(player), contains("Invalid PNG image")));
        assertUnchanged();
    }

    @Test
    void ordinaryMapIsNotIntercepted() throws Exception {
        ItemStack ordinary = legacyMap(5000);
        when(inventory.getItemInMainHand()).thenReturn(ordinary);
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertFalse(event.isCancelled());
        assertUnchanged();
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void placingCopiedLegacyThumbnailConsumesOnlyOneAndKeepsEveryLegacyAlias() throws Exception {
        ItemStack stacked = new ItemStack(Material.FILLED_MAP, 2);
        stacked.setItemMeta(held.getItemMeta());
        when(inventory.getItemInMainHand()).thenReturn(stacked);
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertTrue(event.isCancelled());
        assertTrue(errors.isEmpty(), errors.toString());
        verify(economy).withdrawPlayer(player, 600d);
        ArgumentCaptor<ItemStack> remaining = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory).setItemInMainHand(remaining.capture());
        assertEquals(1, remaining.getValue().getAmount());
        assertEquals(123, ((MapMeta) remaining.getValue().getItemMeta()).getMapView().getId());
        for (int i = 0; i < frames.size(); i++) {
            ArgumentCaptor<ItemStack> placed = ArgumentCaptor.forClass(ItemStack.class);
            verify(frames.get(i)).setItem(placed.capture(), eq(false));
            MapMeta meta = (MapMeta) placed.getValue().getItemMeta();
            assertEquals(MapStorage.ImageReference.tile(LegacyMapFixture.ARTWORK, i % 3, i / 3),
                    storage.find("world", meta.getMapView().getId()).orElseThrow());
            verify(meta, never()).getPersistentDataContainer();
        }
        assertLegacyAliasesRemain();
        verify(world, never()).getPersistentDataContainer();
    }

    @Test
    void frameFailureAfterPaymentRestoresFramesAndRefundsPlayer() {
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(call -> {
            if (!failed.getAndSet(true)) {
                throw new IllegalStateException("Frame changed during placement");
            }
            return null;
        }).when(frames.get(2)).setItem(any(ItemStack.class), eq(false));
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertTrue(event.isCancelled());
        verify(economy).withdrawPlayer(player, 600d);
        verify(economy).depositPlayer(player, 600d);
        verify(inventory, never()).setItemInMainHand(any(ItemStack.class));
        for (ItemFrame frame : frames) {
            ArgumentCaptor<ItemStack> changes = ArgumentCaptor.forClass(ItemStack.class);
            verify(frame, atLeastOnce()).setItem(changes.capture(), eq(false));
            List<ItemStack> items = changes.getAllValues();
            assertTrue(items.get(items.size() - 1).getType().isAir(), "Rollback must restore each frame's original item");
        }
        notifications.verify(() -> Notification.error(eq(player), contains("Frame changed during placement")));
        assertLegacyAliasesRemain();
    }

    @Test
    void placementWaitsForImageReadAndDurableIndexBeforeChangingFrames() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        List<Runnable> playerTasks = new ArrayList<>();
        queueScheduler(workers, playerTasks);
        ImageMapEvent listener = new ImageMapEvent();

        listener.putImageMapsOnItemFrame(placement());

        assertEquals(1, workers.size());
        verify(storage, never()).read(any());
        assertUnchanged();
        workers.remove(0).run(); // Decode files away from the player thread.
        assertEquals(1, playerTasks.size());
        bukkit.verify(() -> Bukkit.createMap(any(World.class)), never());
        playerTasks.remove(0).run(); // Create Bukkit maps on the player thread.
        assertEquals(1, workers.size());
        assertTrue(storage.find("world", 300).isEmpty());
        assertUnchanged();
        workers.remove(0).run(); // Commit the map-ID index before exposing any item.
        assertTrue(storage.find("world", 300).isPresent());
        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
        for (ItemFrame frame : frames) {
            verify(frame, never()).setItem(any(ItemStack.class), anyBoolean());
        }
        playerTasks.remove(0).run(); // The final world mutation happens on the owning thread.

        verify(economy).withdrawPlayer(player, 600d);
        for (ItemFrame frame : frames) {
            verify(frame).setItem(any(ItemStack.class), eq(false));
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void switchingHeldItemWhileTheIndexIsSavedCannotConsumeTheReplacement() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        List<Runnable> playerTasks = new ArrayList<>();
        queueScheduler(workers, playerTasks);
        ImageMapEvent listener = new ImageMapEvent();
        listener.putImageMapsOnItemFrame(placement());
        workers.remove(0).run();
        playerTasks.remove(0).run();
        workers.remove(0).run();
        when(inventory.getItemInMainHand()).thenReturn(new ItemStack(Material.DIAMOND));

        playerTasks.remove(0).run();

        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
        verify(inventory, never()).setItemInMainHand(any(ItemStack.class));
        for (ItemFrame frame : frames) {
            verify(frame, never()).setItem(any(ItemStack.class), anyBoolean());
        }
        notifications.verify(() -> Notification.error(eq(player), contains("held map or item frames changed")));
        assertLegacyAliasesRemain();
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyWallCanBePickedUpReplacedAndReopenedAfterRestart() throws Exception {
        for (int i = 0; i < frames.size(); i++) {
            AtomicReference<ItemStack> contents = new AtomicReference<>(legacyMap(124 + i));
            ItemFrame frame = frames.get(i);
            when(frame.getItem()).thenAnswer(call -> contents.get());
            doAnswer(call -> { contents.set(call.getArgument(0)); return null; })
                    .when(frame).setItem(any(ItemStack.class), eq(false));
        }
        Item dropped = mock(Item.class);
        when(dropped.isValid()).thenReturn(true);
        when(world.dropItemNaturally(any(Location.class), any(ItemStack.class))).thenReturn(dropped);
        EntityDamageByEntityEvent pickup = new EntityDamageByEntityEvent(player, frames.get(4),
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1d);
        ImageMapEvent listener = new ImageMapEvent();

        listener.breakImageMapsFromItemFrame(pickup);

        assertTrue(pickup.isCancelled());
        ArgumentCaptor<ItemStack> recovered = ArgumentCaptor.forClass(ItemStack.class);
        verify(world).dropItemNaturally(any(Location.class), recovered.capture());
        for (ItemFrame frame : frames) {
            assertTrue(frame.getItem().getType().isAir());
        }
        when(inventory.getItemInMainHand()).thenReturn(recovered.getValue());

        listener.putImageMapsOnItemFrame(placement());

        assertTrue(errors.isEmpty(), errors.toString());
        MapStorage reopened = new MapStorage(directory);
        MapMeta recoveredMeta = (MapMeta) recovered.getValue().getItemMeta();
        assertEquals(MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK),
                reopened.find("world", recoveredMeta.getMapView().getId()).orElseThrow());
        assertEquals(MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK),
                reopened.find("world", 123).orElseThrow());
        for (int i = 0; i < frames.size(); i++) {
            MapMeta replacement = (MapMeta) frames.get(i).getItem().getItemMeta();
            MapStorage.ImageReference expected = MapStorage.ImageReference.tile(LegacyMapFixture.ARTWORK, i % 3, i / 3);
            assertEquals(expected, reopened.find("world", replacement.getMapView().getId()).orElseThrow());
            assertEquals(expected, reopened.find("world", 124 + i).orElseThrow());
            assertEquals(0xffff0000, reopened.read(expected).getRGB(0, 0));
        }
    }

    @ParameterizedTest
    @MethodSource("cn.lunadeer.reColorfulMap.events.ImageMapGeometryTest#legacyWalls")
    void anyLegacyTileCanRecoverTheWholeArtworkAndKeepOriginalMapIds(BlockFace facing, int[][] positions) throws Exception {
        frames = wall(facing, positions);
        for (int i = 0; i < frames.size(); i++) {
            ItemStack tile = legacyMap(124 + i);
            when(frames.get(i).getItem()).thenReturn(tile);
        }
        Item dropped = mock(Item.class);
        when(dropped.isValid()).thenReturn(true);
        when(world.dropItemNaturally(any(Location.class), any(ItemStack.class))).thenReturn(dropped);
        // Click the upper-right tile instead of the old implementation's special origin frame.
        HangingBreakEvent event = new HangingBreakEvent(frames.get(2), HangingBreakEvent.RemoveCause.PHYSICS);

        new ImageMapEvent().itemFrameBroken(event);

        assertFalse(event.isCancelled(), "After recovering the map, vanilla may break the empty frame");
        logger.verifyNoInteractions();
        ArgumentCaptor<ItemStack> recovered = ArgumentCaptor.forClass(ItemStack.class);
        verify(world).dropItemNaturally(eq(frames.get(2).getLocation()), recovered.capture());
        MapMeta meta = (MapMeta) recovered.getValue().getItemMeta();
        assertEquals(MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK),
                storage.find("world", meta.getMapView().getId()).orElseThrow());
        for (ItemFrame frame : frames) {
            ArgumentCaptor<ItemStack> cleared = ArgumentCaptor.forClass(ItemStack.class);
            verify(frame).setItem(cleared.capture(), eq(false));
            assertTrue(cleared.getValue().getType().isAir());
        }
        assertLegacyAliasesRemain();
        verify(meta, never()).getPersistentDataContainer();
        verify(world, never()).getPersistentDataContainer();
    }

    @ParameterizedTest
    @MethodSource("cn.lunadeer.reColorfulMap.events.ImageMapGeometryTest#legacyWalls")
    void clickingLegacyTileCannotClearAnotherArtworkInTheSameWall(BlockFace facing, int[][] positions) throws Exception {
        frames = wall(facing, positions);
        for (int i = 0; i < frames.size(); i++) {
            ItemStack tile = legacyMap(124 + i);
            when(frames.get(i).getItem()).thenReturn(tile);
        }
        // A different artwork occupies the exact expected coordinates of 1_0.png.
        ItemStack otherArtwork = legacyMap(999);
        when(frames.get(1).getItem()).thenReturn(otherArtwork);
        HangingBreakEvent event = new HangingBreakEvent(frames.get(5), HangingBreakEvent.RemoveCause.PHYSICS);

        new ImageMapEvent().itemFrameBroken(event);

        assertFalse(event.isCancelled(), "A loose legacy tile must remain removable by vanilla");
        logger.verifyNoInteractions();
        assertUnchanged();
        verify(world, never()).dropItemNaturally(any(Location.class), any(ItemStack.class));
    }

    @Test
    void unloadedNeighboringChunkCannotSplitAnOtherwiseCompleteLegacyWall() throws Exception {
        frames = wall(BlockFace.NORTH, new int[][]{
                {16, 65, 20}, {15, 65, 20}, {14, 65, 20},
                {16, 64, 20}, {15, 64, 20}, {14, 64, 20}});
        for (int i = 0; i < frames.size(); i++) {
            ItemStack tile = legacyMap(124 + i);
            when(frames.get(i).getItem()).thenReturn(tile);
        }
        // The clicked tile is loaded in chunk 0,1; the artwork's origin lies in unloaded chunk 1,1.
        when(world.isChunkLoaded(1, 1)).thenReturn(false);
        HangingBreakEvent event = new HangingBreakEvent(frames.get(2), HangingBreakEvent.RemoveCause.PHYSICS);

        new ImageMapEvent().itemFrameBroken(event);

        assertTrue(event.isCancelled(), "An unavailable neighbor must not be mistaken for a missing artwork tile");
        assertUnchanged();
        logger.verify(() -> XLogger.warn(contains("unloaded chunks")));
        verify(world, never()).dropItemNaturally(any(Location.class), any(ItemStack.class));
        verify(world, never()).getChunkAt(anyInt(), anyInt());
        verify(world, never()).loadChunk(anyInt(), anyInt());
    }

    private PlayerInteractEntityEvent placement() {
        return new PlayerInteractEntityEvent(player, frames.get(3), EquipmentSlot.HAND);
    }

    private void queueScheduler(List<Runnable> workers, List<Runnable> playerTasks) {
        scheduler.when(() -> Scheduler.runTaskAsync(any(Runnable.class))).thenAnswer(call -> {
            workers.add(call.getArgument(0));
            return null;
        });
        scheduler.when(() -> Scheduler.runTask(any(Player.class), any(Runnable.class), any(Runnable.class)))
                .thenAnswer(call -> {
                    playerTasks.add(call.getArgument(1));
                    return null;
                });
    }

    private void assertLegacyAliasesRemain() {
        assertEquals(MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK),
                storage.find("world", 123).orElseThrow());
        for (int i = 0; i < 6; i++) {
            assertEquals(MapStorage.ImageReference.tile(LegacyMapFixture.ARTWORK, i % 3, i / 3),
                    storage.find("world", 124 + i).orElseThrow());
        }
    }

    private MapMeta emptyMapMeta() {
        MapMeta meta = mock(MapMeta.class, withSettings().extraInterfaces(Damageable.class));
        AtomicReference<MapView> view = new AtomicReference<>();
        when(meta.getMapView()).thenAnswer(call -> view.get());
        doAnswer(call -> { view.set(call.getArgument(0)); return null; }).when(meta).setMapView(any(MapView.class));
        when(meta.clone()).thenReturn(meta);
        return meta;
    }

    private void assertUnchanged() throws Exception {
        verify(inventory, never()).setItemInMainHand(any(ItemStack.class));
        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
        verify(storage, never()).register(any());
        verify(world, never()).getPersistentDataContainer();
        for (ItemFrame frame : frames) {
            verify(frame, never()).setItem(any(ItemStack.class));
            verify(frame, never()).setItem(any(ItemStack.class), anyBoolean());
            verify(frame, never()).getPersistentDataContainer();
        }
    }

    private ItemStack legacyMap(int id) {
        MapView view = mock(MapView.class);
        when(view.getWorld()).thenReturn(world);
        when(view.getId()).thenReturn(id);
        MapMeta meta = mock(MapMeta.class, withSettings().extraInterfaces(Damageable.class));
        when(meta.getMapView()).thenReturn(view);
        when(meta.clone()).thenReturn(meta);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.FILLED_MAP);
        when(item.getItemMeta()).thenReturn(meta);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getAmount()).thenReturn(1);
        when(item.clone()).thenReturn(item);
        return item;
    }

    private List<ItemFrame> wall(BlockFace facing, int[][] positions) {
        List<ItemFrame> result = new ArrayList<>();
        for (int[] position : positions) {
            ItemFrame frame = mock(ItemFrame.class);
            when(frame.getWorld()).thenReturn(world);
            when(frame.getFacing()).thenReturn(facing);
            when(frame.isValid()).thenReturn(true);
            when(frame.getItem()).thenReturn(new ItemStack(Material.AIR));
            when(frame.getLocation()).thenAnswer(call -> new Location(world, position[0], position[1], position[2]));
            result.add(frame);
        }
        when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new ArrayList<Entity>(result));
        return result;
    }
}
