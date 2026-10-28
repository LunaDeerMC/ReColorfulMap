package cn.lunadeer.reColorfulMap.storage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable PNG files in maps/&lt;artwork UUID&gt;/. Each map item carries its own
 * relative image path; there is no map-ID index or image data in PDC.
 */
public final class MapStorage {
    private static final int TILE_SIZE = 128;
    private static final Pattern TILE_NAME = Pattern.compile("(0|[1-9][0-9]*)_(0|[1-9][0-9]*)\\.png");
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern IMAGE_PATH = Pattern.compile("maps/(" + UUID_PATTERN + ")/(thumb\\.png|(?:0|[1-9][0-9]*)_(?:0|[1-9][0-9]*)\\.png)");
    // This namespace is reserved for this implementation, never legacy artwork directories.
    private static final String TEMP_PREFIX = ".recolorfulmap-";
    private static final Pattern TEMP_NAME = Pattern.compile("\\.recolorfulmap-(?:artwork|delete)-" + UUID_PATTERN + "\\.tmp");

    private final Path mapsDirectory;
    private final Set<Path> activeStaging = ConcurrentHashMap.newKeySet();
    private final Set<UUID> deletedArtworks = ConcurrentHashMap.newKeySet();
    private final Set<ImageReference> deletedImages = ConcurrentHashMap.newKeySet();
    private static final String INDEPENDENT_FILE = ".independent-artwork";
    // Dimensions are immutable for published artwork. Bound metadata independently of pixels.
    private final Map<UUID, Artwork> artworks = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<UUID, FileTime> artworkDirectoryTimes = new java.util.HashMap<>();
    private static final int MAX_CACHED_ARTWORKS = 4096;
    private final Set<UUID> validatedArtworks = ConcurrentHashMap.newKeySet();

    /** Tile coordinates start at the top left, as in ColorfulMap 2.3. */
    public record ImageReference(UUID artwork, int x, int y) {
        public ImageReference {
            Objects.requireNonNull(artwork, "artwork");
            if (!(x == -1 && y == -1) && (x < 0 || y < 0)) {
                throw new IllegalArgumentException("Invalid image coordinates");
            }
        }

        public boolean thumbnail() {
            return x == -1;
        }

        public String path() {
            return "maps/" + artwork + "/" + (thumbnail() ? "thumb.png" : x + "_" + y + ".png");
        }

        /** Accepts only canonical relative paths, never absolute paths or traversal. */
        public static Optional<ImageReference> parse(String path) {
            if (path == null) {
                return Optional.empty();
            }
            Matcher matcher = IMAGE_PATH.matcher(path);
            if (!matcher.matches()) {
                return Optional.empty();
            }
            try {
                UUID id = UUID.fromString(matcher.group(1));
                if (matcher.group(2).equals("thumb.png")) {
                    return Optional.of(thumbnail(id));
                }
                Matcher tile = TILE_NAME.matcher(matcher.group(2));
                if (!tile.matches()) {
                    return Optional.empty();
                }
                return Optional.of(tile(id, Integer.parseInt(tile.group(1)), Integer.parseInt(tile.group(2))));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }

        public static ImageReference thumbnail(UUID artwork) {
            return new ImageReference(artwork, -1, -1);
        }

        public static ImageReference tile(UUID artwork, int x, int y) {
            if (x < 0 || y < 0) {
                throw new IllegalArgumentException("Negative tile coordinates");
            }
            return new ImageReference(artwork, x, y);
        }
    }

    public record Artwork(UUID id, int columns, int rows) {
        public Artwork {
            Objects.requireNonNull(id, "id");
            if (columns <= 0 || rows <= 0) {
                throw new IllegalArgumentException("Invalid artwork dimensions");
            }
        }
    }

    public MapStorage(Path pluginDataDirectory) throws IOException {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        Files.createDirectories(pluginDataDirectory);
        mapsDirectory = pluginDataDirectory.toRealPath().resolve("maps");
        if (Files.isSymbolicLink(mapsDirectory)) {
            throw new IOException("The maps directory must not be a symbolic link: " + mapsDirectory);
        }
        Files.createDirectories(mapsDirectory);
        for (String name : new String[]{"maps.yml", "maps.yaml"}) {
            Path legacyIndex = mapsDirectory.resolve(name);
            if (Files.exists(legacyIndex, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Legacy map index found: " + legacyIndex
                        + ". Stop the server and run the offline migration script before starting ReColorfulMap.");
            }
        }
    }

    /** Writes every file into a private directory, then exposes the complete artwork atomically. */
    public Artwork create(BufferedImage original, BufferedImage centered, BufferedImage thumbnail) throws IOException {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(centered, "centered");
        Objects.requireNonNull(thumbnail, "thumbnail");
        if (centered.getWidth() % TILE_SIZE != 0 || centered.getHeight() % TILE_SIZE != 0) {
            throw new IOException("Centered image dimensions must be multiples of 128");
        }
        validateMapDimensions(thumbnail.getWidth(), thumbnail.getHeight());
        checkMapsDirectory();
        UUID id = UUID.randomUUID();
        Path target = mapsDirectory.resolve(id.toString());
        Path temporary = mapsDirectory.resolve(TEMP_PREFIX + "artwork-" + id + ".tmp");
        // Publish ownership before the directory exists so cleanup can never mistake
        // a slow image encoder's work for an abandoned write.
        activeStaging.add(temporary);
        boolean created = false;
        try {
            Files.createDirectory(temporary);
            created = true;
            writePng(original, temporary.resolve("raw.png"));
            writePng(thumbnail, temporary.resolve("thumb.png"));
            int columns = centered.getWidth() / TILE_SIZE;
            int rows = centered.getHeight() / TILE_SIZE;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < columns; x++) {
                    writePng(centered.getSubimage(x * TILE_SIZE, y * TILE_SIZE, TILE_SIZE, TILE_SIZE),
                            temporary.resolve(x + "_" + y + ".png"));
                }
            }
            writeIndependentMarker(temporary, id, columns, rows);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return cacheArtwork(new Artwork(id, columns, rows));
        } finally {
            try {
                // Any failed write leaves the published artwork directories untouched.
                if (created) {
                    deleteTemporaryQuietly(temporary);
                }
            } finally {
                activeStaging.remove(temporary);
            }
        }
    }

    /**
     * Gives a duplicate its own complete directory and identity. Run this on a disk worker,
     * before exposing the new item. The source cannot be deleted halfway through the copy.
     * Files are copied, never linked, so deleting either artwork leaves the other intact.
     */
    public synchronized Artwork copyArtwork(UUID sourceId) throws IOException {
        Artwork source = artwork(sourceId);
        validateArtwork(source);
        Path sourceDirectory = artworkDirectory(sourceId);
        Path original = sourceDirectory.resolve("raw.png");
        boolean hasOriginal = Files.exists(original, LinkOption.NOFOLLOW_LINKS);
        if (hasOriginal) requireRegularFile(original);
        UUID id = UUID.randomUUID();
        Path target = mapsDirectory.resolve(id.toString());
        Path temporary = mapsDirectory.resolve(TEMP_PREFIX + "artwork-" + id + ".tmp");
        activeStaging.add(temporary);
        boolean created = false;
        try {
            Files.createDirectory(temporary);
            created = true;
            if (hasOriginal) copyImage(original, temporary.resolve("raw.png"));
            copyImage(sourceDirectory.resolve("thumb.png"), temporary.resolve("thumb.png"));
            for (int y = 0; y < source.rows(); y++) {
                for (int x = 0; x < source.columns(); x++) {
                    String name = x + "_" + y + ".png";
                    copyImage(sourceDirectory.resolve(name), temporary.resolve(name));
                }
            }
            writeIndependentMarker(temporary, id, source.columns(), source.rows());
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return cacheArtwork(new Artwork(id, source.columns(), source.rows()));
        } finally {
            try {
                if (created) deleteTemporaryQuietly(temporary);
            } finally {
                activeStaging.remove(temporary);
            }
        }
    }

    /** A copied loose tile is a complete independent 1x1 artwork, even if its old wall is damaged. */
    public synchronized Artwork copyImage(ImageReference reference) throws IOException {
        if (reference.thumbnail()) return copyArtwork(reference.artwork());
        read(reference); // Validate just the requested tile; unrelated missing pieces do not matter.
        Path source = artworkDirectory(reference.artwork()).resolve(reference.x() + "_" + reference.y() + ".png");
        UUID id = UUID.randomUUID();
        Path temporary = mapsDirectory.resolve(TEMP_PREFIX + "artwork-" + id + ".tmp");
        Path target = mapsDirectory.resolve(id.toString());
        activeStaging.add(temporary);
        boolean created = false;
        try {
            Files.createDirectory(temporary);
            created = true;
            for (String name : new String[]{"raw.png", "thumb.png", "0_0.png"}) {
                copyImage(source, temporary.resolve(name));
            }
            writeIndependentMarker(temporary, id, 1, 1);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return cacheArtwork(new Artwork(id, 1, 1));
        } finally {
            try {
                if (created) deleteTemporaryQuietly(temporary);
            } finally {
                activeStaging.remove(temporary);
            }
        }
    }

    private static void copyImage(Path source, Path target) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("Artwork copy was interrupted");
        }
        requireRegularFile(source);
        Files.copy(source, target);
    }

    private static void writeIndependentMarker(Path directory, UUID id, int columns, int rows) throws IOException {
        Files.writeString(directory.resolve(INDEPENDENT_FILE), "version=1\nid=" + id
                + "\ncolumns=" + columns + "\nrows=" + rows + "\n");
    }

    private Optional<Artwork> independentArtwork(Path directory, UUID id) throws IOException {
        Path marker = directory.resolve(INDEPENDENT_FILE);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        requireRegularFile(marker);
        if (Files.size(marker) > 1024) throw new IOException("Invalid artwork ownership marker");
        java.util.Properties properties = new java.util.Properties();
        try (var reader = Files.newBufferedReader(marker)) { properties.load(reader); }
        try {
            if (!"1".equals(properties.getProperty("version")) || !id.toString().equals(properties.getProperty("id"))) {
                throw new IllegalArgumentException("Wrong artwork ownership marker identity");
            }
            return Optional.of(new Artwork(id, Integer.parseInt(properties.getProperty("columns")),
                    Integer.parseInt(properties.getProperty("rows"))));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid artwork ownership marker", failure);
        }
    }

    /** Infers the matrix from actual tile names; legacy meta.txt is only an operation log. */
    public Artwork artwork(UUID id) throws IOException {
        if (isDeleted(id)) {
            throw new IOException("Artwork was deleted: " + id);
        }
        Path directory = artworkDirectory(id);
        FileTime modified = Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS);
        synchronized (artworks) {
            Artwork cached = artworks.get(id);
            if (cached != null && modified.equals(artworkDirectoryTimes.get(id))) {
                return cached;
            }
            // A file added/removed since validation must not be hidden by cached dimensions.
            artworks.remove(id);
            artworkDirectoryTimes.remove(id);
            validatedArtworks.remove(id);
        }
        int maxX = -1;
        int maxY = -1;
        long count = 0;
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                Matcher matcher = TILE_NAME.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                requireRegularFile(file);
                try {
                    maxX = Math.max(maxX, Integer.parseInt(matcher.group(1)));
                    maxY = Math.max(maxY, Integer.parseInt(matcher.group(2)));
                } catch (NumberFormatException exception) {
                    throw new IOException("Invalid tile coordinates: " + file, exception);
                }
                count++;
            }
        }
        if (maxX < 0 || maxY < 0 || maxX == Integer.MAX_VALUE || maxY == Integer.MAX_VALUE
                || count != ((long) maxX + 1) * ((long) maxY + 1)) {
            throw new IOException("Incomplete tile matrix: " + directory);
        }
        Optional<Artwork> independent = independentArtwork(directory, id);
        if (independent.isPresent() && (independent.get().columns() != maxX + 1 || independent.get().rows() != maxY + 1)) {
            throw new IOException("Incomplete independent artwork matrix: " + directory);
        }
        requireRegularFile(directory.resolve("thumb.png"));
        return cacheArtwork(new Artwork(id, maxX + 1, maxY + 1));
    }

    /** In-memory lookup only. Event handlers must not scan image directories. */
    public Optional<Artwork> cachedArtwork(UUID id) {
        synchronized (artworks) {
            return deletedArtworks.contains(id) ? Optional.empty() : Optional.ofNullable(artworks.get(id));
        }
    }

    private Artwork cacheArtwork(Artwork artwork) throws IOException {
        FileTime modified = Files.getLastModifiedTime(artworkDirectory(artwork.id()), LinkOption.NOFOLLOW_LINKS);
        synchronized (artworks) {
            if (deletedArtworks.contains(artwork.id())) {
                throw new IOException("Artwork was deleted: " + artwork.id());
            }
            artworks.put(artwork.id(), artwork);
            artworkDirectoryTimes.put(artwork.id(), modified);
            while (artworks.size() > MAX_CACHED_ARTWORKS) {
                UUID oldest = artworks.keySet().iterator().next();
                artworks.remove(oldest);
                artworkDirectoryTimes.remove(oldest);
                validatedArtworks.remove(oldest);
            }
        }
        return artwork;
    }

    /**
     * Full corruption check off the server thread. Discard each decoded tile before reading the next;
     * immutable published files need this check only once per bounded metadata-cache lifetime.
     */
    public void validateArtwork(Artwork artwork) throws IOException {
        if (isDeleted(artwork.id())) {
            throw new IOException("Artwork was deleted: " + artwork.id());
        }
        if (validatedArtworks.contains(artwork.id())) {
            return;
        }
        read(ImageReference.thumbnail(artwork.id()));
        for (int x = 0; x < artwork.columns(); x++) {
            for (int y = 0; y < artwork.rows(); y++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("Artwork validation was interrupted");
                }
                read(ImageReference.tile(artwork.id(), x, y));
            }
        }
        synchronized (artworks) {
            if (isDeleted(artwork.id())) {
                throw new IOException("Artwork was deleted: " + artwork.id());
            }
            if (artworks.containsKey(artwork.id())) {
                validatedArtworks.add(artwork.id());
            }
        }
    }

    /** Checks image dimensions before decoding pixels to avoid loading oversized map images. */
    public BufferedImage read(ImageReference reference) throws IOException {
        Objects.requireNonNull(reference, "reference");
        Path directory = artworkDirectory(reference.artwork());
        Path file = directory.resolve(reference.thumbnail() ? "thumb.png" : reference.x() + "_" + reference.y() + ".png");
        requireRegularFile(file);
        try (InputStream input = Files.newInputStream(file);
             ImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IOException("Invalid PNG image: " + file);
            }
            ImageReader reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase("png")) {
                    throw new IOException("Not a PNG image: " + file);
                }
                reader.setInput(imageInput, true, true);
                validateMapDimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("Invalid PNG image: " + file);
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Unpublishes the directory atomically before removing its contents. The caller
     * decides when all items referencing the artwork should become invalid.
     */
    public synchronized boolean deleteArtwork(UUID id) throws IOException {
        Objects.requireNonNull(id, "id");
        checkMapsDirectory();
        Path directory = mapsDirectory.resolve(id.toString());
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe artwork directory: " + directory);
        }
        Path temporary = mapsDirectory.resolve(TEMP_PREFIX + "delete-" + UUID.randomUUID() + ".tmp");
        activeStaging.add(temporary);
        try {
            Files.move(directory, temporary, StandardCopyOption.ATOMIC_MOVE);
            deletedArtworks.add(id);
            synchronized (artworks) {
                artworks.remove(id);
                artworkDirectoryTimes.remove(id);
                validatedArtworks.remove(id);
            }
            // walkFileTree does not follow symbolic links, including any stored inside this directory.
            deleteTree(temporary);
            return true;
        } finally {
            // If removal fails, cleanup can retry the already unpublished directory.
            activeStaging.remove(temporary);
        }
    }

    /**
     * Automatic item cleanup only owns data with an explicit independent-artwork marker.
     * Old/migrated shared directories are retained until an offline separation establishes ownership.
     * A loose tile owns only its tile file; other pieces of its wall must remain readable.
     */
    public synchronized boolean deleteImage(ImageReference reference) throws IOException {
        if (isDeleted(reference.artwork())) return false;
        Path directory = artworkDirectory(reference.artwork());
        if (independentArtwork(directory, reference.artwork()).isEmpty()) return false;
        if (reference.thumbnail()) return deleteArtwork(reference.artwork());
        Path file = directory.resolve(reference.x() + "_" + reference.y() + ".png");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        requireRegularFile(file);
        Files.delete(file);
        deletedImages.add(reference);
        boolean tilesRemain = false;
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path remaining : files) {
                if (TILE_NAME.matcher(remaining.getFileName().toString()).matches()) {
                    tilesRemain = true;
                    break;
                }
            }
        }
        if (!tilesRemain) return deleteArtwork(reference.artwork());
        synchronized (artworks) {
            artworks.remove(reference.artwork());
            artworkDirectoryTimes.remove(reference.artwork());
            validatedArtworks.remove(reference.artwork());
        }
        return true;
    }

    public boolean isDeleted(ImageReference reference) {
        return isDeleted(reference.artwork()) || deletedImages.contains(reference);
    }

    /** Remembers deletion for this process so decoded image caches can invalidate their entries. */
    public boolean isDeleted(UUID id) {
        return deletedArtworks.contains(Objects.requireNonNull(id, "id"));
    }

    /** Deletes only abandoned private staging files, never published artwork directories. */
    public synchronized int cleanTemporaryFiles() throws IOException {
        checkMapsDirectory();
        int cleaned = 0;
        try (var files = Files.newDirectoryStream(mapsDirectory)) {
            for (Path file : files) {
                if (TEMP_NAME.matcher(file.getFileName().toString()).matches()
                        && !activeStaging.contains(file)
                        && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    deleteTree(file);
                    cleaned++;
                }
            }
        }
        return cleaned;
    }

    private Path artworkDirectory(UUID id) throws IOException {
        Objects.requireNonNull(id, "id");
        checkMapsDirectory();
        Path directory = mapsDirectory.resolve(id.toString());
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing or unsafe artwork directory: " + directory);
        }
        return directory;
    }

    private void checkMapsDirectory() throws IOException {
        if (!Files.isDirectory(mapsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing or unsafe maps directory: " + mapsDirectory);
        }
    }

    private static void requireRegularFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing or unsafe image file: " + file);
        }
    }

    private static void validateMapDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > TILE_SIZE || height > TILE_SIZE) {
            throw new IOException("Map image dimensions must be between 1 and 128 pixels: " + width + "x" + height);
        }
    }

    private static void writePng(BufferedImage image, Path file) throws IOException {
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("PNG encoder unavailable: " + file);
        }
    }

    private static void deleteTemporaryQuietly(Path path) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(path);
            }
        } catch (IOException ignored) {
            // Keep the original write failure; /cleanColorfulMap can retry abandoned staging files.
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
