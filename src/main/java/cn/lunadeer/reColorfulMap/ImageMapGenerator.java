package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.services.download.HttpImageDownloader;
import cn.lunadeer.reColorfulMap.services.download.UrlWhitelist;
import cn.lunadeer.reColorfulMap.utils.ImageUtils;
import cn.lunadeer.reColorfulMap.utils.SafeMessageFormat;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.stream.FileImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

/** Downloads and prepares files without touching worlds, map views or inventories. */
public final class ImageMapGenerator {
    private static final Logger LOGGER = Logger.getLogger("ReColorfulMap");

    private ImageMapGenerator() {
    }

    public static MapStorage.Artwork generate(MapStorage storage, String url, float scale,
                                             int maxColumns, int maxRows, List<String> whitelist) throws IOException {
        return generate(storage, url, scale, maxColumns, maxRows, whitelist,
                new Configuration.DownloadSettings(32L * 1024 * 1024, 16_000_000, 10_000, 15_000, 60_000, 5));
    }

    public static MapStorage.Artwork generate(MapStorage storage, String url, float scale,
                                             int maxColumns, int maxRows, List<String> whitelist,
                                             Configuration.DownloadSettings limits) throws IOException {
        HttpImageDownloader.checkInterrupted();
        if (!Float.isFinite(scale) || scale <= 0) {
            throw new IOException(Language.toMapCommand.scaleMustBeNumber);
        }
        UrlWhitelist policy;
        URI uri;
        try {
            policy = UrlWhitelist.compile(whitelist);
            uri = UrlWhitelist.normalize(url);
        } catch (IllegalArgumentException e) {
            throw new IOException(Language.imageMapItem.readImageFailed, e);
        }
        if (!policy.allows(uri)) {
            throw new IOException(SafeMessageFormat.format(LOGGER, Language.imageMapItem.urlNotAllowed, url));
        }
        BufferedImage original;
        try (HttpImageDownloader.Download download = HttpImageDownloader.download(uri, policy, limits)) {
            original = decode(download.path(), scale, maxColumns, maxRows, limits.maxPixels());
        }
        HttpImageDownloader.checkInterrupted();
        int columns = (int) Math.ceil(Math.floor(original.getWidth() * (double) scale) / 128);
        int rows = (int) Math.ceil(Math.floor(original.getHeight() * (double) scale) / 128);
        BufferedImage resized = scale == 1 ? original : ImageUtils.resize(original, scale);
        HttpImageDownloader.checkInterrupted();
        BufferedImage centered = ImageUtils.center(resized, columns * 128, rows * 128);
        BufferedImage thumbnail = ImageUtils.thumb(original);
        HttpImageDownloader.checkInterrupted();
        // Once publication starts, return its result even if cancellation races it. The caller
        // can durably retain that artwork as pending delivery instead of losing its identity.
        return storage.create(original, centered, thumbnail);
    }

    private static BufferedImage decode(Path file, float scale, int maxColumns, int maxRows,
                                         long maxPixels) throws IOException {
        HttpImageDownloader.checkInterrupted();
        try (FileImageInputStream input = new FileImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException(Language.imageMapItem.readImageFailed);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int sourceWidth = reader.getWidth(0);
                int sourceHeight = reader.getHeight(0);
                if (sourceWidth <= 0 || sourceHeight <= 0 || (long) sourceWidth * sourceHeight > maxPixels) {
                    throw new IOException("Original image exceeds the pixel limit of " + maxPixels);
                }
                double width = Math.floor(sourceWidth * (double) scale);
                double height = Math.floor(sourceHeight * (double) scale);
                if (width < 1 || height < 1) {
                    throw new IOException(Language.toMapCommand.scaleMustBeNumber);
                }
                if (maxColumns <= 0 || maxRows <= 0 || width > (long) maxColumns * 128
                        || height > (long) maxRows * 128 || width > Integer.MAX_VALUE - 127L
                        || height > Integer.MAX_VALUE - 127L) {
                    throw new IOException(SafeMessageFormat.format(LOGGER, Language.imageMapItem.imageSizeNotAllowed,
                            (long) maxColumns * 128, (long) maxRows * 128));
                }
                HttpImageDownloader.checkInterrupted();
                reader.addIIOReadProgressListener(new InterruptibleDecode());
                BufferedImage image = reader.read(0);
                HttpImageDownloader.checkInterrupted();
                if (image == null || image.getWidth() != sourceWidth || image.getHeight() != sourceHeight) {
                    throw new IOException(Language.imageMapItem.readImageFailed);
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private static final class InterruptibleDecode implements IIOReadProgressListener {
        @Override public void imageProgress(ImageReader reader, float percentageDone) {
            if (Thread.currentThread().isInterrupted()) {
                reader.abort();
            }
        }
        @Override public void sequenceStarted(ImageReader source, int minIndex) { }
        @Override public void sequenceComplete(ImageReader source) { }
        @Override public void imageStarted(ImageReader source, int imageIndex) { }
        @Override public void imageComplete(ImageReader source) { }
        @Override public void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex) { }
        @Override public void thumbnailProgress(ImageReader source, float percentageDone) { }
        @Override public void thumbnailComplete(ImageReader source) { }
        @Override public void readAborted(ImageReader source) { }
    }
}
