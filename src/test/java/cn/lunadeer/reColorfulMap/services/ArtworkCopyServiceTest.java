package cn.lunadeer.reColorfulMap.services;

import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArtworkCopyServiceTest {
    @TempDir Path directory;
    private final Player player = mock(Player.class);
    private final Logger logger = mock(Logger.class);
    private final List<Runnable> workers = new ArrayList<>();
    private final TestPlatform platform = new TestPlatform();
    private MapStorage storage;
    private MapStorage.Artwork original;
    private ItemStack source;
    private ArtworkCopyService service;

    @BeforeEach void setUp() throws Exception {
        storage = new MapStorage(directory);
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.MAGENTA.getRGB());
        original = storage.create(image, image, image);
        source = item(MapStorage.ImageReference.thumbnail(original.id()));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        service = service(workers::add);
    }

    @Test void twoCopiesUseDifferentFilesAndSurviveOneCopyBeingDestroyed() throws Exception {
        Map<String, String> before = hashes(original.id());
        AtomicReference<List<ItemStack>> delivered = new AtomicReference<>();
        assertTrue(service.copy(player, source, 2, items -> { delivered.set(items); return true; }));
        assertEquals(List.of(original.id().toString()), directories());
        workers.remove(0).run();
        assertEquals(2, delivered.get().size());
        var first = MapItemData.requireReference(delivered.get().get(0));
        var second = MapItemData.requireReference(delivered.get().get(1));
        assertNotEquals(original.id(), first.artwork());
        assertNotEquals(first.artwork(), second.artwork());
        assertEquals(3, directories().size());
        for (String file : List.of("raw.png", "thumb.png", "0_0.png")) {
            Path old = art(original.id()).resolve(file);
            Path copy = art(first.artwork()).resolve(file);
            assertArrayEquals(Files.readAllBytes(old), Files.readAllBytes(copy));
            assertFalse(Files.isSameFile(old, copy));
        }
        assertEquals(before, hashes(original.id()));
        assertEquals(MapStorage.ImageReference.thumbnail(original.id()), MapItemData.requireReference(source));
        verify(source, never()).setAmount(anyInt());
        verify(source, never()).setItemMeta(any());
        assertTrue(storage.deleteImage(first));
        assertEquals(Color.MAGENTA.getRGB(), storage.read(second).getRGB(0, 0));
        assertEquals(before, hashes(original.id()));
    }

    @Test void tileCopyKeepsCoordinatesButGetsAnIndependentArtwork() throws Exception {
        ItemStack tile = item(MapStorage.ImageReference.tile(original.id(), 0, 0));
        AtomicReference<ItemStack> delivered = new AtomicReference<>();
        assertTrue(service.copy(player, tile, 1, items -> { delivered.set(items.get(0)); return true; }));
        workers.remove(0).run();
        var copy = MapItemData.requireReference(delivered.get());
        assertFalse(copy.thumbnail());
        assertEquals(0, copy.x());
        assertEquals(0, copy.y());
        assertNotEquals(original.id(), copy.artwork());
        assertEquals(Color.MAGENTA.getRGB(), storage.read(copy).getRGB(0, 0));
    }

    @Test void rejectedCommitRemovesOnlyUnpublishedCopiesAndReleasesPlayer() throws Exception {
        assertTrue(service.copy(player, source, 2, items -> false));
        workers.remove(0).run();
        assertEquals(2, platform.created.size());
        assertEquals(List.of(original.id().toString()), directories());
        assertTrue(platform.created.stream().noneMatch(reference -> Files.exists(art(reference.artwork()))));
        assertTrue(service.copy(player, source, 1, items -> true));
        workers.remove(0).run();
        assertEquals(2, directories().size());
    }

    @Test void staleInventoryDoesNotCreateMapViewsOrCallCommit() throws Exception {
        Function<List<ItemStack>, Boolean> commit = mock(Function.class);
        assertTrue(service.copy(player, source, 2, () -> false, commit));
        workers.remove(0).run();
        verifyNoInteractions(commit);
        assertTrue(platform.created.isEmpty());
        assertEquals(List.of(original.id().toString()), directories());
        assertTrue(platform.errors.contains(Language.mapCopy.changed));
    }

    @Test void uncertainCommitKeepsEveryPotentiallyDeliveredCopy() throws Exception {
        assertTrue(service.copy(player, source, 2, items -> { throw new IllegalStateException("partial inventory write"); }));
        workers.remove(0).run();
        assertEquals(3, directories().size());
        for (var reference : platform.created) assertEquals(Color.MAGENTA.getRGB(), storage.read(reference).getRGB(0, 0));
        assertTrue(platform.errors.contains(Language.mapCopy.uncertain));
        assertTrue(service.copy(player, source, 1, items -> false));
        workers.remove(0).run();
        assertEquals(3, directories().size());
    }

    @Test void itemConstructionFailureRemovesCopiesBeforeAnyInventoryCallback() throws Exception {
        platform.failItem = 2;
        Function<List<ItemStack>, Boolean> commit = mock(Function.class);
        assertTrue(service.copy(player, source, 2, commit));
        workers.remove(0).run();
        verifyNoInteractions(commit);
        assertEquals(List.of(original.id().toString()), directories());
        assertTrue(platform.errors.contains(Language.mapCopy.failed));
    }

    @Test void retiredPlayerDoesNotReceiveItemsAndCopiedFilesAreRemoved() throws Exception {
        platform.retire = true;
        Function<List<ItemStack>, Boolean> commit = mock(Function.class);
        assertTrue(service.copy(player, source, 2, commit));
        workers.remove(0).run();
        verifyNoInteractions(commit);
        assertTrue(platform.created.isEmpty());
        assertEquals(List.of(original.id().toString()), directories());
    }

    @Test void closeBeforeQueuedWorkDoesNotCopyOrDeliverAnything() throws Exception {
        Function<List<ItemStack>, Boolean> commit = mock(Function.class);
        assertTrue(service.copy(player, source, 2, commit));
        service.close();
        workers.remove(0).run();
        verifyNoInteractions(commit);
        assertTrue(platform.created.isEmpty());
        assertEquals(List.of(original.id().toString()), directories());
        assertFalse(service.copy(player, source, 1, commit));
    }

    @Test void closeWhileWaitingForOwnerCancelsLateCallbackAndRemovesCopies() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> future = new AtomicReference<>();
        CountDownLatch queuedOwner = new CountDownLatch(1);
        AtomicReference<Runnable> lateOwner = new AtomicReference<>();
        platform.scheduling = (action, retired) -> { lateOwner.set(action); queuedOwner.countDown(); };
        ArtworkCopyService asynchronous = service(task -> future.set(executor.submit(task)));
        Function<List<ItemStack>, Boolean> commit = mock(Function.class);
        try {
            assertTrue(asynchronous.copy(player, source, 2, commit));
            assertTrue(queuedOwner.await(5, TimeUnit.SECONDS));
            asynchronous.close();
            future.get().get(5, TimeUnit.SECONDS);
            lateOwner.get().run();
            verifyNoInteractions(commit);
            assertTrue(platform.created.isEmpty());
            assertEquals(List.of(original.id().toString()), directories());
        } finally {
            asynchronous.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void queueRejectionReleasesThePlayersPendingGuard() throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        ArtworkCopyService rejecting = service(task -> {
            if (submitted.getAndIncrement() == 0) throw new RejectedExecutionException("full");
            workers.add(task);
        });
        assertFalse(rejecting.copy(player, source, 1, items -> true));
        assertTrue(rejecting.copy(player, source, 1, items -> true));
        workers.remove(0).run();
        assertEquals(2, directories().size());
    }

    @Test void executorFailureAndSourceCloneFailureDoNotLeavePendingPlayers() throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        ArtworkCopyService failing = service(task -> {
            if (submitted.getAndIncrement() == 0) throw new IllegalStateException("executor stopped unexpectedly");
            workers.add(task);
        });
        assertThrows(IllegalStateException.class, () -> failing.copy(player, source, 1, items -> true));
        assertTrue(failing.copy(player, source, 1, items -> true));
        workers.remove(0).run();
        when(source.clone()).thenThrow(new IllegalStateException("broken item"));
        assertThrows(IllegalStateException.class, () -> service.copy(player, source, 1, items -> true));
        doReturn(source).when(source).clone();
        assertTrue(service.copy(player, source, 1, items -> true));
        workers.remove(0).run();
        assertEquals(3, directories().size());
    }

    @Test void missingSourceFileFailsBeforeAnyInventoryChanges() throws Exception {
        Files.delete(art(original.id()).resolve("0_0.png"));
        Function<List<ItemStack>, Boolean> commit = mock(Function.class);
        assertTrue(service.copy(player, source, 2, commit));
        workers.remove(0).run();
        verifyNoInteractions(commit);
        assertTrue(platform.created.isEmpty());
        assertEquals(List.of(original.id().toString()), directories());
        assertTrue(platform.errors.contains(Language.mapCopy.failed));
    }

    @Test void samePlayerCannotQueueTwoCopiesUntilFirstCompletes() throws Exception {
        assertTrue(service.copy(player, source, 1, items -> true));
        assertFalse(service.copy(player, source, 1, items -> true));
        assertEquals(1, workers.size());
        workers.remove(0).run();
        assertTrue(service.copy(player, source, 1, items -> true));
        workers.remove(0).run();
        assertEquals(3, directories().size());
    }

    private ArtworkCopyService service(Executor executor) {
        return new ArtworkCopyService(storage, executor, platform, logger);
    }

    private ItemStack item(MapStorage.ImageReference reference) {
        ItemStack item = mock(ItemStack.class);
        MapMeta meta = mock(MapMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.FILLED_MAP);
        when(item.getAmount()).thenReturn(1);
        when(item.getItemMeta()).thenReturn(meta);
        when(item.clone()).thenReturn(item);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(MapItemData.IMAGE_PATH, PersistentDataType.STRING)).thenReturn(true);
        when(pdc.get(MapItemData.IMAGE_PATH, PersistentDataType.STRING)).thenReturn(reference.path());
        return item;
    }

    private Path art(UUID id) { return directory.resolve("maps").resolve(id.toString()); }

    private List<String> directories() throws Exception {
        try (var paths = Files.list(directory.resolve("maps"))) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private Map<String, String> hashes(UUID id) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(art(id))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                result.put(art(id).relativize(file).toString(), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
            }
        }
        return result;
    }

    private interface Scheduling { void run(Runnable action, Runnable retired); }

    private final class TestPlatform implements ArtworkCopyService.Platform {
        final List<MapStorage.ImageReference> created = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        int failItem;
        boolean retire;
        Scheduling scheduling;
        public void schedule(Player player, Runnable action, Runnable retired) {
            if (scheduling != null) scheduling.run(action, retired);
            else if (retire) retired.run();
            else action.run();
        }
        public ItemStack item(Player player, ItemStack source, MapStorage.ImageReference reference) {
            if (failItem == created.size() + 1) throw new IllegalStateException("map item construction failed");
            created.add(reference);
            return ArtworkCopyServiceTest.this.item(reference);
        }
        public void error(Player player, String message) { errors.add(message); }
    }
}
