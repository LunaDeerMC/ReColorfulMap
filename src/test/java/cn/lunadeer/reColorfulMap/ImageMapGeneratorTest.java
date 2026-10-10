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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImageMapGeneratorTest {
    @TempDir Path directory;

    @Test
    void generatesLegacyFilesFromScaledImageAndPreservesOriginal() throws Exception {
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
            assertEquals(0, storage.size()); // no Bukkit map IDs are invented by the background worker
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

    private void withImageServer(BufferedImage image, ImageRequest test) throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ImageIO.write(image, "png", data);
        byte[] png = data.toByteArray();
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
