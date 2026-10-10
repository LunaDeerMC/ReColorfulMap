package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Shared bounded pixels; pending and failed entries contain references only. */
final class ImageCache {
    private static final int MAX_READERS = 2;

    private record Key(MapStorage storage, ImageReference reference) { }

    private final int capacity;
    private final Consumer<Runnable> background;
    private final BiConsumer<ImageReference, Exception> onFailure;
    private final LinkedHashMap<Key, BufferedImage> images = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<Key> pending = new HashSet<>();
    private final Set<Key> failed = new HashSet<>();
    private final ArrayDeque<Key> queue = new ArrayDeque<>();
    private int readers;

    ImageCache(int capacity, Consumer<Runnable> background, BiConsumer<ImageReference, Exception> onFailure) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Image cache capacity must be positive");
        }
        this.capacity = capacity;
        this.background = Objects.requireNonNull(background);
        this.onFailure = Objects.requireNonNull(onFailure);
    }

    /** Returns immediately; aliases share both cached pixels and any outstanding read. */
    BufferedImage getOrRequest(MapStorage storage, ImageReference reference) {
        Key key = key(storage, reference);
        boolean startReader;
        synchronized (this) {
            BufferedImage image = images.get(key);
            if (image != null || failed.contains(key) || !pending.add(key)) {
                return image;
            }
            queue.addLast(key);
            startReader = readers < MAX_READERS;
            if (startReader) {
                readers++;
            }
        }
        if (startReader) {
            try {
                background.accept(this::readQueuedImages);
            } catch (RuntimeException exception) {
                // A disabled plugin cannot accept tasks. Do not retry every render tick.
                List<Key> rejected = new ArrayList<>();
                synchronized (this) {
                    readers--;
                    while (!queue.isEmpty()) {
                        Key queued = queue.removeFirst();
                        pending.remove(queued);
                        if (!images.containsKey(queued) && failed.add(queued)) {
                            rejected.add(queued);
                        }
                    }
                }
                rejected.forEach(rejectedKey -> onFailure.accept(rejectedKey.reference(), exception));
            }
        }
        return null;
    }

    /** Preloaded pixels follow exactly the same capacity limit as background reads. */
    synchronized void put(MapStorage storage, ImageReference reference, BufferedImage image) {
        put(key(storage, reference), Objects.requireNonNull(image));
    }

    private void put(Key key, BufferedImage image) {
        images.put(key, image);
        failed.remove(key);
        while (images.size() > capacity) {
            images.remove(images.keySet().iterator().next());
        }
    }

    private void readQueuedImages() {
        while (true) {
            Key key;
            synchronized (this) {
                key = queue.pollFirst();
                if (key == null) {
                    readers--;
                    return;
                }
                if (images.containsKey(key)) {
                    pending.remove(key);
                    continue;
                }
            }
            try {
                BufferedImage image = key.storage().read(key.reference());
                synchronized (this) {
                    put(key, image);
                    pending.remove(key);
                }
            } catch (Exception exception) {
                boolean report;
                synchronized (this) {
                    pending.remove(key);
                    report = !images.containsKey(key) && failed.add(key);
                }
                if (report) {
                    onFailure.accept(key.reference(), exception);
                }
            }
        }
    }

    private static Key key(MapStorage storage, ImageReference reference) {
        return new Key(Objects.requireNonNull(storage), Objects.requireNonNull(reference));
    }
}
