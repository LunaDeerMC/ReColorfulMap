package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.commands.Clean;
import cn.lunadeer.reColorfulMap.commands.Reload;
import cn.lunadeer.reColorfulMap.commands.ToMap;
import cn.lunadeer.reColorfulMap.commands.MapManage;
import cn.lunadeer.reColorfulMap.events.ImageMapEvent;
import cn.lunadeer.reColorfulMap.events.MapBindingEvent;
import cn.lunadeer.reColorfulMap.events.MapDestructionEvent;
import cn.lunadeer.reColorfulMap.events.MapCopyEvent;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.services.ArtworkWallService;
import cn.lunadeer.reColorfulMap.services.GenerationService;
import cn.lunadeer.reColorfulMap.services.ArtworkCopyService;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import cn.lunadeer.reColorfulMap.utils.bStatsMetrics;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.io.IOException;

public final class ReColorfulMap extends JavaPlugin {

    public static class ReColorfulMapText extends ConfigurationPart {
        public String failToLoadConfig = "Failed to load configuration file: %s";
        public String failToLoadLanguage = "Failed to load language file: %s, using default language.";
        public String loading = "ReColorfulMap is loading...";
        public String loaded = "ReColorfulMap is loaded successfully!";
    }

    @Override
    public void onEnable() {
        instance = this;
        new Notification(this);
        new XLogger(this);
        new Scheduler(this);
        if (!Configuration.load(getServer().getConsoleSender())) {
            getLogger().severe("Cannot enable ReColorfulMap with an invalid configuration; the original file was kept.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        XLogger.info(Language.reColorfulMapText.loading);

        try {
            mapStorage = new MapStorage(getDataFolder().toPath());
            mapViewRegistry = new MapViewRegistry(mapStorage);
            artworkWallService = new ArtworkWallService(this);
            generationService = new GenerationService(mapStorage, getDataFolder().toPath(), Configuration.snapshot().generation());
            artworkCopyService = new ArtworkCopyService(this);
        } catch (IOException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Cannot initialize map services; existing data was not replaced", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // https://patorjk.com/software/taag/#p=display&f=Big&t=reColorfulMap
        XLogger.info("            _____      _             __       _ __  __");
        XLogger.info("           / ____|    | |           / _|     | |  \\/  |");
        XLogger.info("  _ __ ___| |     ___ | | ___  _ __| |_ _   _| | \\  / | __ _ _ __");
        XLogger.info(" | '__/ _ \\ |    / _ \\| |/ _ \\| '__|  _| | | | | |\\/| |/ _` | '_ \\");
        XLogger.info(" | | |  __/ |___| (_) | | (_) | |  | | | |_| | | |  | | (_| | |_) |");
        XLogger.info(" |_|  \\___|\\_____/\\___/|_|\\___/|_|  |_|  \\__,_|_|_|  |_|\\__,_| .__/");
        XLogger.info("                                                            | |");
        XLogger.info("                                                            |_|");

        new bStatsMetrics(this, 21443);

        Objects.requireNonNull(getCommand("tomap")).setExecutor(new ToMap());
        Objects.requireNonNull(getCommand("reloadColorfulMap")).setExecutor(new Reload());
        Objects.requireNonNull(getCommand("cleanColorfulMap")).setExecutor(new Clean());
        Objects.requireNonNull(getCommand("mapmanage")).setExecutor(new MapManage());

        getServer().getPluginManager().registerEvents(new ImageMapEvent(artworkWallService), this);
        MapBindingEvent bindings = new MapBindingEvent(this);
        getServer().getPluginManager().registerEvents(bindings, this);
        getServer().getPluginManager().registerEvents(new MapCopyEvent(), this);
        new MapDestructionEvent(this).register();
        bindings.bootstrap();

        XLogger.info(Language.reColorfulMapText.loaded);
        XLogger.debug("Debug mode is enabled.");
    }

    @Override
    public void onDisable() {
        if (artworkCopyService != null) {
            artworkCopyService.close();
        }
        if (generationService != null) {
            generationService.close();
        }
        if (artworkWallService != null) {
            artworkWallService.close();
        }
        if (mapViewRegistry != null) {
            mapViewRegistry.clear();
        }
        if (Scheduler.instance != null) {
            Scheduler.cancelAll();
        }
    }

    private static ReColorfulMap instance;
    private MapStorage mapStorage;
    private MapViewRegistry mapViewRegistry;
    private ArtworkWallService artworkWallService;
    private GenerationService generationService;
    private ArtworkCopyService artworkCopyService;

    /** Integration point for plugins that need copies with independent image ownership. */
    public ArtworkCopyService getArtworkCopyService() {
        return Objects.requireNonNull(artworkCopyService, "Artwork copying has not been initialized");
    }

    public MapStorage getMapStorage() {
        return Objects.requireNonNull(mapStorage, "Map storage has not been initialized");
    }

    public MapViewRegistry getMapViewRegistry() {
        return Objects.requireNonNull(mapViewRegistry, "Map view registry has not been initialized");
    }

    public ArtworkWallService getArtworkWallService() {
        return Objects.requireNonNull(artworkWallService, "Artwork wall service has not been initialized");
    }

    public GenerationService getGenerationService() {
        return Objects.requireNonNull(generationService, "Generation service has not been initialized");
    }

    public static ReColorfulMap getInstance() {
        return instance;
    }
}
