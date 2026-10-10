package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.commands.Clean;
import cn.lunadeer.reColorfulMap.commands.Reload;
import cn.lunadeer.reColorfulMap.commands.ToMap;
import cn.lunadeer.reColorfulMap.events.ImageMapEvent;
import cn.lunadeer.reColorfulMap.events.MapBindingEvent;
import cn.lunadeer.reColorfulMap.events.MapDestructionEvent;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
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
        Configuration.load(ReColorfulMap.getInstance().getServer().getConsoleSender());
        XLogger.setDebug(Configuration.debug);
        XLogger.info(Language.reColorfulMapText.loading);

        try {
            mapStorage = new MapStorage(getDataFolder().toPath());
        } catch (IOException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Cannot open maps storage; existing data was not replaced", e);
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

        getServer().getPluginManager().registerEvents(new ImageMapEvent(), this);
        MapBindingEvent bindings = new MapBindingEvent(this);
        getServer().getPluginManager().registerEvents(bindings, this);
        new MapDestructionEvent(this).register();
        bindings.bootstrap();

        XLogger.info(Language.reColorfulMapText.loaded);
        XLogger.debug("Debug mode is enabled.");
    }

    @Override
    public void onDisable() {
        if (Scheduler.instance != null) {
            Scheduler.cancelAll();
        }
    }

    private static ReColorfulMap instance;
    private MapStorage mapStorage;

    public MapStorage getMapStorage() {
        return Objects.requireNonNull(mapStorage, "Map storage has not been initialized");
    }

    public static ReColorfulMap getInstance() {
        return instance;
    }
}
