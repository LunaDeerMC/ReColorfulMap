package cn.lunadeer.reColorfulMap.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ColorfulMap 2.3's on-disk format: maps/maps.yml indexes immutable PNG files in
 * maps/&lt;artwork UUID&gt;/. Only references are kept in memory; image pixels never
 * belong in a world's or an item's persistent data container.
 */
public final class MapStorage {
    private static final int TILE_SIZE = 128;
    private static final Pattern TILE_NAME = Pattern.compile("(0|[1-9][0-9]*)_(0|[1-9][0-9]*)\\.png");
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern IMAGE_PATH = Pattern.compile("maps/(" + UUID_PATTERN + ")/(thumb\\.png|(?:0|[1-9][0-9]*)_(?:0|[1-9][0-9]*)\\.png)");
    // This namespace is reserved for this implementation, never legacy artwork directories.
    private static final String TEMP_PREFIX = ".recolorfulmap-";
    private static final Pattern TEMP_NAME = Pattern.compile("\\.recolorfulmap-(?:artwork|index)-" + UUID_PATTERN + "\\.tmp");

    private final Path mapsDirectory;
    private final Path indexFile;
    private final Set<Path> activeStaging = ConcurrentHashMap.newKeySet();
    private volatile Map<MapKey, ImageReference> index;

    public record MapKey(String world, int id) {
        public MapKey {
            Objects.requireNonNull(world, "world");
            if (world.isEmpty() || world.indexOf('\0') >= 0 || id < 0) {
                throw new IllegalArgumentException("Invalid world or map ID");
            }
        }
    }

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
        indexFile = mapsDirectory.resolve("maps.yml");
        index = Map.copyOf(loadIndex());
    }

    public Optional<ImageReference> find(String world, int mapId) {
        return Optional.ofNullable(index.get(new MapKey(world, mapId)));
    }

    /**
     * Persists a complete batch before publishing it to readers. Existing aliases
     * remain valid forever, including aliases used by cloned items or other worlds.
     */
    public synchronized void register(Map<MapKey, ImageReference> additions) throws IOException {
        Objects.requireNonNull(additions, "additions");
        Map<MapKey, ImageReference> updated = new HashMap<>(index);
        for (Map.Entry<MapKey, ImageReference> entry : additions.entrySet()) {
            MapKey key = Objects.requireNonNull(entry.getKey(), "map key");
            ImageReference reference = Objects.requireNonNull(entry.getValue(), "image reference");
            ImageReference existing = updated.putIfAbsent(key, reference);
            if (existing != null && !existing.equals(reference)) {
                throw new IOException("Map " + key.world() + "/" + key.id() + " already refers to " + existing.path());
            }
        }
        if (updated.equals(index)) {
            return;
        }
        Map<MapKey, ImageReference> snapshot = Map.copyOf(updated);
        persistIndex(snapshot);
        index = snapshot;
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
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return new Artwork(id, columns, rows);
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

    /** Infers the matrix from actual tile names; legacy meta.txt is only an operation log. */
    public Artwork artwork(UUID id) throws IOException {
        Path directory = artworkDirectory(id);
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
        requireRegularFile(directory.resolve("thumb.png"));
        return new Artwork(id, maxX + 1, maxY + 1);
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

    /** Deletes only abandoned private staging files, never artwork or map-ID aliases. */
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

    public int size() {
        return index.size();
    }

    private Map<MapKey, ImageReference> loadIndex() throws IOException {
        if (!Files.exists(indexFile, LinkOption.NOFOLLOW_LINKS)) {
            return new HashMap<>();
        }
        requireRegularFile(indexFile);
        YamlConfiguration yaml = newYaml();
        try {
            yaml.loadFromString(Files.readString(indexFile, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid map index: " + indexFile, exception);
        }
        Map<MapKey, ImageReference> loaded = new HashMap<>();
        for (String world : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(world);
            if (section == null) {
                throw new IOException("Expected a world section in map index: " + world);
            }
            for (String id : section.getKeys(false)) {
                Object value = section.get(id);
                if (!(value instanceof String path)) {
                    throw new IOException("Invalid image path for " + world + "/" + id);
                }
                try {
                    MapKey key = new MapKey(world, Integer.parseInt(id));
                    ImageReference reference = parseReference(path);
                    if (loaded.putIfAbsent(key, reference) != null) {
                        throw new IOException("Duplicate map ID: " + world + "/" + id);
                    }
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid map index entry: " + world + "/" + id, exception);
                }
            }
        }
        return loaded;
    }

    private static ImageReference parseReference(String path) throws IOException {
        Matcher matcher = IMAGE_PATH.matcher(path);
        if (!matcher.matches()) {
            throw new IOException("Unsupported map image path: " + path);
        }
        UUID id = UUID.fromString(matcher.group(1));
        if (matcher.group(2).equals("thumb.png")) {
            return ImageReference.thumbnail(id);
        }
        Matcher tile = TILE_NAME.matcher(matcher.group(2));
        if (!tile.matches()) {
            throw new IOException("Invalid tile path: " + path);
        }
        return ImageReference.tile(id, Integer.parseInt(tile.group(1)), Integer.parseInt(tile.group(2)));
    }

    private void persistIndex(Map<MapKey, ImageReference> updated) throws IOException {
        checkMapsDirectory();
        if (Files.exists(indexFile, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(indexFile);
        }
        YamlConfiguration yaml = newYaml();
        Map<String, Map<Integer, ImageReference>> worlds = new TreeMap<>();
        updated.forEach((key, value) -> worlds.computeIfAbsent(key.world(), ignored -> new TreeMap<>()).put(key.id(), value));
        worlds.forEach((world, images) -> {
            ConfigurationSection section = yaml.createSection(world);
            images.forEach((id, reference) -> section.set(Integer.toString(id), reference.path()));
        });
        Path temporary = mapsDirectory.resolve(TEMP_PREFIX + "index-" + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            // Never fall back to a non-atomic replacement: failure must preserve the old index.
            Files.move(temporary, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteTemporaryQuietly(temporary);
        }
    }

    private static YamlConfiguration newYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        // World names may contain dots. They are literal top-level YAML keys.
        yaml.options().pathSeparator('\0');
        return yaml;
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
            throw new IOException("Missing or unsafe image/index file: " + file);
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
