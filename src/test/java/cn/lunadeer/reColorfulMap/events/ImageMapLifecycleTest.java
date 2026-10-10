package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.Configuration;
import cn.lunadeer.reColorfulMap.LegacyMapFixture;
import cn.lunadeer.reColorfulMap.MapItemData;
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
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.PluginManager;
import org.bukkit.persistence.PersistentDataType;
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
    void setupImageWorld() throws Exception {
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
        // Exercise Bukkit's real ItemStack copy constructor and independent PDC metadata clones.
        factory = mock(ItemFactory.class);
        bukkit.when(Bukkit::getItemFactory).thenReturn(factory);
        when(factory.isApplicable(any(ItemMeta.class), any(Material.class))).thenReturn(true);
        when(factory.asMetaFor(any(ItemMeta.class), any(Material.class))).thenAnswer(call -> call.getArgument(0));
        when(factory.updateMaterial(any(ItemMeta.class), any(Material.class))).thenAnswer(call -> call.getArgument(1));
        when(factory.equals(any(), any())).thenAnswer(call -> LegacyMapFixture.sameMeta(call.getArgument(0), call.getArgument(1)));
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
        held = imageMap(123);
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
    void missingTileClaimsTheInteractionAndPreservesTheHeldMap() throws Exception {
        Files.delete(directory.resolve("maps/" + LegacyMapFixture.ARTWORK + "/1_1.png"));
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertTrue(event.isCancelled(), "Vanilla must not consume an artwork whose files need repair");
        notifications.verify(() -> Notification.error(eq(player), contains("Incomplete tile matrix")));
        assertUnchanged();
    }

    @Test
    void corruptPngFailsBeforeAnyPaymentOrFrameChange() throws Exception {
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
        ItemStack ordinary = imageMap(5000);
        when(inventory.getItemInMainHand()).thenReturn(ordinary);
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertFalse(event.isCancelled());
        assertUnchanged();
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void malformedTaggedPathCannotBeConsumedAsAnOrdinaryMap() throws Exception {
        held.getItemMeta().getPersistentDataContainer().set(MapItemData.IMAGE_PATH,
                PersistentDataType.STRING, "maps/../../outside.png");
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertTrue(event.isCancelled());
        notifications.verify(() -> Notification.error(eq(player), contains("Missing or invalid image map path")));
        assertUnchanged();
    }

    @Test
    void placingCopiedThumbnailConsumesOnlyOneAndKeepsArtworkFiles() throws Exception {
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
            assertEquals(MapStorage.ImageReference.tile(LegacyMapFixture.ARTWORK, i % 3, i / 3),
                    MapItemData.requireReference(placed.getValue()));
        }
        assertArtworkFilesRemain();
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
        assertArtworkFilesRemain();
    }

    @Test
    void placementWaitsForBackgroundImageReadBeforeCreatingMapsAndChangingFrames() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        List<Runnable> playerTasks = new ArrayList<>();
        queueScheduler(workers, playerTasks);
        ImageMapEvent listener = new ImageMapEvent();

        listener.putImageMapsOnItemFrame(placement());

        assertEquals(1, workers.size());
        verify(storage, never()).read(any());
        assertUnchanged();
        workers.remove(0).run(); // Decode all files away from the player thread.
        assertEquals(1, playerTasks.size());
        bukkit.verify(() -> Bukkit.createMap(any(World.class)), never());
        assertUnchanged();
        playerTasks.remove(0).run(); // Create tagged maps and place them on the owning thread.

        assertTrue(workers.isEmpty(), "PDC references need no separate index commit");
        verify(economy).withdrawPlayer(player, 600d);
        for (ItemFrame frame : frames) {
            ArgumentCaptor<ItemStack> placed = ArgumentCaptor.forClass(ItemStack.class);
            verify(frame).setItem(placed.capture(), eq(false));
            assertTrue(MapItemData.reference(placed.getValue()).isPresent());
        }
        assertTrue(errors.isEmpty(), errors.toString());
        assertArtworkFilesRemain();
    }

    @Test
    void switchingHeldItemWhileImagesAreReadCannotConsumeTheReplacement() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        List<Runnable> playerTasks = new ArrayList<>();
        queueScheduler(workers, playerTasks);
        ImageMapEvent listener = new ImageMapEvent();
        listener.putImageMapsOnItemFrame(placement());
        workers.remove(0).run();
        when(inventory.getItemInMainHand()).thenReturn(new ItemStack(Material.DIAMOND));

        playerTasks.remove(0).run();

        bukkit.verify(() -> Bukkit.createMap(any(World.class)), never());
        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
        verify(inventory, never()).setItemInMainHand(any(ItemStack.class));
        for (ItemFrame frame : frames) {
            verify(frame, never()).setItem(any(ItemStack.class), anyBoolean());
        }
        notifications.verify(() -> Notification.error(eq(player), contains("held map or item frames changed")));
        assertArtworkFilesRemain();
    }

    @Test
    void artworkDeletedAfterBackgroundReadCannotConsumeItemChargePlayerOrCreateMaps() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        List<Runnable> playerTasks = new ArrayList<>();
        queueScheduler(workers, playerTasks);
        PlayerInteractEntityEvent event = placement();
        new ImageMapEvent().putImageMapsOnItemFrame(event);
        workers.remove(0).run();
        assertEquals(1, playerTasks.size());
        assertTrue(storage.deleteArtwork(LegacyMapFixture.ARTWORK));

        playerTasks.remove(0).run();

        assertTrue(event.isCancelled());
        bukkit.verify(() -> Bukkit.createMap(any(World.class)), never());
        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
        assertNoFrameChangesOrItemConsumption();
        notifications.verify(() -> Notification.error(eq(player), contains("Image files are missing or corrupted")));
    }

    @Test
    void artworkDeletedDuringFinalProtectionChecksCannotChargePlayerOrPlaceTiles() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        doAnswer(call -> {
            // Six preflight probes run before decoding; the next probe is immediately before payment.
            if (probes.incrementAndGet() == 7) {
                storage.deleteArtwork(LegacyMapFixture.ARTWORK);
            }
            return null;
        }).when(pluginManager).callEvent(any(Event.class));
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertTrue(event.isCancelled());
        assertTrue(storage.isDeleted(LegacyMapFixture.ARTWORK));
        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
        assertNoFrameChangesOrItemConsumption();
        notifications.verify(() -> Notification.error(eq(player), contains("Image files are missing or corrupted")));
    }

    @Test
    void artworkDeletedDuringPaymentIsRefundedWithoutConsumingItemOrChangingFrames() throws Exception {
        doAnswer(call -> {
            storage.deleteArtwork(LegacyMapFixture.ARTWORK);
            return null;
        }).when(economy).withdrawPlayer(player, 600d);
        PlayerInteractEntityEvent event = placement();

        new ImageMapEvent().putImageMapsOnItemFrame(event);

        assertTrue(event.isCancelled());
        verify(economy).withdrawPlayer(player, 600d);
        verify(economy).depositPlayer(player, 600d);
        assertNoFrameChangesOrItemConsumption();
        notifications.verify(() -> Notification.error(eq(player), contains("Image files are missing or corrupted")));
    }

    @Test
    @SuppressWarnings("deprecation")
    void wallCanBePickedUpReplacedAndReopenedAfterRestart() throws Exception {
        for (int i = 0; i < frames.size(); i++) {
            AtomicReference<ItemStack> contents = new AtomicReference<>(imageMap(124 + i));
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
        assertEquals(MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK),
                MapItemData.requireReference(recovered.getValue()));
        assertEquals(0xffff0000, reopened.read(MapItemData.requireReference(recovered.getValue())).getRGB(0, 0));
        assertEquals(MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK), MapItemData.requireReference(held));
        for (int i = 0; i < frames.size(); i++) {
            MapStorage.ImageReference expected = MapStorage.ImageReference.tile(LegacyMapFixture.ARTWORK, i % 3, i / 3);
            assertEquals(expected, MapItemData.requireReference(frames.get(i).getItem()));
            assertEquals(0xffff0000, reopened.read(expected).getRGB(0, 0));
        }
    }

    @ParameterizedTest
    @MethodSource("cn.lunadeer.reColorfulMap.events.ImageMapGeometryTest#artworkWalls")
    void anyTileCanRecoverWholeArtworkAndPreserveFiles(BlockFace facing, int[][] positions) throws Exception {
        frames = wall(facing, positions);
        for (int i = 0; i < frames.size(); i++) {
            ItemStack tile = imageMap(124 + i);
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
        assertEquals(MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK),
                MapItemData.requireReference(recovered.getValue()));
        for (ItemFrame frame : frames) {
            ArgumentCaptor<ItemStack> cleared = ArgumentCaptor.forClass(ItemStack.class);
            verify(frame).setItem(cleared.capture(), eq(false));
            assertTrue(cleared.getValue().getType().isAir());
        }
        assertArtworkFilesRemain();
        verify(world, never()).getPersistentDataContainer();
    }

    @ParameterizedTest
    @MethodSource("cn.lunadeer.reColorfulMap.events.ImageMapGeometryTest#artworkWalls")
    void clickingTileCannotClearAnotherArtworkInTheSameWall(BlockFace facing, int[][] positions) throws Exception {
        frames = wall(facing, positions);
        for (int i = 0; i < frames.size(); i++) {
            ItemStack tile = imageMap(124 + i);
            when(frames.get(i).getItem()).thenReturn(tile);
        }
        // A different artwork occupies the exact expected coordinates of 1_0.png.
        ItemStack otherArtwork = imageMap(999);
        when(frames.get(1).getItem()).thenReturn(otherArtwork);
        HangingBreakEvent event = new HangingBreakEvent(frames.get(5), HangingBreakEvent.RemoveCause.PHYSICS);

        new ImageMapEvent().itemFrameBroken(event);

        assertFalse(event.isCancelled(), "A loose image tile must remain removable by vanilla");
        logger.verifyNoInteractions();
        assertUnchanged();
        verify(world, never()).dropItemNaturally(any(Location.class), any(ItemStack.class));
    }

    @Test
    void unloadedNeighboringChunkCannotSplitAnOtherwiseCompleteWall() throws Exception {
        frames = wall(BlockFace.NORTH, new int[][]{
                {16, 65, 20}, {15, 65, 20}, {14, 65, 20},
                {16, 64, 20}, {15, 64, 20}, {14, 64, 20}});
        for (int i = 0; i < frames.size(); i++) {
            ItemStack tile = imageMap(124 + i);
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

    private void assertArtworkFilesRemain() {
        Path artwork = directory.resolve("maps/" + LegacyMapFixture.ARTWORK);
        assertTrue(Files.isRegularFile(artwork.resolve("thumb.png")));
        for (int i = 0; i < 6; i++) {
            assertTrue(Files.isRegularFile(artwork.resolve((i % 3) + "_" + (i / 3) + ".png")));
        }
        assertFalse(Files.exists(directory.resolve("maps/maps.yml")));
        assertFalse(Files.exists(directory.resolve("maps/maps.yaml")));
    }

    private MapMeta emptyMapMeta() {
        return LegacyMapFixture.mapMeta(null);
    }

    private void assertUnchanged() throws Exception {
        verify(inventory, never()).setItemInMainHand(any(ItemStack.class));
        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
        verify(storage, never()).deleteArtwork(any());
        assertFalse(Files.exists(directory.resolve("maps/maps.yml")));
        verify(world, never()).getPersistentDataContainer();
        for (ItemFrame frame : frames) {
            verify(frame, never()).setItem(any(ItemStack.class));
            verify(frame, never()).setItem(any(ItemStack.class), anyBoolean());
            verify(frame, never()).getPersistentDataContainer();
        }
    }

    private void assertNoFrameChangesOrItemConsumption() {
        verify(inventory, never()).setItemInMainHand(any(ItemStack.class));
        for (ItemFrame frame : frames) {
            verify(frame, never()).setItem(any(ItemStack.class));
            verify(frame, never()).setItem(any(ItemStack.class), anyBoolean());
        }
    }

    private ItemStack imageMap(int id) {
        MapView view = mock(MapView.class);
        when(view.getWorld()).thenReturn(world);
        when(view.getId()).thenReturn(id);
        MapMeta meta = LegacyMapFixture.mapMeta(view);
        if (id == 123) {
            MapItemData.setReference(meta, MapStorage.ImageReference.thumbnail(LegacyMapFixture.ARTWORK));
        } else if (id >= 124 && id < 130) {
            MapItemData.setReference(meta, MapStorage.ImageReference.tile(LegacyMapFixture.ARTWORK, (id - 124) % 3, (id - 124) / 3));
        } else if (id == 999) {
            MapItemData.setReference(meta, MapStorage.ImageReference.tile(LegacyMapFixture.OTHER_ARTWORK, 1, 0));
        }
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
