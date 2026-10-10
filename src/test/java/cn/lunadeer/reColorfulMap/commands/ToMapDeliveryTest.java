package cn.lunadeer.reColorfulMap.commands;

import cn.lunadeer.reColorfulMap.ImageMapGenerator;
import cn.lunadeer.reColorfulMap.ImageMapItem;
import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the command's completion notifications as well as the inventory/drop boundary. */
class ToMapDeliveryTest {
    private static final String URL = "https://example.test/artwork.png";

    @TempDir
    Path directory;

    private final Player player = mock(Player.class);
    private final World world = mock(World.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final Item dropped = mock(Item.class);
    private final ItemStack leftover = mock(ItemStack.class);
    private final Command command = mock(Command.class);
    private final HashMap<Integer, ItemStack> leftovers = new HashMap<>();
    private final ToMap executor = new ToMap();
    private MapStorage storage;
    private MapStorage.Artwork artwork;
    private MockedStatic<ReColorfulMap> pluginStatic;
    private MockedStatic<ImageMapGenerator> generator;
    private MockedStatic<Scheduler> scheduler;
    private MockedStatic<Notification> notifications;
    private MockedStatic<XLogger> logger;
    private MockedConstruction<ImageMapItem> items;

    @BeforeEach
    void setUp() throws Exception {
        storage = new MapStorage(directory);
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        artwork = storage.create(image, image, image);
        ReColorfulMap plugin = mock(ReColorfulMap.class);
        when(plugin.getMapStorage()).thenReturn(storage);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.getInventory()).thenReturn(inventory);
        Location location = new Location(world, 1, 2, 3);
        when(player.getLocation()).thenReturn(location);
        when(inventory.addItem(any(ImageMapItem.class))).thenReturn(leftovers);
        when(world.dropItemNaturally(location, leftover)).thenReturn(dropped);

        pluginStatic = mockStatic(ReColorfulMap.class);
        pluginStatic.when(ReColorfulMap::getInstance).thenReturn(plugin);
        generator = mockStatic(ImageMapGenerator.class);
        generator.when(() -> ImageMapGenerator.generate(eq(storage), eq(URL), eq(1.0f),
                anyInt(), anyInt(), anyList())).thenReturn(artwork);
        scheduler = mockStatic(Scheduler.class);
        scheduler.when(() -> Scheduler.runTaskAsync(any())).thenAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        });
        scheduler.when(() -> Scheduler.runTask(eq(player), any(), any())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        });
        notifications = mockStatic(Notification.class);
        logger = mockStatic(XLogger.class);
        items = mockConstruction(ImageMapItem.class);
    }

    @AfterEach
    void tearDown() {
        items.close();
        logger.close();
        notifications.close();
        scheduler.close();
        generator.close();
        pluginStatic.close();
    }

    @Test
    void availableInventoryDeliversTheMapAndReportsSuccessWithoutDropping() throws Exception {
        runCommand();

        verify(inventory).addItem(items.constructed().get(0));
        verify(world, never()).dropItemNaturally(any(), any());
        assertSuccess();
        assertArtworkKept();
    }

    @Test
    void fullInventoryReportsSuccessOnlyAfterAValidOverflowDrop() throws Exception {
        leftovers.put(0, leftover);
        when(dropped.isValid()).thenReturn(true);

        runCommand();

        verify(world).dropItemNaturally(player.getLocation(), leftover);
        verify(dropped).isValid();
        assertSuccess();
        assertArtworkKept();
    }

    @Test
    void cancelledOverflowDropReportsDeliveryFailureWithoutSuccessAndKeepsPngs() throws Exception {
        leftovers.put(0, leftover);
        when(dropped.isValid()).thenReturn(false);

        runCommand();

        assertFailure("Could not deliver the generated map: item drop was cancelled. "
                + "Image files were kept for artwork " + artwork.id());
        // Another plugin can move an item during ItemSpawnEvent, then cancel its ground spawn.
        assertArtworkKept();
        verify(dropped, never()).remove();
    }

    @Test
    void dropExceptionReportsFailureWithoutSuccessAndKeepsPngs() throws Exception {
        leftovers.put(0, leftover);
        when(world.dropItemNaturally(player.getLocation(), leftover))
                .thenThrow(new IllegalStateException("item spawn failed"));

        runCommand();

        assertFailure("item spawn failed");
        assertArtworkKept();
    }

    @Test
    void deliveryFailureReleasesThePlayerForAnotherGenerationAttempt() {
        leftovers.put(0, leftover);
        when(dropped.isValid()).thenReturn(false, true);

        runCommand();
        runCommand();

        assertEquals(2, items.constructed().size());
        verify(world, times(2)).dropItemNaturally(player.getLocation(), leftover);
        notifications.verify(() -> Notification.info(player, Language.toMapCommand.generatedMap, 1, 1));
        notifications.verify(() -> Notification.error(eq(player), eq(Language.toMapCommand.generateMapFailed),
                anyString()));
    }

    private void runCommand() {
        assertTrue(executor.onCommand(player, command, "tomap", new String[]{URL}));
    }

    private void assertSuccess() {
        notifications.verify(() -> Notification.info(player, Language.toMapCommand.generatedMap, 1, 1));
        notifications.verify(() -> Notification.error(eq(player), eq(Language.toMapCommand.generateMapFailed),
                anyString()), never());
    }

    private void assertFailure(String reason) {
        notifications.verify(() -> Notification.error(player, Language.toMapCommand.generateMapFailed, reason));
        notifications.verify(() -> Notification.info(player, Language.toMapCommand.generatedMap, 1, 1), never());
    }

    private void assertArtworkKept() throws Exception {
        Path artworkDirectory = directory.resolve("maps").resolve(artwork.id().toString());
        assertTrue(Files.isRegularFile(artworkDirectory.resolve("raw.png")));
        assertTrue(Files.isRegularFile(artworkDirectory.resolve("thumb.png")));
        assertTrue(Files.isRegularFile(artworkDirectory.resolve("0_0.png")));
        assertNotNull(storage.read(MapStorage.ImageReference.thumbnail(artwork.id())));
        assertNotNull(storage.read(MapStorage.ImageReference.tile(artwork.id(), 0, 0)));
        assertFalse(storage.isDeleted(artwork.id()));
    }
}
