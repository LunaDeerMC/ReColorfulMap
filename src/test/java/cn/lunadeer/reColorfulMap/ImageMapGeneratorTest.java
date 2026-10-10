package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.utils.ImageUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class ImageMapGeneratorTest {
    @TempDir Path directory;

    @Test
    void generatesPngFilesFromScaledImageAndPreservesOriginal() throws Exception {
        BufferedImage source = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        source.setRGB(100, 100, 0x123456);
        withImageServer(source, url -> {
            MapStorage storage = new MapStorage(directory);
            MapStorage.Artwork art = ImageMapGenerator.generate(storage, url, 0.5f, 32, 18, List.of());
            assertEquals(3, art.columns());
            assertEquals(2, art.rows());
            assertEquals(art, new MapStorage(directory).artwork(art.id()));
            BufferedImage raw = ImageIO.read(directory.resolve("maps/" + art.id() + "/raw.png").toFile());
            assertEquals(600, raw.getWidth());
            assertEquals(400, raw.getHeight());
            assertEquals(0x123456, raw.getRGB(100, 100) & 0xffffff);
            BufferedImage tile = storage.read(MapStorage.ImageReference.tile(art.id(), 0, 0));
            assertEquals(128, tile.getWidth());
            assertEquals(0, tile.getRGB(0, 0)); // centered padding remains transparent
            assertFalse(Files.exists(directory.resolve("maps/maps.yml")));
            assertFalse(Files.exists(directory.resolve("maps/maps.yaml")));
        });
    }

    @Test
    void rejectsOversizedScaleBeforeCreatingAnyArtwork() throws Exception {
        withImageServer(new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB), url -> {
            MapStorage storage = new MapStorage(directory);
            assertThrows(IOException.class, () -> ImageMapGenerator.generate(storage, url, 1000, 32, 18, List.of()));
            try (var files = Files.list(directory.resolve("maps"))) {
                assertEquals(0, files.count());
            }
        });
    }

    @Test
    void rejectsNonFiniteAndNonPositiveScaleBeforeOpeningNetwork() throws Exception {
        MapStorage storage = new MapStorage(directory);
        for (float scale : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IOException.class, () -> ImageMapGenerator.generate(storage, "http://127.0.0.1:1/image", scale, 32, 18, List.of()));
        }
    }

    @Test
    void thumbnailSupportsExtremelyNarrowLegacyImages() {
        BufferedImage thumbnail = ImageUtils.thumb(new BufferedImage(1, 4096, BufferedImage.TYPE_INT_ARGB));
        assertEquals(128, thumbnail.getWidth());
        assertEquals(128, thumbnail.getHeight());
    }

    @Test
    void rejectsCompressedOriginalPixelCountEvenWhenScaledOutputWouldFit() throws Exception {
        BufferedImage source = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);
        assertTrue(encoded.size() < 10_000, "The compressed body must be small enough to pass the byte limit");
        withBytesServer(encoded.toByteArray(), url -> {
            MapStorage storage = new MapStorage(directory);
            Configuration.DownloadSettings limits = new Configuration.DownloadSettings(100_000, 100_000,
                    1000, 1000, 3000, 3);
            IOException failure = assertThrows(IOException.class,
                    () -> ImageMapGenerator.generate(storage, url, .1f, 32, 18, List.of(), limits));
            assertTrue(failure.getMessage().contains("pixel limit"));
            assertNoArtwork();
        });
    }

    @Test
    void validatesDimensionsBeforeAllocatingOrDecodingPixels() throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", encoded);
        byte[] png = encoded.toByteArray();
        // A valid IHDR with a deliberately mismatched tiny IDAT: decoding would fail,
        // but allocating the advertised raster first would require about 400 MB.
        ByteBuffer.wrap(png, 16, 8).putInt(10_000).putInt(10_000);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png, 29, 4).putInt((int) crc.getValue());
        withBytesServer(png, url -> {
            MapStorage storage = new MapStorage(directory);
            IOException failure = assertThrows(IOException.class,
                    () -> ImageMapGenerator.generate(storage, url, .01f, 32, 18, List.of()));
            assertTrue(failure.getMessage().contains("pixel limit"));
            assertNoArtwork();
        });
    }

    @Test
    void rejectsInvalidImagesWithoutPublishingFiles() throws Exception {
        withBytesServer(new byte[]{1, 2, 3, 4}, url -> {
            MapStorage storage = new MapStorage(directory);
            assertThrows(IOException.class,
                    () -> ImageMapGenerator.generate(storage, url, 1, 32, 18, List.of()));
            assertNoArtwork();
        });
    }

    @Test
    void rejectsDisallowedAddressBeforeContactingIt() throws Exception {
        MapStorage storage = new MapStorage(directory);
        IOException failure = assertThrows(IOException.class,
                () -> ImageMapGenerator.generate(storage, "http://127.0.0.1:1/image", 1, 32, 18,
                        List.of("https://images.example.org")));
        assertEquals(Language.imageMapItem.urlNotAllowed.formatted("http://127.0.0.1:1/image"), failure.getMessage());
        assertNoArtwork();
    }

    @Test
    void malformedWhitelistErrorTranslationStillReportsAnIoFailure() throws Exception {
        String original = Language.imageMapItem.urlNotAllowed;
        try {
            Language.imageMapItem.urlNotAllowed = "Bad placeholder: %q";
            MapStorage storage = new MapStorage(directory);
            IOException failure = assertThrows(IOException.class,
                    () -> ImageMapGenerator.generate(storage, "http://127.0.0.1:1/image", 1, 32, 18,
                            List.of("https://images.example.org")));
            assertTrue(failure.getMessage().contains("http://127.0.0.1:1/image"));
            assertNoArtwork();
        } finally {
            Language.imageMapItem.urlNotAllowed = original;
        }
    }

    @Test
    void malformedSizeErrorTranslationStillRejectsTheOversizedImage() throws Exception {
        String original = Language.imageMapItem.imageSizeNotAllowed;
        try {
            Language.imageMapItem.imageSizeNotAllowed = "Bad placeholder: %q";
            withImageServer(new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB), url -> {
                MapStorage storage = new MapStorage(directory);
                IOException failure = assertThrows(IOException.class,
                        () -> ImageMapGenerator.generate(storage, url, 1000, 32, 18, List.of()));
                assertTrue(failure.getMessage().contains("4096"));
                assertNoArtwork();
            });
        } finally {
            Language.imageMapItem.imageSizeNotAllowed = original;
        }
    }

    private void assertNoArtwork() throws IOException {
        try (var paths = Files.list(directory.resolve("maps"))) {
            assertEquals(0, paths.count());
        }
    }

    private void withImageServer(BufferedImage image, ImageRequest test) throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ImageIO.write(image, "png", data);
        withBytesServer(data.toByteArray(), test);
    }

    private void withBytesServer(byte[] png, ImageRequest test) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image.png", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
            }
        });
        server.start();
        try {
            test.run("http://127.0.0.1:" + server.getAddress().getPort() + "/image.png");
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface ImageRequest {
        void run(String url) throws Exception;
    }
}
