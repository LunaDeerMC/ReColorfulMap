package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.utils.ImageUtils;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;

public class ImageRenderer extends MapRenderer {

    public static class ImageRendererText extends ConfigurationPart {
        public String failToRender = "Failed to render image: %s";
        public String imageTooLarge = "image (%d x %d) is too large.";
    }

    public ImageRenderer(BufferedImage image) {
        this.image = image;
    }

    private final BufferedImage image;

    /**
     * Render to the given map.
     *
     * @param map    The MapView being rendered to.
     * @param canvas The canvas to use for rendering.
     * @param player The player who triggered the rendering.
     */
    @Override
    public void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
        try {
            if (image.getWidth() > 128 || image.getHeight() > 128) {
                throw new Exception(Language.imageRenderer.imageTooLarge.formatted(image.getWidth(), image.getHeight()));
            }
            int x_offset = (128 - image.getWidth()) / 2;
            int y_offset = (128 - image.getHeight()) / 2;
            canvas.drawImage(x_offset, y_offset, image);
        } catch (Exception e) {
            Notification.error(player, Language.imageRenderer.failToRender.formatted(e.getMessage()));
        }
    }

    public static MapMeta renderOnMeta(MapMeta meta, byte[] base64) throws Exception {
        BufferedImage image = ImageUtils.decodeBase64ToImage(base64);
        return renderOnMeta(meta, image);
    }

    public static MapMeta renderOnMeta(MapMeta meta, BufferedImage image) throws Exception {
        MapView view = meta.getMapView();
        if (view == null) {
            throw new Exception("MapMeta does not have a MapView.");
        }
        view.getRenderers().clear();
        view.addRenderer(new ImageRenderer(image));
        meta.setMapView(view);
        return meta;
    }
}
