package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.utils.ImageUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.List;

/** Downloads and prepares files without touching worlds, map views or inventories. */
public final class ImageMapGenerator {
    private ImageMapGenerator() {
    }

    public static MapStorage.Artwork generate(MapStorage storage, String url, float scale,
                                             int maxColumns, int maxRows, List<String> whitelist) throws IOException {
        if (!Float.isFinite(scale) || scale <= 0) {
            throw new IOException(Language.toMapCommand.scaleMustBeNumber);
        }
        if (!urlAllowed(url, whitelist)) {
            throw new IOException(Language.imageMapItem.urlNotAllowed.formatted(url));
        }
        URLConnection connection;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Expected an HTTP or HTTPS image URL");
            }
            connection = uri.toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException(Language.imageMapItem.readImageFailed, e);
        }
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        BufferedImage original;
        try (InputStream stream = connection.getInputStream()) {
            original = ImageIO.read(stream);
        }
        if (original == null) {
            throw new IOException(Language.imageMapItem.readImageFailed);
        }
        double width = Math.floor(original.getWidth() * (double) scale);
        double height = Math.floor(original.getHeight() * (double) scale);
        if (width < 1 || height < 1) {
            throw new IOException(Language.toMapCommand.scaleMustBeNumber);
        }
        if (maxColumns <= 0 || maxRows <= 0 || width > (long) maxColumns * 128 || height > (long) maxRows * 128) {
            throw new IOException(Language.imageMapItem.imageSizeNotAllowed.formatted(maxColumns * 128, maxRows * 128));
        }
        int columns = (int) Math.ceil(width / 128);
        int rows = (int) Math.ceil(height / 128);
        BufferedImage resized = scale == 1 ? original : ImageUtils.resize(original, scale);
        BufferedImage centered = ImageUtils.center(resized, columns * 128, rows * 128);
        return storage.create(original, centered, ImageUtils.thumb(original));
    }

    private static boolean urlAllowed(String url, List<String> whitelist) {
        return whitelist.isEmpty() || whitelist.stream().anyMatch(prefix -> url.startsWith(prefix)
                || url.startsWith("http://" + prefix) || url.startsWith("https://" + prefix));
    }
}
