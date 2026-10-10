package cn.lunadeer.reColorfulMap.commands;

import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ToMap implements TabExecutor {
    public static class ToMapCommandText extends ConfigurationPart {
        public String usage = "Usage /tomap <image-url> [scale (optional, default 1)]";
        public String scaleMustBeNumber = "Scale must be a finite positive number and produce an image at least 1x1 pixel.";
        public String serverBusy = "All image processing slots are busy. Please try again later.";
        public String generatingMap = "Generating map...";
        public String generatedMap = "Generated map, frame size: %d x %d";
        public String generateMapFailed = "Failed to generate map: %s";
        public String playerOnly = "Only players can use this command.";
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Notification.warn(sender, Language.toMapCommand.playerOnly);
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            Notification.warn(player, Language.toMapCommand.usage);
            return true;
        }
        final float scale;
        try {
            scale = args.length == 2 ? Float.parseFloat(args[1]) : 1;
            if (!Float.isFinite(scale) || scale <= 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            Notification.warn(player, Language.toMapCommand.scaleMustBeNumber);
            return true;
        }
        ReColorfulMap.getInstance().getGenerationService().submit(player, args[0], scale);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return args.length == 1 ? List.of("<image-url>") : args.length == 2 ? List.of("1") : List.of();
    }
}
