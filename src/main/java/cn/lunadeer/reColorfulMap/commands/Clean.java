package cn.lunadeer.reColorfulMap.commands;

import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

public class Clean implements TabExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        MapStorage storage = ReColorfulMap.getInstance().getMapStorage();
        Scheduler.runTaskAsync(() -> {
            try {
                int count = storage.cleanTemporaryFiles();
                reply(sender, () -> Notification.info(sender, Language.imageMapItem.removedImageTiles, count));
            } catch (IOException e) {
                reply(sender, () -> Notification.error(sender, "Cannot clean temporary map files: " + e.getMessage()));
            }
        });
        return true;
    }

    private static void reply(CommandSender sender, Runnable task) {
        if (sender instanceof Player player) {
            Scheduler.runTask(player, task, () -> { });
        } else {
            Scheduler.runTask(task);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return List.of();
    }
}
