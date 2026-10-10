package cn.lunadeer.reColorfulMap.storage;

import cn.lunadeer.reColorfulMap.storage.MapStorage.Artwork;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MapStorageTest {
    @TempDir
    Path data;

    @ParameterizedTest
    @ValueSource(strings = {"maps.yml", "maps.yaml"})
    void refusesLegacyIndexAndRequestsOfflineMigration(String name) throws Exception {
        Files.createDirectory(data.resolve("maps"));
        Path index = data.resolve("maps").resolve(name);
        String yaml = "world:\n  '17': maps/" + UUID.randomUUID() + "/thumb.png\n";
        Files.writeString(index, yaml);

        IOException failure = assertThrows(IOException.class, () -> new MapStorage(data));

        assertTrue(failure.getMessage().contains("Stop the server"));
        assertTrue(failure.getMessage().contains("migration script"));
        assertEquals(yaml, Files.readString(index));
    }

    @Test
    void createsPngLayoutWithTopLeftTileOriginWithoutAnIndex() throws Exception {
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
        MapStorage restarted = new MapStorage(data);
        assertEquals(artwork, restarted.artwork(id));
        assertEquals(Color.WHITE.getRGB(), restarted.read(ImageReference.thumbnail(id)).getRGB(0, 0));
        try (var directories = Files.list(data.resolve("maps"))) {
            assertEquals(List.of(id.toString()), directories.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void infersMatrixWithoutDependingOnOperationLog() throws Exception {
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
    void missingOrCorruptPngNeverDeletesArtwork() throws Exception {
        MapStorage storage = new MapStorage(data);
        Artwork artwork = create(storage, Color.BLACK);
        ImageReference reference = ImageReference.tile(artwork.id(), 0, 0);
        Path tile = data.resolve(reference.path());
        Files.writeString(tile, "broken image");

        assertThrows(IOException.class, () -> storage.read(reference));
        assertEquals("broken image", Files.readString(tile));
        Files.delete(tile);
        assertThrows(IOException.class, () -> storage.read(reference));
        assertTrue(Files.exists(data.resolve("maps/" + artwork.id() + "/thumb.png")));
        assertFalse(storage.isDeleted(artwork.id()));
    }

    @Test
    void rejectsOversizedMapImageWithoutRemovingIt() throws Exception {
        MapStorage storage = new MapStorage(data);
        UUID id = UUID.randomUUID();
        Path directory = Files.createDirectory(data.resolve("maps/" + id));
        ImageIO.write(image(129, 128, Color.BLACK), "png", directory.resolve("thumb.png").toFile());

        assertThrows(IOException.class, () -> storage.read(ImageReference.thumbnail(id)));
        assertTrue(Files.exists(directory.resolve("thumb.png")));
    }

    @Test
    void refusesMapsDirectoryReplacementForEveryDiskOperation() throws Exception {
        MapStorage storage = new MapStorage(data);
        Artwork artwork = create(storage, Color.BLACK);
        Path preserved = data.resolve("preserved-maps");
        Files.move(data.resolve("maps"), preserved);
        Path outside = Files.createDirectory(data.resolve("outside"));
        Files.createSymbolicLink(data.resolve("maps"), outside);

        assertThrows(IOException.class, () -> create(storage, Color.RED));
        assertThrows(IOException.class, () -> storage.read(ImageReference.thumbnail(artwork.id())));
        assertThrows(IOException.class, () -> storage.artwork(artwork.id()));
        assertThrows(IOException.class, () -> storage.deleteArtwork(artwork.id()));
        assertThrows(IOException.class, storage::cleanTemporaryFiles);
        assertTrue(Files.exists(preserved.resolve(artwork.id() + "/thumb.png")));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void cleanupOnlyRemovesOwnedTemporaryFilesAndNeverPublishedArtwork() throws Exception {
        MapStorage storage = new MapStorage(data);
        Artwork artwork = create(storage, Color.RED);
        Path abandoned = Files.createDirectory(data.resolve("maps/.recolorfulmap-artwork-" + UUID.randomUUID() + ".tmp"));
        Files.writeString(abandoned.resolve("partial.png"), "partial write");
        Path deleting = Files.createDirectory(data.resolve("maps/.recolorfulmap-delete-" + UUID.randomUUID() + ".tmp"));
        Files.writeString(deleting.resolve("0_0.png"), "pending removal");
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
        assertEquals(Color.RED.getRGB(), storage.read(ImageReference.thumbnail(artwork.id())).getRGB(0, 0));
        assertEquals(0, storage.cleanTemporaryFiles());
    }

    @Test
    void referencesRoundTripWithCanonicalPaths() {
        UUID id = UUID.fromString("b44b66b1-1260-43a6-91bb-aeb1f3dca74d");
        for (ImageReference reference : List.of(ImageReference.thumbnail(id), ImageReference.tile(id, 0, 0),
                ImageReference.tile(id, 5, 21), ImageReference.tile(id, Integer.MAX_VALUE, Integer.MAX_VALUE))) {
            assertEquals(reference, ImageReference.parse(reference.path()).orElseThrow());
        }
        assertTrue(ImageReference.parse(null).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "/maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/thumb.png",
            "maps/../thumb.png", "maps/../../outside.png", "maps/1-1-1-1-1/thumb.png",
            "maps/B44B66B1-1260-43A6-91BB-AEB1F3DCA74D/thumb.png",
            "maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/../thumb.png",
            "maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/raw.png",
            "maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/00_0.png",
            "maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/-1_0.png",
            "maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/2147483648_0.png",
            "maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/thumb.png\n",
            "maps\\b44b66b1-1260-43a6-91bb-aeb1f3dca74d\\thumb.png",
            "maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/%2e%2e/thumb.png"
    })
    void rejectsUnsafeOrNoncanonicalReferencePaths(String path) {
        assertTrue(ImageReference.parse(path).isEmpty());
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
        assertThrows(IOException.class, () -> storage.artwork(id));
        assertThrows(IOException.class, () -> storage.deleteArtwork(id));
        Files.delete(artworkDirectory);
        Files.createDirectory(artworkDirectory);
        Files.createSymbolicLink(artworkDirectory.resolve("thumb.png"), outside.resolve("thumb.png"));
        assertThrows(IOException.class, () -> storage.read(ImageReference.thumbnail(id)));
        assertTrue(Files.exists(outside.resolve("thumb.png")));
        assertFalse(storage.isDeleted(id));
    }

    @Test
    void deletesOnlySelectedArtworkWithoutFollowingNestedSymlinks() throws Exception {
        MapStorage storage = new MapStorage(data);
        Artwork removed = create(storage, Color.RED);
        Artwork kept = create(storage, Color.BLUE);
        Path outside = Files.createDirectory(data.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep me");
        Files.createSymbolicLink(data.resolve("maps/" + removed.id() + "/nested-link"), outside);
        assertFalse(storage.isDeleted(removed.id()));

        assertTrue(storage.deleteArtwork(removed.id()));

        assertTrue(storage.isDeleted(removed.id()));
        assertFalse(storage.isDeleted(kept.id()));
        assertFalse(storage.deleteArtwork(removed.id()));
        assertThrows(IOException.class, () -> storage.read(ImageReference.thumbnail(removed.id())));
        assertThrows(IOException.class, () -> storage.artwork(removed.id()));
        assertEquals(kept, storage.artwork(kept.id()));
        assertEquals(Color.BLUE.getRGB(), storage.read(ImageReference.thumbnail(kept.id())).getRGB(0, 0));
        assertEquals("keep me", Files.readString(outside.resolve("keep.txt")));
        assertEquals(0, storage.cleanTemporaryFiles());
        try (var files = Files.list(data.resolve("maps"))) {
            assertEquals(List.of(kept.id().toString()), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void deletingMissingArtworkIsAnUnmarkedNoOp() throws Exception {
        MapStorage storage = new MapStorage(data);
        UUID id = UUID.randomUUID();

        assertFalse(storage.deleteArtwork(id));
        assertFalse(storage.isDeleted(id));
    }

    @Test
    void concurrentDeletionUnpublishesArtworkOnlyOnce() throws Exception {
        MapStorage storage = new MapStorage(data);
        Artwork artwork = create(storage, Color.RED);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> storage.deleteArtwork(artwork.id()));
            var second = workers.submit(() -> storage.deleteArtwork(artwork.id()));
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertTrue(storage.isDeleted(artwork.id()));
            assertEquals(0, storage.cleanTemporaryFiles());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
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
    void failureAfterWritingThumbnailNeverPublishesPartialArtwork() throws Exception {
        MapStorage storage = new MapStorage(data);
        BufferedImage brokenTiles = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB) {
            @Override
            public BufferedImage getSubimage(int x, int y, int width, int height) {
                throw new IllegalStateException("Failed to extract tile");
            }
        };

        assertThrows(IllegalStateException.class, () -> storage.create(image(128, 128, Color.RED),
                brokenTiles, image(128, 128, Color.RED)));

        try (var files = Files.list(data.resolve("maps"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void slowEncodingAllowsReadsAndKeepsActiveStagingSafeFromCleanup() throws Exception {
        MapStorage storage = new MapStorage(data);
        Artwork existing = create(storage, Color.RED);
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
                assertEquals(existing, storage.artwork(existing.id()));
                assertEquals(Color.RED.getRGB(), storage.read(ImageReference.thumbnail(existing.id())).getRGB(0, 0));
                assertEquals(0, storage.cleanTemporaryFiles());
                assertTrue(Files.isDirectory(staging), "Cleanup must leave the active image directory intact");
                return null;
            });
            access.get(3, TimeUnit.SECONDS);
            assertFalse(creation.isDone(), "Reads and cleanup should finish while PNG encoding remains blocked");
            resumeEncoding.countDown();
            Artwork artwork = creation.get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(staging));
            assertEquals(artwork, storage.artwork(artwork.id()));
            assertEquals(Color.BLUE.getRGB(), storage.read(ImageReference.tile(artwork.id(), 0, 0)).getRGB(0, 0));
        } finally {
            resumeEncoding.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Artwork create(MapStorage storage, Color color) throws IOException {
        BufferedImage image = image(128, 128, color);
        return storage.create(image, image, image);
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
