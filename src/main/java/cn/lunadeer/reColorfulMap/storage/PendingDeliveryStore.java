package cn.lunadeer.reColorfulMap.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Durable delivery receipts, separate from the immutable PNG store and map-item paths. */
public final class PendingDeliveryStore {
    public enum Delivery { PENDING, DELIVERING, DELIVERED }
    public enum Payment { UNPAID, CHARGING, PAID, REFUNDING, REFUNDED }

    public record Entry(UUID artwork, UUID owner, Delivery delivery, Payment payment,
                        double amount, long createdAt) {
        public Entry {
            Objects.requireNonNull(artwork); Objects.requireNonNull(owner);
            Objects.requireNonNull(delivery); Objects.requireNonNull(payment);
            if (!Double.isFinite(amount) || amount < 0 || createdAt < 0) {
                throw new IllegalArgumentException("Invalid delivery receipt");
            }
        }
        public Entry delivery(Delivery value) { return new Entry(artwork, owner, value, payment, amount, createdAt); }
        public Entry payment(Payment value) { return new Entry(artwork, owner, delivery, value, amount, createdAt); }
        public boolean uncertain() { return delivery == Delivery.DELIVERING || payment == Payment.CHARGING || payment == Payment.REFUNDING; }
    }

    private final Path directory;
    private final java.util.concurrent.ConcurrentMap<UUID, Entry> entries = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean healthy = true;

    public PendingDeliveryStore(Path pluginDataDirectory) throws IOException {
        Files.createDirectories(pluginDataDirectory);
        directory = pluginDataDirectory.toRealPath().resolve("deliveries");
        if (Files.isSymbolicLink(directory)) throw new IOException("Delivery directory must not be a symbolic link");
        Files.createDirectories(directory);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.properties")) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16384) {
                    throw new IOException("Invalid delivery receipt: " + file);
                }
                Entry entry = read(file);
                if (!file.getFileName().toString().equals(entry.artwork() + ".properties") || entries.put(entry.artwork(), entry) != null) {
                    throw new IOException("Delivery receipt identity mismatch: " + file);
                }
            }
        }
    }

    public Optional<Entry> find(UUID artwork) { return Optional.ofNullable(entries.get(artwork)); }
    public List<Entry> list(UUID owner) {
        return entries.values().stream().filter(e -> e.owner().equals(owner) && e.delivery() != Delivery.DELIVERED)
                .sorted(Comparator.comparingLong(Entry::createdAt)).toList();
    }

    public synchronized Entry create(UUID artwork, UUID owner, double amount) throws IOException {
        if (entries.containsKey(artwork)) throw new IOException("A delivery receipt already exists for " + artwork);
        Entry entry = new Entry(artwork, owner, Delivery.PENDING, Payment.UNPAID, amount, System.currentTimeMillis());
        save(entry);
        return entry;
    }

    /** Deleted artwork can never be delivered again; retain pending/ambiguous records for diagnosis. */
    public synchronized int pruneCompleted(java.util.function.Predicate<UUID> artworkMissing) throws IOException {
        if (!healthy) throw new IOException("Delivery storage requires a restart");
        int removed = 0;
        for (Entry entry : List.copyOf(entries.values())) {
            if (entry.delivery() != Delivery.DELIVERED || !artworkMissing.test(entry.artwork())) continue;
            try {
                Files.delete(directory.resolve(entry.artwork() + ".properties"));
                entries.remove(entry.artwork());
                removed++;
            } catch (IOException failure) { healthy = false; throw failure; }
        }
        if (removed > 0 && !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
            catch (IOException failure) { healthy = false; throw failure; }
        }
        return removed;
    }

    /** Compare-and-set prevents simultaneous claims and stale completion callbacks. */
    public synchronized Entry update(Entry expected, UnaryOperator<Entry> change) throws IOException {
        if (!expected.equals(entries.get(expected.artwork()))) throw new IOException("Delivery state changed; retry the operation");
        Entry next = Objects.requireNonNull(change.apply(expected));
        if (!next.artwork().equals(expected.artwork()) || !next.owner().equals(expected.owner())
                || next.amount() != expected.amount() || next.createdAt() != expected.createdAt()) {
            throw new IOException("Receipt identity and quoted price cannot be changed");
        }
        save(next);
        return next;
    }

    private void save(Entry entry) throws IOException {
        if (!healthy) throw new IOException("Delivery storage requires a server restart after a failed write");
        Properties values = new Properties();
        values.setProperty("version", "1"); values.setProperty("artwork", entry.artwork().toString());
        values.setProperty("owner", entry.owner().toString()); values.setProperty("delivery", entry.delivery().name());
        values.setProperty("payment", entry.payment().name()); values.setProperty("amount", Double.toString(entry.amount()));
        values.setProperty("created-at", Long.toString(entry.createdAt()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); values.store(bytes, "ReColorfulMap delivery receipt");
        Path temporary = Files.createTempFile(directory, ".receipt-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Path target = directory.resolve(entry.artwork() + ".properties");
            if (Files.isSymbolicLink(target)) throw new IOException("Delivery receipt must not be a symbolic link");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            entries.put(entry.artwork(), entry);
            // Directory fsync is supported on Unix. Windows does not permit opening directory channels.
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
            }
        } catch (IOException failure) {
            healthy = false;
            throw failure;
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Entry read(Path file) throws IOException {
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { values.load(input); }
        try {
            if (!"1".equals(values.getProperty("version"))) throw new IllegalArgumentException("Unknown receipt version");
            UUID artwork = UUID.fromString(values.getProperty("artwork"));
            UUID owner = UUID.fromString(values.getProperty("owner"));
            if (!artwork.toString().equals(values.getProperty("artwork")) || !owner.toString().equals(values.getProperty("owner"))) {
                throw new IllegalArgumentException("Non-canonical receipt UUID");
            }
            return new Entry(artwork, owner,
                    Delivery.valueOf(values.getProperty("delivery")), Payment.valueOf(values.getProperty("payment")),
                    Double.parseDouble(values.getProperty("amount")), Long.parseLong(values.getProperty("created-at")));
        } catch (RuntimeException failure) { throw new IOException("Invalid delivery receipt: " + file, failure); }
    }
}
