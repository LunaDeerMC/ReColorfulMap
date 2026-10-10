package cn.lunadeer.reColorfulMap.utils;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class SendMessageAbstract {

    private final JavaPlugin plugin;

    public SendMessageAbstract(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void sendMessage(Player player, Component msg) {

        player.sendMessage(msg);

    }

    public void sendMessage(CommandSender sender, Component msg) {

        sender.sendMessage(msg);

    }

    public void broadcast(Component msg) {

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(msg);
        }
        plugin.getServer().getConsoleSender().sendMessage(msg);

    }

    public void sendActionBar(Player player, Component msg) {

        player.sendActionBar(msg);

    }

    public void sendTitle(Player player, Component title, Component subtitle) {
        Title titleObj = Title.title(title, subtitle);

        player.showTitle(titleObj);

    }

    public void sendBossBar(Player player, Component msg) {
        BossBar bossBar = BossBar.bossBar(msg, 1.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);

        player.showBossBar(bossBar);

        // Follow the player's owning region if they move before the bar expires.
        player.getScheduler().runDelayed(plugin, task -> player.hideBossBar(bossBar), null, 60);
    }
}
