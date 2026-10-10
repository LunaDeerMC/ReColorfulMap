package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Shared pixels with bounded readers, requests and failures; render callbacks never wait for disk. */
final class ImageCache {
    private static final int MAX_READERS = 2;
    private static final int MAX_PENDING = 1024;
    private static final int MAX_FAILURES = 1024;
    private static final long REQUEST_LIFETIME = TimeUnit.SECONDS.toNanos(30);

    private record Key(MapStorage storage, ImageReference reference) { }

    private static final class Request {
        final Key key;
        long touched;

        Request(Key key, long touched) {
            this.key = key;
            this.touched = touched;
        }
    }

    /** A queued scheduler task can be cancelled without ever entering its runnable. */
    private static final class Reader {
        boolean started;
        boolean retired;
    }

    private static final class Pixels {
        final BufferedImage image;
        final Request request;
        boolean consumed;

        Pixels(BufferedImage image, Request request, boolean consumed) {
            this.image = image;
            this.request = request;
            this.consumed = consumed;
        }
    }

    private final int capacity;
    private final Consumer<Runnable> background;
    private final BiConsumer<ImageReference, Exception> onFailure;
    private final LongSupplier clock;
    private final LinkedHashMap<Key, Pixels> images = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Key, Request> pending = new LinkedHashMap<>();
    private final LinkedHashMap<Key, Boolean> failed = new LinkedHashMap<>(16, 0.75f, true);
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Set<Reader> readers = new HashSet<>();
    private int reservations;
    private long lastPrune;

    ImageCache(int capacity, Consumer<Runnable> background, BiConsumer<ImageReference, Exception> onFailure) {
        this(capacity, background, onFailure, System::nanoTime);
    }

    ImageCache(int capacity, Consumer<Runnable> background, BiConsumer<ImageReference, Exception> onFailure,
               LongSupplier clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Image cache capacity must be positive");
        }
        this.capacity = capacity;
        this.background = Objects.requireNonNull(background);
        this.onFailure = Objects.requireNonNull(onFailure);
        this.clock = Objects.requireNonNull(clock);
    }

    /** A read result stays reserved until a render callback actually receives it. */
    BufferedImage getOrRequest(MapStorage storage, ImageReference reference) {
        if (storage.isDeleted(reference.artwork())) {
            invalidate(storage, reference.artwork());
            return null;
        }
        Key key = key(storage, reference);
        BufferedImage image = null;
        synchronized (this) {
            long now = clock.getAsLong();
            Pixels pixels = images.get(key);
            if (pixels != null) {
                pixels.consumed = true;
                pixels.request.touched = now;
                image = pixels.image;
            } else if (failed.get(key) == null) {
                Request request = pending.get(key);
                if (request != null) {
                    request.touched = now;
                } else {
                    discardAbandonedRequests(now);
                    if (pending.size() < MAX_PENDING) {
                        request = new Request(key, now);
                        pending.put(key, request);
                        queue.addLast(request);
                    }
                    // A full queue needs no additional bookkeeping: the next render retries.
                }
            }
        }
        startReaders();
        return image;
    }

    /** Preloaded thumbnails are optional cache entries and never displace unread results. */
    synchronized void put(MapStorage storage, ImageReference reference, BufferedImage image) {
        Objects.requireNonNull(image);
        Key key = key(storage, reference);
        if (storage.isDeleted(reference.artwork())) {
            return;
        }
        Pixels existing = images.get(key);
        if (existing != null) {
            return;
        }
        if (!makeRoom(clock.getAsLong())) {
            return;
        }
        images.put(key, new Pixels(image, new Request(key, clock.getAsLong()), true));
        failed.remove(key);
    }

    void invalidate(MapStorage storage, UUID artwork) {
        synchronized (this) {
            images.keySet().removeIf(key -> matches(key, storage, artwork));
            failed.keySet().removeIf(key -> matches(key, storage, artwork));
            queue.removeIf(request -> matches(request.key, storage, artwork));
            pending.keySet().removeIf(key -> matches(key, storage, artwork));
            // A running read owns its reservation until it finishes. Its request identity
            // is no longer in pending, so it cannot republish an invalidated result.
        }
        startReaders();
    }

    /** Remove every reference owned by one plugin instance, including in-flight publication rights. */
    void clear(MapStorage storage) {
        synchronized (this) {
            images.keySet().removeIf(key -> key.storage() == storage);
            failed.keySet().removeIf(key -> key.storage() == storage);
            queue.removeIf(request -> request.key.storage() == storage);
            pending.keySet().removeIf(key -> key.storage() == storage);
            // Plugin shutdown cancels scheduler tasks without calling them. Revoke
            // their leases now; late callbacks recognize that they no longer own a slot.
            readers.forEach(reader -> reader.retired = true);
            readers.removeIf(reader -> !reader.started);
        }
        startReaders();
    }

    private static boolean matches(Key key, MapStorage storage, UUID artwork) {
        return key.storage() == storage && key.reference().artwork().equals(artwork);
    }

    private void startReaders() {
        List<Reader> scheduled = new ArrayList<>();
        synchronized (this) {
            discardAbandonedRequests(clock.getAsLong());
            int start = hasRoom(clock.getAsLong())
                    ? Math.min(MAX_READERS - readers.size(), queue.size() - readers.size()) : 0;
            for (int index = 0; index < start; index++) {
                Reader reader = new Reader();
                readers.add(reader);
                scheduled.add(reader);
            }
        }
        for (Reader reader : scheduled) {
            try {
                background.accept(() -> readQueuedImages(reader));
            } catch (RuntimeException exception) {
                List<Key> rejected = new ArrayList<>();
                synchronized (this) {
                    if (!readers.remove(reader)) {
                        continue; // A shutdown already revoked this task; leave later requests alone.
                    }
                    while (!queue.isEmpty()) {
                        Request request = queue.removeFirst();
                        pending.remove(request.key, request);
                        if (!images.containsKey(request.key) && rememberFailure(request.key)) {
                            rejected.add(request.key);
                        }
                    }
                }
                rejected.forEach(key -> onFailure.accept(key.reference(), exception));
            }
        }
    }

    private void readQueuedImages(Reader reader) {
        synchronized (this) {
            if (!readers.contains(reader)) {
                return;
            }
            reader.started = true;
        }
        try {
            while (true) {
                Request request;
                synchronized (this) {
                    if (reader.retired) {
                        return;
                    }
                    discardAbandonedRequests(clock.getAsLong());
                    request = queue.peekFirst();
                    if (request == null) {
                        return;
                    }
                    if (images.containsKey(request.key)) {
                        queue.removeFirst();
                        pending.remove(request.key, request);
                        continue;
                    }
                    if (!makeRoom(clock.getAsLong())) {
                        return;
                    }
                    queue.removeFirst();
                    reservations++;
                }
                BufferedImage image = null;
                Exception failure = null;
                try {
                    image = request.key.storage().read(request.key.reference());
                } catch (Exception exception) {
                    failure = exception;
                }
                boolean report = false;
                synchronized (this) {
                    reservations--;
                    if (pending.remove(request.key, request)
                            && !request.key.storage().isDeleted(request.key.reference().artwork())
                            && !images.containsKey(request.key)) {
                        if (failure == null) {
                            images.put(request.key, new Pixels(image, request, false));
                            failed.remove(request.key);
                        } else {
                            report = rememberFailure(request.key);
                        }
                    }
                }
                if (report) {
                    onFailure.accept(request.key.reference(), failure);
                }
            }
        } finally {
            synchronized (this) {
                readers.remove(reader);
            }
            startReaders();
        }
    }

    private boolean rememberFailure(Key key) {
        boolean first = failed.put(key, Boolean.TRUE) == null;
        while (failed.size() > MAX_FAILURES) {
            failed.remove(failed.keySet().iterator().next());
        }
        return first;
    }

    private void discardAbandonedRequests(long now) {
        if (now - lastPrune < TimeUnit.SECONDS.toNanos(1)) {
            return;
        }
        lastPrune = now;
        queue.removeIf(request -> {
            if (now - request.touched < REQUEST_LIFETIME) {
                return false;
            }
            pending.remove(request.key, request);
            return true;
        });
    }

    private boolean hasRoom(long now) {
        if (images.size() + reservations < capacity) {
            return true;
        }
        return images.values().stream().anyMatch(pixels -> pixels.consumed
                || now - pixels.request.touched >= REQUEST_LIFETIME);
    }

    private boolean makeRoom(long now) {
        if (images.size() + reservations < capacity) {
            return true;
        }
        Iterator<Pixels> iterator = images.values().iterator();
        while (iterator.hasNext()) {
            Pixels pixels = iterator.next();
            if (pixels.consumed || now - pixels.request.touched >= REQUEST_LIFETIME) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static Key key(MapStorage storage, ImageReference reference) {
        return new Key(Objects.requireNonNull(storage), Objects.requireNonNull(reference));
    }
}
