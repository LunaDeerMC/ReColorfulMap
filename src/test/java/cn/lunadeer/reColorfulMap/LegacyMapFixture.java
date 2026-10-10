package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** A directory exactly like copying plugins/ColorfulMap/maps from version 2.3. */
public final class LegacyMapFixture {
    public static final UUID ARTWORK = UUID.fromString("b44b66b1-1260-43a6-91bb-aeb1f3dca74d");
    public static final UUID OTHER_ARTWORK = UUID.fromString("0ca1858f-3e96-40d1-913e-53960ab57f41");
    public static final int THUMBNAIL_ID = 123;

    private LegacyMapFixture() {
    }

    public static MapStorage copyTo(Path pluginDirectory) throws IOException {
        writeArtwork(pluginDirectory, ARTWORK, 0xffff0000);
        writeArtwork(pluginDirectory, OTHER_ARTWORK, 0xff0000ff);
        Files.writeString(pluginDirectory.resolve("maps/maps.yml"), """
                world:
                  '123': maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/thumb.png
                  '124': maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/0_0.png
                  '125': maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/1_0.png
                  '126': maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/2_0.png
                  '127': maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/0_1.png
                  '128': maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/1_1.png
                  '129': maps/b44b66b1-1260-43a6-91bb-aeb1f3dca74d/2_1.png
                  '999': maps/0ca1858f-3e96-40d1-913e-53960ab57f41/1_0.png
                world_nether:
                  '123': maps/0ca1858f-3e96-40d1-913e-53960ab57f41/thumb.png
                """);
        return new MapStorage(pluginDirectory);
    }

    private static void writeArtwork(Path pluginDirectory, UUID id, int color) throws IOException {
        Path directory = Files.createDirectories(pluginDirectory.resolve("maps").resolve(id.toString()));
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        ImageIO.write(image, "png", directory.resolve("thumb.png").toFile());
        // No raw.png or meta.txt is needed to restore a complete legacy grid.
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                ImageIO.write(image, "png", directory.resolve(x + "_" + y + ".png").toFile());
            }
        }
    }
}
