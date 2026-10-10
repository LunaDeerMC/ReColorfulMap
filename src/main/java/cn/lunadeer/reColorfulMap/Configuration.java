package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.services.download.UrlWhitelist;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.VaultConnect.VaultConnect;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import cn.lunadeer.reColorfulMap.utils.configuration.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Locale;

/** Configuration is parsed and validated off to the side, then published in one volatile write. */
public class Configuration extends ConfigurationFile {
    // Compatibility mirrors. Plugin code uses snapshot() and captures it once per operation.
    public static int version = 3;
    public static int maxFrameX = 32;
    public static int maxFrameY = 18;
    public static String language = "en_us";
    public static Economy economy = new Economy();
    public static List<String> addressWhiteList = List.of();
    public static boolean debug = false;

    public static class Economy extends ConfigurationPart {
        public boolean enable = false;
        public double costPerMap = 100.0;
    }

    public record EconomySettings(boolean enable, double costPerMap) { }
    public record DownloadSettings(long maxBytes, long maxPixels, int connectTimeoutMillis,
                                   int readTimeoutMillis, int totalTimeoutMillis, int maxRedirects) { }
    public record GenerationSettings(int workers, int queueCapacity) { }
    public record Snapshot(int maxFrameX, int maxFrameY, String language, boolean debug,
                           List<String> addressWhiteList, EconomySettings economy,
                           DownloadSettings download, GenerationSettings generation) {
        public Snapshot { addressWhiteList = List.copyOf(addressWhiteList); }
    }

    private static final Snapshot DEFAULTS = new Snapshot(32, 18, "en_us", false, List.of(),
            new EconomySettings(false, 100.0),
            new DownloadSettings(32L * 1024 * 1024, 16_000_000L, 10_000, 15_000, 60_000, 5),
            new GenerationSettings(2, 16));
    private static volatile Snapshot current = DEFAULTS;

    public static Snapshot snapshot() { return current; }

    /** Parse without mutating either the current configuration or any file. */
    public static Snapshot read(File file) throws Exception {
        return file.exists() ? parse(ConfigurationManager.readYaml(file)) : DEFAULTS;
    }

    static Snapshot parse(YamlConfiguration yaml) throws InvalidConfigurationException {
        integer(yaml, "version", 3, 1, 3);
        section(yaml, "economy");
        section(yaml, "download");
        section(yaml, "generation");
        int x = integer(yaml, "max-frame-x", DEFAULTS.maxFrameX(), 1, 256);
        int y = integer(yaml, "max-frame-y", DEFAULTS.maxFrameY(), 1, 256);
        if ((long) x * y > 4096) throw invalid("max-frame-x/max-frame-y", "must not exceed 4096 tiles in total");
        String languageName = string(yaml, "language", DEFAULTS.language()).toLowerCase(Locale.ROOT);
        if (!languageName.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw invalid("language", "must be a language filename without a path or extension");
        boolean debugEnabled = bool(yaml, "debug", DEFAULTS.debug());
        double cost = decimal(yaml, "economy.cost-per-map", DEFAULTS.economy().costPerMap());
        if (cost < 0 || !Double.isFinite(cost * x * y)) throw invalid("economy.cost-per-map", "must be non-negative and produce a finite total price");
        List<String> whitelist = strings(yaml, "address-white-list", DEFAULTS.addressWhiteList());
        try { UrlWhitelist.compile(whitelist); }
        catch (IllegalArgumentException e) { throw invalid("address-white-list", e.getMessage()); }
        return new Snapshot(x, y, languageName, debugEnabled, whitelist,
                new EconomySettings(bool(yaml, "economy.enable", false), cost),
                new DownloadSettings(
                        integral(yaml, "download.max-bytes", DEFAULTS.download().maxBytes(), 1, 512L * 1024 * 1024),
                        integral(yaml, "download.max-pixels", DEFAULTS.download().maxPixels(), 1, 100_000_000L),
                        integer(yaml, "download.connect-timeout-millis", 10_000, 1, 600_000),
                        integer(yaml, "download.read-timeout-millis", 15_000, 1, 600_000),
                        integer(yaml, "download.total-timeout-millis", 60_000, 1, 600_000),
                        integer(yaml, "download.max-redirects", 5, 0, 20)),
                new GenerationSettings(integer(yaml, "generation.workers", 2, 1, 32),
                        integer(yaml, "generation.queue-capacity", 16, 0, 1024)));
    }

    /** All validation, including the selected language, completes before publishing changes. */
    public static synchronized boolean load(CommandSender sender) {
        try {
            ReColorfulMap plugin = ReColorfulMap.getInstance();
            File file = new File(plugin.getDataFolder(), "config.yml");
            Snapshot next = read(file);
            ConfigurationManager.Prepared nextLanguage = Language.prepare(next.language());
            if (!file.exists()) ConfigurationManager.saveYaml(defaultYaml(), file);
            // Pending deliveries retain their original quote even if new generation becomes free.
            // The connector is lazy: no economy provider is required until a paid claim uses it.
            if (VaultConnect.instance == null) new VaultConnect(plugin);
            nextLanguage.publish();
            publish(next);
            return true;
        } catch (Exception e) {
            Notification.error(sender, Language.reColorfulMapText.failToLoadConfig, e.getMessage());
            return false;
        }
    }

    static void publish(Snapshot next) {
        // Legacy mirrors never feed the runtime snapshot and cannot cause partially applied reads.
        maxFrameX = next.maxFrameX();
        maxFrameY = next.maxFrameY();
        language = next.language();
        debug = next.debug();
        addressWhiteList = next.addressWhiteList();
        Economy mirror = new Economy();
        mirror.enable = next.economy().enable();
        mirror.costPerMap = next.economy().costPerMap();
        economy = mirror;
        XLogger.setDebug(next.debug());
        current = next;
    }

    static YamlConfiguration defaultYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 3);
        yaml.setComments("version", List.of("Configuration schema version."));
        yaml.set("max-frame-x", DEFAULTS.maxFrameX());
        yaml.set("max-frame-y", DEFAULTS.maxFrameY());
        yaml.setComments("max-frame-x", List.of("Maximum item-frame dimensions; each tile is 128 x 128 pixels.", "Each dimension is at most 256; the total is at most 4096 tiles."));
        yaml.set("language", DEFAULTS.language());
        yaml.setComments("language", List.of("Language filename in this plugin's languages directory, without .yml."));
        yaml.set("economy.enable", DEFAULTS.economy().enable());
        yaml.set("economy.cost-per-map", DEFAULTS.economy().costPerMap());
        yaml.setComments("economy", List.of("Require Vault and an economy plugin. Charge once on generation, per image tile.", "Picking up or placing an existing artwork is always free."));
        yaml.set("address-white-list", DEFAULTS.addressWhiteList());
        yaml.setComments("address-white-list", List.of("Empty list allows any HTTP(S) address. Entries use a complete hostname,", "optional port/path, or a full HTTP(S) URL. Every redirect must also match."));
        yaml.set("download.max-bytes", DEFAULTS.download().maxBytes());
        yaml.set("download.max-pixels", DEFAULTS.download().maxPixels());
        yaml.set("download.connect-timeout-millis", DEFAULTS.download().connectTimeoutMillis());
        yaml.set("download.read-timeout-millis", DEFAULTS.download().readTimeoutMillis());
        yaml.set("download.total-timeout-millis", DEFAULTS.download().totalTimeoutMillis());
        yaml.set("download.max-redirects", DEFAULTS.download().maxRedirects());
        yaml.setComments("download", List.of("Limits apply to the original image before decoding or resizing."));
        yaml.set("generation.workers", DEFAULTS.generation().workers());
        yaml.set("generation.queue-capacity", DEFAULTS.generation().queueCapacity());
        yaml.setComments("generation", List.of("Global image generation concurrency and waiting-queue capacity (restart required)."));
        yaml.set("debug", DEFAULTS.debug());
        return yaml;
    }

    private static void section(YamlConfiguration yaml, String key) throws InvalidConfigurationException {
        if (yaml.contains(key) && !yaml.isConfigurationSection(key)) throw invalid(key, "must be a mapping");
    }
    private static String string(YamlConfiguration yaml, String key, String fallback) throws InvalidConfigurationException {
        if (!yaml.contains(key)) return fallback;
        if (yaml.get(key) instanceof String value) return value;
        throw invalid(key, "must be a string");
    }
    private static boolean bool(YamlConfiguration yaml, String key, boolean fallback) throws InvalidConfigurationException {
        if (!yaml.contains(key)) return fallback;
        if (yaml.get(key) instanceof Boolean value) return value;
        throw invalid(key, "must be a boolean");
    }
    private static int integer(YamlConfiguration yaml, String key, int fallback, int min, int max) throws InvalidConfigurationException {
        return (int) integral(yaml, key, fallback, min, max);
    }
    private static long integral(YamlConfiguration yaml, String key, long fallback, long min, long max) throws InvalidConfigurationException {
        if (!yaml.contains(key)) return fallback;
        Object raw = yaml.get(key);
        if (!(raw instanceof Integer || raw instanceof Long)) throw invalid(key, "must be an integer");
        long value = ((Number) raw).longValue();
        if (value < min || value > max) throw invalid(key, "must be between " + min + " and " + max);
        return value;
    }
    private static double decimal(YamlConfiguration yaml, String key, double fallback) throws InvalidConfigurationException {
        if (!yaml.contains(key)) return fallback;
        if (yaml.get(key) instanceof Number value && Double.isFinite(value.doubleValue())) return value.doubleValue();
        throw invalid(key, "must be a finite number");
    }
    private static List<String> strings(YamlConfiguration yaml, String key, List<String> fallback) throws InvalidConfigurationException {
        if (!yaml.contains(key)) return fallback;
        if (!(yaml.get(key) instanceof List<?> values) || !values.stream().allMatch(String.class::isInstance)) throw invalid(key, "must be a list of strings");
        return values.stream().map(String.class::cast).toList();
    }
    private static InvalidConfigurationException invalid(String key, String reason) {
        return new InvalidConfigurationException(key + " " + reason);
    }
}
