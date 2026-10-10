package cn.lunadeer.reColorfulMap.commands;

import cn.lunadeer.reColorfulMap.Configuration;
import cn.lunadeer.reColorfulMap.ImageMapGenerator;
import cn.lunadeer.reColorfulMap.ImageMapItem;
import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.awt.image.BufferedImage;

public class ToMap implements TabExecutor {
    public static class ToMapCommandText extends ConfigurationPart {
        public String usage = "Usage /tomap <image-url> [scale (optional, default 1)]";
        public String scaleMustBeNumber = "Scale must be a finite positive number and produce an image at least 1x1 pixel.";
        public String generatingMap = "Generating map...";
        public String generatedMap = "Generated map, frame size: %d x %d";
        public String generateMapFailed = "Failed to generate map: %s";
        public String playerOnly = "Only players can use this command.";
    }

    private final Set<UUID> generating = ConcurrentHashMap.newKeySet();

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
            if (!Float.isFinite(scale) || scale <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            Notification.warn(player, Language.toMapCommand.scaleMustBeNumber);
            return true;
        }
        if (!generating.add(player.getUniqueId())) {
            Notification.info(player, Language.toMapCommand.generatingMap);
            return true;
        }
        MapStorage storage = ReColorfulMap.getInstance().getMapStorage();
        int maxX = Configuration.maxFrameX;
        int maxY = Configuration.maxFrameY;
        List<String> whitelist = List.copyOf(Configuration.addressWhiteList);
        Notification.info(player, Language.toMapCommand.generatingMap);
        Scheduler.runTaskAsync(() -> {
            try {
                MapStorage.Artwork artwork = ImageMapGenerator.generate(storage, args[0], scale, maxX, maxY, whitelist);
                BufferedImage thumbnail = storage.read(MapStorage.ImageReference.thumbnail(artwork.id()));
                Scheduler.runTask(player, () -> {
                    try {
                        ImageMapItem item = new ImageMapItem(player.getWorld(), artwork, thumbnail);
                        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
                            if (!player.getWorld().dropItemNaturally(player.getLocation(), leftover).isValid()) {
                                // A spawn listener may have transferred the item elsewhere, so keep its files.
                                throw new IllegalStateException("Could not deliver the generated map: item drop was cancelled. "
                                        + "Image files were kept for artwork " + artwork.id());
                            }
                        }
                        Notification.info(player, Language.toMapCommand.generatedMap, artwork.columns(), artwork.rows());
                    } catch (Exception e) {
                        reportFailure(player, e);
                    } finally {
                        generating.remove(player.getUniqueId());
                    }
                }, () -> generating.remove(player.getUniqueId()));
            } catch (Exception e) {
                completeFailure(player, e);
            }
        });
        return true;
    }

    private void completeFailure(Player player, Exception error) {
        Scheduler.runTask(player, () -> {
            generating.remove(player.getUniqueId());
            reportFailure(player, error);
        }, () -> generating.remove(player.getUniqueId()));
    }

    private static void reportFailure(Player player, Exception error) {
        XLogger.warn("Failed to generate map: %s", error.getMessage());
        Notification.error(player, Language.toMapCommand.generateMapFailed, error.getMessage());
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return args.length == 1 ? List.of("<image-url>") : args.length == 2 ? List.of("1") : List.of();
    }
}
