package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.commands.MapManage;
import cn.lunadeer.reColorfulMap.commands.Reload;
import cn.lunadeer.reColorfulMap.commands.ToMap;
import cn.lunadeer.reColorfulMap.events.ImageMapEvent;
import cn.lunadeer.reColorfulMap.events.MapCopyEvent;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationFile;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationManager;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

public class Language extends ConfigurationFile {
    public static volatile ReColorfulMap.ReColorfulMapText reColorfulMapText = new ReColorfulMap.ReColorfulMapText();
    public static volatile ImageRenderer.ImageRendererText imageRenderer = new ImageRenderer.ImageRendererText();
    public static volatile ImageMapItem.ImageMapItemText imageMapItem = new ImageMapItem.ImageMapItemText();
    public static volatile ToMap.ToMapCommandText toMapCommand = new ToMap.ToMapCommandText();
    public static volatile ImageMapEvent.ImageMapEventText imageMapEvent = new ImageMapEvent.ImageMapEventText();
    public static volatile Reload.ReloadCommandText reloadCommand = new Reload.ReloadCommandText();
    public static volatile MapManage.MapManageText mapManage = new MapManage.MapManageText();

    public static volatile MapCopyEvent.CopyText mapCopy = new MapCopyEvent.CopyText();

    static ConfigurationManager.Prepared prepare(String selectedLanguage) throws Exception {
        ReColorfulMap plugin = ReColorfulMap.getInstance();
        for (String resource : List.of("languages/en_us.yml", "languages/zh_cn.yml")) {
            if (!new File(plugin.getDataFolder(), resource).exists()) plugin.saveResource(resource, false);
        }
        File file = new File(plugin.getDataFolder(), "languages/" + selectedLanguage + ".yml");
        if (!file.isFile()) throw new java.io.IOException("Language file does not exist: " + file.getName());
        return prepare(file);
    }

    static ConfigurationManager.Prepared prepare(File file) throws Exception {
        YamlConfiguration yaml = ConfigurationManager.readYaml(file);
        // Early Chinese releases shipped this name; respect administrators' existing translation.
        if (!yaml.contains("reload-command") && yaml.isConfigurationSection("reload-command-text")) {
            yaml.set("reload-command", yaml.getConfigurationSection("reload-command-text"));
        }
        return ConfigurationManager.prepare(Language.class, yaml);
    }

    public static boolean load(CommandSender sender) {
        try {
            prepare(Configuration.snapshot().language()).publish();
            return true;
        } catch (Exception e) {
            Notification.error(sender, Language.reColorfulMapText.failToLoadLanguage, e.getMessage());
            return false;
        }
    }
}
