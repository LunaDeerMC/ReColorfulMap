package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

public class ImageRenderer extends MapRenderer {
    public static class ImageRendererText extends ConfigurationPart {
        public String failToRender = "Failed to render image: %s";
        public String imageTooLarge = "image (%d x %d) is too large.";
    }

    // 128 RGBA map images use about 8 MiB; map aliases share the same cache entry.
    private static final ImageCache IMAGES = new ImageCache(128, Scheduler::runTaskAsync,
            (reference, error) -> XLogger.warn("Cannot load map image %s: %s", reference.path(), error.getMessage()));

    // Compatibility-only constructor. Production renderers hold file references, not pixels.
    private final BufferedImage directImage;
    private final MapStorage storage;
    private final ImageReference reference;
    private final Set<MapCanvas> rendered = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<MapCanvas> cleared = Collections.newSetFromMap(new WeakHashMap<>());

    public ImageRenderer(BufferedImage image) {
        this.directImage = Objects.requireNonNull(image);
        this.storage = null;
        this.reference = null;
    }

    /** Attaching this renderer does not open or decode a PNG. */
    public ImageRenderer(MapStorage storage, ImageReference reference) {
        this.directImage = null;
        this.storage = Objects.requireNonNull(storage);
        this.reference = Objects.requireNonNull(reference);
    }

    /** Reuse already validated pixels without keeping another permanent per-map copy. */
    public ImageRenderer(MapStorage storage, ImageReference reference, BufferedImage image) {
        this(storage, reference);
        IMAGES.put(storage, reference, image);
    }

    public boolean matches(MapStorage store, ImageReference image) {
        return storage == store && Objects.equals(reference, image);
    }

    @Override
    public synchronized void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
        if (storage != null && storage.isDeleted(reference.artwork())) {
            IMAGES.invalidate(storage, reference.artwork());
            if (cleared.add(canvas)) {
                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        canvas.setPixel(x, y, (byte) 0);
                    }
                }
            }
            return;
        }
        if (rendered.contains(canvas)) {
            return;
        }
        BufferedImage image = directImage != null ? directImage : IMAGES.getOrRequest(storage, reference);
        if (image == null) {
            return;
        }
        canvas.drawImage((128 - image.getWidth()) / 2, (128 - image.getHeight()) / 2, image);
        rendered.add(canvas);
    }

    public static MapMeta renderOnMeta(MapMeta meta, BufferedImage image) throws Exception {
        return renderOnMeta(meta, new ImageRenderer(image));
    }

    public static MapMeta renderOnMeta(MapMeta meta, BufferedImage image, MapStorage storage, ImageReference reference) throws Exception {
        return renderOnMeta(meta, new ImageRenderer(storage, reference, image));
    }

    private static MapMeta renderOnMeta(MapMeta meta, ImageRenderer renderer) throws Exception {
        MapView view = meta.getMapView();
        if (view == null) {
            throw new Exception("MapMeta does not have a MapView.");
        }
        for (MapRenderer existing : view.getRenderers()) {
            view.removeRenderer(existing);
        }
        view.addRenderer(renderer);
        meta.setMapView(view);
        return meta;
    }
}
