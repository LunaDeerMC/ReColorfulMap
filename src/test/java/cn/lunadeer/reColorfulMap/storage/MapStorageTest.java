package cn.lunadeer.reColorfulMap.storage;

import cn.lunadeer.reColorfulMap.storage.MapStorage.Artwork;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import cn.lunadeer.reColorfulMap.storage.MapStorage.MapKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MapStorageTest {
    @TempDir
    Path data;

    @Test
    void readsLegacyIndexWithoutDecodingOrRemovingMissingImages() throws Exception {
        UUID id = UUID.randomUUID();
        Files.createDirectory(data.resolve("maps"));
        String yaml = "world:\n  '17': maps/" + id + "/thumb.png\n"
                + "  '18': maps/" + id + "/1_0.png\n"
                + "world_nether:\n  '17': maps/" + id + "/0_1.png\n";
        Files.writeString(data.resolve("maps/maps.yml"), yaml);

        MapStorage storage = new MapStorage(data);

        assertEquals(3, storage.size());
        assertEquals(ImageReference.thumbnail(id), storage.find("world", 17).orElseThrow());
        assertEquals(ImageReference.tile(id, 1, 0), storage.find("world", 18).orElseThrow());
        assertEquals(ImageReference.tile(id, 0, 1), storage.find("world_nether", 17).orElseThrow());
        assertThrows(IOException.class, () -> storage.read(ImageReference.thumbnail(id)));
        assertEquals(yaml, Files.readString(data.resolve("maps/maps.yml")));
        assertEquals(3, new MapStorage(data).size());
    }

    @Test
    void createsLegacyLayoutWithTopLeftTileOriginAndRestoresAllAliases() throws Exception {
        MapStorage storage = new MapStorage(data);
        BufferedImage centered = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = centered.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 128, 128);
            graphics.setColor(Color.GREEN);
            graphics.fillRect(128, 0, 128, 128);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 128, 128, 128);
            graphics.setColor(Color.YELLOW);
            graphics.fillRect(128, 128, 128, 128);
        } finally {
            graphics.dispose();
        }
        Artwork artwork = storage.create(image(9, 7, Color.MAGENTA), centered, image(128, 128, Color.WHITE));
        UUID id = artwork.id();
        assertEquals(new Artwork(id, 2, 2), storage.artwork(id));
        assertEquals(Color.RED.getRGB(), storage.read(ImageReference.tile(id, 0, 0)).getRGB(64, 64));
        assertEquals(Color.GREEN.getRGB(), storage.read(ImageReference.tile(id, 1, 0)).getRGB(64, 64));
        assertEquals(Color.BLUE.getRGB(), storage.read(ImageReference.tile(id, 0, 1)).getRGB(64, 64));
        assertEquals(Color.YELLOW.getRGB(), storage.read(ImageReference.tile(id, 1, 1)).getRGB(64, 64));
        BufferedImage raw = ImageIO.read(data.resolve("maps/" + id + "/raw.png").toFile());
        assertEquals(9, raw.getWidth());
        assertEquals(7, raw.getHeight());
        assertEquals(Color.MAGENTA.getRGB(), raw.getRGB(0, 0));

        Map<MapKey, ImageReference> aliases = Map.of(
                new MapKey("world", 9), ImageReference.thumbnail(id),
                new MapKey("world", 10), ImageReference.thumbnail(id),
                new MapKey("world", 11), ImageReference.tile(id, 0, 0),
                new MapKey("world.with.dots", 9), ImageReference.tile(id, 1, 1));
        storage.register(aliases);
        storage.register(aliases);
        MapStorage restarted = new MapStorage(data);
        assertEquals(4, restarted.size());
        aliases.forEach((key, reference) -> assertEquals(reference, restarted.find(key.world(), key.id()).orElseThrow()));
        assertEquals(artwork, restarted.artwork(id));
        assertTrue(Files.readString(data.resolve("maps/maps.yml")).contains("maps/" + id + "/thumb.png"));
    }

    @Test
    void infersLegacyMatrixWithoutDependingOnOperationLog() throws Exception {
        UUID id = UUID.randomUUID();
        Path directory = Files.createDirectories(data.resolve("maps/" + id));
        ImageIO.write(image(128, 128, Color.BLACK), "png", directory.resolve("thumb.png").toFile());
        ImageIO.write(image(128, 128, Color.BLACK), "png", directory.resolve("0_0.png").toFile());
        ImageIO.write(image(128, 128, Color.BLACK), "png", directory.resolve("1_0.png").toFile());
        Files.writeString(directory.resolve("meta.txt"), "old plugin operation log, not dimensions");

        assertEquals(new Artwork(id, 2, 1), new MapStorage(data).artwork(id));
    }

    @Test
    void rejectsIncompleteMatrix() throws Exception {
        MapStorage storage = new MapStorage(data);
        Artwork artwork = storage.create(image(256, 256, Color.BLACK), image(256, 256, Color.BLACK), image(128, 128, Color.BLACK));
        Files.delete(data.resolve("maps/" + artwork.id() + "/0_1.png"));

        assertThrows(IOException.class, () -> storage.artwork(artwork.id()));
    }

    @Test
    void missingOrCorruptPngNeverDeletesAnAlias() throws Exception {
        MapStorage storage = new MapStorage(data);
        Artwork artwork = storage.create(image(128, 128, Color.BLACK), image(128, 128, Color.BLACK), image(128, 128, Color.BLACK));
        ImageReference reference = ImageReference.tile(artwork.id(), 0, 0);
        storage.register(Map.of(new MapKey("world", 23), reference));
        String index = Files.readString(data.resolve("maps/maps.yml"));
        Path tile = data.resolve(reference.path());
        Files.writeString(tile, "broken image");

        assertThrows(IOException.class, () -> storage.read(reference));
        assertEquals(reference, new MapStorage(data).find("world", 23).orElseThrow());
        Files.delete(tile);
        assertThrows(IOException.class, () -> storage.read(reference));
        assertEquals(reference, new MapStorage(data).find("world", 23).orElseThrow());
        assertEquals(index, Files.readString(data.resolve("maps/maps.yml")));
    }

    @Test
    void rejectsOversizedMapImageWithoutAlteringIndex() throws Exception {
        MapStorage storage = new MapStorage(data);
        UUID id = UUID.randomUUID();
        Path directory = Files.createDirectory(data.resolve("maps/" + id));
        ImageIO.write(image(129, 128, Color.BLACK), "png", directory.resolve("thumb.png").toFile());
        ImageReference reference = ImageReference.thumbnail(id);
        storage.register(Map.of(new MapKey("world", 1), reference));

        assertThrows(IOException.class, () -> storage.read(reference));
        assertEquals(reference, storage.find("world", 1).orElseThrow());
    }

    @Test
    void conflictingBatchPreservesAllExistingDiskAndMemoryMappings() throws Exception {
        MapStorage storage = new MapStorage(data);
        ImageReference original = ImageReference.thumbnail(UUID.randomUUID());
        storage.register(Map.of(new MapKey("world", 1), original));
        String originalIndex = Files.readString(data.resolve("maps/maps.yml"));
        Map<MapKey, ImageReference> additions = new LinkedHashMap<>();
        additions.put(new MapKey("world", 2), original);
        additions.put(new MapKey("world", 1), ImageReference.thumbnail(UUID.randomUUID()));

        assertThrows(IOException.class, () -> storage.register(additions));

        assertTrue(storage.find("world", 2).isEmpty());
        assertEquals(original, storage.find("world", 1).orElseThrow());
        assertEquals(originalIndex, Files.readString(data.resolve("maps/maps.yml")));
        assertEquals(1, new MapStorage(data).size());
    }

    @Test
    void failedDiskWriteDoesNotPublishBatchOrReplaceExistingFile() throws Exception {
        MapStorage storage = new MapStorage(data);
        ImageReference reference = ImageReference.thumbnail(UUID.randomUUID());
        storage.register(Map.of(new MapKey("world", 1), reference));
        String originalIndex = Files.readString(data.resolve("maps/maps.yml"));
        Path preserved = data.resolve("preserved-maps");
        Files.move(data.resolve("maps"), preserved);
        Path outside = Files.createDirectory(data.resolve("outside"));
        Files.createSymbolicLink(data.resolve("maps"), outside);

        assertThrows(IOException.class, () -> storage.register(Map.of(new MapKey("world", 2), reference)));

        assertEquals(1, storage.size());
        assertTrue(storage.find("world", 2).isEmpty());
        assertEquals(originalIndex, Files.readString(preserved.resolve("maps.yml")));
        assertFalse(Files.exists(outside.resolve("maps.yml")));
    }

    @Test
    void cleanupOnlyRemovesOwnedTemporaryFilesAndNeverArtworkOrAliases() throws Exception {
        MapStorage storage = new MapStorage(data);
        Artwork artwork = storage.create(image(128, 128, Color.RED), image(128, 128, Color.RED), image(128, 128, Color.RED));
        ImageReference reference = ImageReference.thumbnail(artwork.id());
        storage.register(Map.of(new MapKey("world", 4), reference));
        Path abandoned = Files.createDirectory(data.resolve("maps/.recolorfulmap-artwork-" + UUID.randomUUID() + ".tmp"));
        Files.writeString(abandoned.resolve("partial.png"), "partial write");
        Files.writeString(data.resolve("maps/.recolorfulmap-index-" + UUID.randomUUID() + ".tmp"), "partial index");
        Path unrelated = data.resolve("maps/.recolorfulmap-user-backup.tmp");
        Files.writeString(unrelated, "keep me");
        Path outside = Files.createDirectory(data.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep me too");
        Files.createSymbolicLink(data.resolve("maps/.recolorfulmap-artwork-" + UUID.randomUUID() + ".tmp"), outside);

        assertEquals(3, storage.cleanTemporaryFiles());

        assertTrue(Files.exists(unrelated));
        assertTrue(Files.exists(outside.resolve("keep.txt")));
        assertTrue(Files.exists(data.resolve("maps/" + artwork.id() + "/raw.png")));
        assertEquals(artwork, storage.artwork(artwork.id()));
        assertEquals(reference, new MapStorage(data).find("world", 4).orElseThrow());
        assertEquals(Color.RED.getRGB(), storage.read(reference).getRGB(0, 0));
        assertEquals(0, storage.cleanTemporaryFiles());
    }

    @Test
    void rejectsTraversalInLegacyIndexWithoutRewritingIt() throws Exception {
        Files.createDirectory(data.resolve("maps"));
        String index = "world:\n  '1': maps/../../outside.png\n";
        Files.writeString(data.resolve("maps/maps.yml"), index);

        assertThrows(IOException.class, () -> new MapStorage(data));
        assertEquals(index, Files.readString(data.resolve("maps/maps.yml")));
    }

    @Test
    void refusesArtworkAndImageSymlinks() throws Exception {
        MapStorage storage = new MapStorage(data);
        Path outside = Files.createDirectory(data.resolve("outside"));
        ImageIO.write(image(128, 128, Color.RED), "png", outside.resolve("thumb.png").toFile());
        UUID id = UUID.randomUUID();
        Path artworkDirectory = data.resolve("maps/" + id);
        Files.createSymbolicLink(artworkDirectory, outside);
        assertThrows(IOException.class, () -> storage.read(ImageReference.thumbnail(id)));
        Files.delete(artworkDirectory);
        Files.createDirectory(artworkDirectory);
        Files.createSymbolicLink(artworkDirectory.resolve("thumb.png"), outside.resolve("thumb.png"));
        assertThrows(IOException.class, () -> storage.read(ImageReference.thumbnail(id)));
    }

    @Test
    void invalidCreationNeverPublishesAnArtwork() throws Exception {
        MapStorage storage = new MapStorage(data);
        assertThrows(IOException.class, () -> storage.create(image(128, 128, Color.BLACK), image(129, 128, Color.BLACK), image(128, 128, Color.BLACK)));
        assertThrows(IOException.class, () -> storage.create(image(128, 128, Color.BLACK), image(128, 128, Color.BLACK), image(128, 129, Color.BLACK)));
        try (var files = Files.list(data.resolve("maps"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void slowImageEncodingDoesNotBlockIndexAccessOrExposeActiveStagingToCleanup() throws Exception {
        MapStorage storage = new MapStorage(data);
        ImageReference reference = ImageReference.thumbnail(UUID.randomUUID());
        storage.register(Map.of(new MapKey("world", 1), reference));
        CountDownLatch encodingStarted = new CountDownLatch(1);
        CountDownLatch resumeEncoding = new CountDownLatch(1);
        BufferedImage slowOriginal = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB) {
            @Override
            public int getWidth() {
                encodingStarted.countDown();
                try {
                    if (!resumeEncoding.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release the image encoder");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Image encoder interrupted", exception);
                }
                return super.getWidth();
            }
        };
        var workers = Executors.newFixedThreadPool(2);
        try {
            var creation = workers.submit(() -> storage.create(slowOriginal,
                    image(128, 128, Color.BLUE), image(128, 128, Color.BLUE)));
            assertTrue(encodingStarted.await(5, TimeUnit.SECONDS), "PNG encoding should reach the blocking original image");
            Path staging;
            try (var files = Files.list(data.resolve("maps"))) {
                staging = files.filter(file -> file.getFileName().toString().startsWith(".recolorfulmap-artwork-"))
                        .findFirst().orElseThrow();
            }

            var access = workers.submit(() -> {
                assertEquals(reference, storage.find("world", 1).orElseThrow());
                assertEquals(1, storage.size());
                assertEquals(0, storage.cleanTemporaryFiles());
                assertTrue(Files.isDirectory(staging), "Cleanup must leave the active image directory intact");
                storage.register(Map.of(new MapKey("world", 2), reference));
                assertEquals(reference, storage.find("world", 2).orElseThrow());
                return null;
            });
            access.get(3, TimeUnit.SECONDS);
            assertFalse(creation.isDone(), "Index operations should finish while PNG encoding remains blocked");
            resumeEncoding.countDown();
            Artwork artwork = creation.get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(staging));
            assertEquals(artwork, storage.artwork(artwork.id()));
            assertEquals(Color.BLUE.getRGB(), storage.read(ImageReference.tile(artwork.id(), 0, 0)).getRGB(0, 0));
            assertEquals(2, new MapStorage(data).size());
        } finally {
            resumeEncoding.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void indexReadersDoNotWaitForTheRegistrationMonitor() throws Exception {
        MapStorage storage = new MapStorage(data);
        ImageReference reference = ImageReference.thumbnail(UUID.randomUUID());
        storage.register(Map.of(new MapKey("world", 5), reference));
        var reader = Executors.newSingleThreadExecutor();
        try {
            synchronized (storage) {
                var lookup = reader.submit(() -> {
                    assertEquals(reference, storage.find("world", 5).orElseThrow());
                    assertEquals(1, storage.size());
                });
                lookup.get(3, TimeUnit.SECONDS);
            }
        } finally {
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static BufferedImage image(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
