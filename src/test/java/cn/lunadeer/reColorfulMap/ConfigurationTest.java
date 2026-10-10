package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.VaultConnect.VaultConnect;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationManager;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfigurationTest {
    @TempDir Path directory;
    private Configuration.Snapshot previous;
    private VaultConnect previousVault;
    private final Map<Field, Object> previousLanguage = new HashMap<>();

    @BeforeEach void keepConfiguration() throws Exception {
        previous = Configuration.snapshot();
        previousVault = VaultConnect.instance;
        for (Field field : Language.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) previousLanguage.put(field, field.get(null));
        }
        Files.createDirectories(directory.resolve("languages"));
        Files.writeString(directory.resolve("languages/en_us.yml"), "{}");
        Files.writeString(directory.resolve("languages/zh_cn.yml"), "{}");
    }

    @AfterEach void restoreConfiguration() throws Exception {
        Configuration.publish(previous);
        VaultConnect.instance = previousVault;
        for (Map.Entry<Field, Object> entry : previousLanguage.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    private Configuration.Snapshot read(String content) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, content);
        return Configuration.read(file.toFile());
    }

    @Test void malformedYamlPreservesBytesAndSnapshot() throws Exception {
        String contents = "# Keep my comments!\nmax-frame-x: 10\naddress-white-list: [unterminated\n";
        assertThrows(Exception.class, () -> read(contents));
        assertEquals(contents, Files.readString(directory.resolve("config.yml")));
        assertSame(previous, Configuration.snapshot());
    }

    @Test void lateTypeFailureCannotPartiallyApplyEarlierValues() {
        assertThrows(Exception.class, () -> read("max-frame-x: 10\nmax-frame-y: wrong\n"));
        assertSame(previous, Configuration.snapshot());
        assertEquals(previous.maxFrameX(), Configuration.maxFrameX);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "debug: 'true'", "max-frame-x: 0", "max-frame-x: 1.5", "max-frame-x: 257",
            "max-frame-x: 100\nmax-frame-y: 100", "language: '../config'", "economy: nope",
            "economy:\n  cost-per-map: .nan", "economy:\n  cost-per-map: .inf",
            "economy:\n  cost-per-map: -1", "economy:\n  cost-per-map: 1e308",
            "download:\n  max-bytes: 0", "download:\n  max-pixels: 100000001",
            "download:\n  total-timeout-millis: 0", "download:\n  max-redirects: 21",
            "generation:\n  workers: 0", "generation:\n  queue-capacity: -1",
            "address-white-list: [123]", "address-white-list: ['https://example.org@evil.invalid']",
            "debug: false\ndebug: true", "- not-a-mapping", "version: 999"
    }) void rejectsInvalidConfiguration(String content) {
        assertThrows(Exception.class, () -> read(content));
        assertSame(previous, Configuration.snapshot());
    }

    @Test void missingFileAndLegacyFileHaveSafeDefaultsAndAreNotWritten() throws Exception {
        Path missing = directory.resolve("missing.yml");
        Configuration.Snapshot defaults = Configuration.read(missing.toFile());
        assertFalse(Files.exists(missing));
        assertEquals(32L * 1024 * 1024, defaults.download().maxBytes());
        assertEquals(16_000_000L, defaults.download().maxPixels());
        assertEquals(2, defaults.generation().workers());
        assertEquals(16, defaults.generation().queueCapacity());
        String legacy = "# Old configuration\nversion: 2\neconomy:\n  enable: true\n  cost-per-map: 50\naddress-white-list: ['']\n";
        Configuration.Snapshot loaded = read(legacy);
        assertEquals(50, loaded.economy().costPerMap());
        assertTrue(loaded.economy().enable());
        assertEquals(defaults.download(), loaded.download());
        assertEquals(legacy, Files.readString(directory.resolve("config.yml")));
    }

    @Test void whitelistSnapshotIsDefensivelyCopied() {
        List<String> urls = new ArrayList<>(List.of("example.org"));
        Configuration.Snapshot value = new Configuration.Snapshot(1, 1, "en_us", false, urls,
                previous.economy(), previous.download(), previous.generation());
        urls.clear();
        assertEquals(List.of("example.org"), value.addressWhiteList());
        assertThrows(UnsupportedOperationException.class, () -> value.addressWhiteList().clear());
    }

    @Test void snapshotsArePublishedTogetherUnderConcurrentReads() throws Exception {
        Configuration.Snapshot first = new Configuration.Snapshot(1, 2, "one", false, List.of(), previous.economy(), previous.download(), previous.generation());
        Configuration.Snapshot second = new Configuration.Snapshot(3, 4, "two", true, List.of("example.org"), previous.economy(), previous.download(), previous.generation());
        Configuration.publish(first);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean mixed = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            while (running.get()) {
                Configuration.Snapshot observed = Configuration.snapshot();
                if (!observed.equals(first) && !observed.equals(second)) mixed.set(true);
            }
        });
        reader.start();
        try {
            for (int count = 0; count < 20_000; count++) Configuration.publish(count % 2 == 0 ? first : second);
        } finally {
            running.set(false);
            reader.join(5_000);
        }
        assertFalse(reader.isAlive());
        assertFalse(mixed.get());
    }

    @Test void debugChangesAtPublishAndInvalidReloadCannotChangeIt() throws Exception {
        Configuration.publish(read("debug: true"));
        assertTrue(XLogger.isDebug());
        assertThrows(Exception.class, () -> read("debug: false\neconomy: broken"));
        assertTrue(XLogger.isDebug());
        Configuration.publish(read("debug: false"));
        assertFalse(XLogger.isDebug());
    }

    @Test void badLanguageBlocksConfigReloadWithoutChangingEitherFileOrRuntime() throws Exception {
        Path config = directory.resolve("config.yml");
        String configBytes = "debug: true\nlanguage: zh_cn\nmax-frame-x: 4\n";
        Files.writeString(config, configBytes);
        String languageBytes = "to-map-command:\n  player-only: [unterminated\n";
        Files.writeString(directory.resolve("languages/zh_cn.yml"), languageBytes);
        Object oldText = Language.toMapCommand;
        try (MockedStatic<ReColorfulMap> access = mockStatic(ReColorfulMap.class);
             MockedStatic<Notification> notifications = mockStatic(Notification.class)) {
            ReColorfulMap plugin = mock(ReColorfulMap.class);
            when(plugin.getDataFolder()).thenReturn(directory.toFile());
            access.when(ReColorfulMap::getInstance).thenReturn(plugin);
            assertFalse(Configuration.load(mock(CommandSender.class)));
        }
        assertSame(previous, Configuration.snapshot());
        assertSame(oldText, Language.toMapCommand);
        assertEquals(configBytes, Files.readString(config));
        assertEquals(languageBytes, Files.readString(directory.resolve("languages/zh_cn.yml")));
    }

    @Test void successfulReloadPublishesLanguageDebugAndDoesNotRegisterVaultTwice() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "debug: true\neconomy:\n  enable: true\n");
        Files.writeString(directory.resolve("languages/en_us.yml"), "to-map-command:\n  player-only: translated\n");
        VaultConnect.instance = null;
        try (MockedStatic<ReColorfulMap> access = mockStatic(ReColorfulMap.class);
             MockedStatic<Notification> notifications = mockStatic(Notification.class)) {
            ReColorfulMap plugin = mock(ReColorfulMap.class);
            Server server = mock(Server.class);
            PluginManager manager = mock(PluginManager.class);
            when(plugin.getDataFolder()).thenReturn(directory.toFile());
            when(plugin.getServer()).thenReturn(server);
            when(server.getPluginManager()).thenReturn(manager);
            access.when(ReColorfulMap::getInstance).thenReturn(plugin);
            assertTrue(Configuration.load(mock(CommandSender.class)));
            assertTrue(Configuration.load(mock(CommandSender.class)));
            verify(manager, times(1)).registerEvents(any(VaultConnect.class), same(plugin));
        }
        assertEquals("translated", Language.toMapCommand.playerOnly);
        assertTrue(Configuration.snapshot().debug());
        assertTrue(XLogger.isDebug());
    }

    @Test void languageTypeErrorKeepsAllGroupsAndFileBytes() throws Exception {
        Path file = directory.resolve("language.yml");
        String yaml = "to-map-command:\n  player-only: translated\nreload-command:\n  reload-config-success: 123\n";
        Files.writeString(file, yaml);
        Object before = Language.toMapCommand;
        assertThrows(Exception.class, () -> Language.prepare(file.toFile()));
        assertSame(before, Language.toMapCommand);
        assertEquals(yaml, Files.readString(file));
    }

    @Test void legacyChineseReloadTranslationIsAcceptedWithoutRewriting() throws Exception {
        Path file = directory.resolve("language.yml");
        String yaml = "reload-command-text:\n  reloading-config: 正在加载\n";
        Files.writeString(file, yaml);
        Language.prepare(file.toFile()).publish();
        assertEquals("正在加载", Language.reloadCommand.reloadingConfig);
        assertEquals(yaml, Files.readString(file));
    }

    @Test void generatedDefaultFileRoundTripsThroughValidation() throws Exception {
        Path file = directory.resolve("new-config.yml");
        ConfigurationManager.saveYaml(Configuration.defaultYaml(), file.toFile());
        assertEquals(Configuration.read(directory.resolve("absent.yml").toFile()), Configuration.read(file.toFile()));
    }
}
