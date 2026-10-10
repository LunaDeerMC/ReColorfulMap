package cn.lunadeer.reColorfulMap.commands;

import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.services.GenerationService;
import cn.lunadeer.reColorfulMap.storage.PendingDeliveryStore.Entry;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.Scheduler;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/** Recovery commands never silently reset delivered or crash-ambiguous receipts. */
public final class MapManage implements TabExecutor {
    public static class MapManageText extends ConfigurationPart {
        public String usage = "Usage: /mapmanage status|cancel|list|claim <artwork UUID>";
        public String adminUsage = "Admin: /mapmanage inspect <UUID> | recover <UUID> <online player> confirm | resolve <UUID> <pending|delivered|paid|unpaid|refunded> confirm";
        public String noPermission = "You do not have permission to use this operation.";
        public String idle = "No image is currently being generated.";
        public String queued = "Your image is waiting for an available processing slot.";
        public String running = "Your image is being generated.";
        public String cancelling = "Your generation task is stopping.";
        public String cancelled = "Cancellation requested. If the image was already saved, you can find it with /mapmanage list.";
        public String empty = "You have no maps waiting for delivery.";
        public String pendingEntry = "%s — %s; creation price: %.2f. Use /mapmanage claim <UUID>.";
        public String ready = "Ready to claim";
        public String review = "Awaiting administrator review";
        public String notOwned = "No map waiting for delivery was found with this UUID in your account.";
        public String notFound = "No delivery record was found for this UUID.";
        public String alreadyDelivered = "This map has already been delivered. It cannot be claimed again.";
        public String needsReview = "The previous delivery could not be confirmed. Ask an administrator to check artwork %s before it can be claimed again.";
        public String claimInProgress = "This map is already being prepared for delivery.";
        public String insufficientFunds = "Creating this map costs %.2f. Your balance is too low; the image was saved as %s for later claiming.";
        public String deliveryUncertain = "The map could not be delivered. Its image was kept; ask an administrator to check delivery before trying again.";
        public String economyUnavailable = "The economy service is unavailable. Your saved map can be claimed later.";
        public String invalidUuid = "Please enter a valid artwork UUID.";
        public String playerOffline = "The recipient must be online.";
        public String recoveryScheduled = "Recovery was requested for the original owner. Check the recipient's delivery result.";
        public String inspectEntry = "Artwork %s; owner %s; delivery %s; payment %s; quoted price %.2f.";
        public String inspectMissing = "No receipt exists for %s. Recovery requires first checking that no usable item or placed copy remains.";
        public String resolved = "Delivery record updated. This does not transfer items or money.";
        public String resolvePaymentFirst = "First confirm the economy transaction and resolve it as paid, unpaid or refunded.";
        public String invalidResolution = "This payment resolution does not apply to the current record.";
        public String operationFailed = "Map management failed: %s";
    }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                       @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("recolorfulmap.manage")) {
            Notification.error(sender, Language.mapManage.noPermission); return true;
        }
        GenerationService service = ReColorfulMap.getInstance().getGenerationService();
        String operation = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = Set.of("inspect", "recover", "resolve").contains(operation);
        if (admin && !sender.hasPermission("recolorfulmap.admin")) {
            Notification.error(sender, Language.mapManage.noPermission); return true;
        }
        if (!admin && !(sender instanceof Player)) {
            Notification.error(sender, Language.toMapCommand.playerOnly); return true;
        }
        try {
            switch (operation) {
                case "status" -> Notification.info(sender, service.status(((Player) sender).getUniqueId()));
                case "cancel" -> Notification.info(sender, service.cancel(((Player) sender).getUniqueId())
                        ? Language.mapManage.cancelled : Language.mapManage.idle);
                case "list" -> {
                    List<Entry> pending = service.pending(((Player) sender).getUniqueId());
                    if (pending.isEmpty()) Notification.info(sender, Language.mapManage.empty);
                    for (Entry entry : pending) Notification.info(sender, Language.mapManage.pendingEntry, entry.artwork(),
                            entry.uncertain() ? Language.mapManage.review : Language.mapManage.ready,
                            entry.payment() == cn.lunadeer.reColorfulMap.storage.PendingDeliveryStore.Payment.PAID ? 0 : entry.amount());
                }
                case "claim" -> {
                    if (args.length != 2) { Notification.info(sender, Language.mapManage.usage); break; }
                    service.claim((Player) sender, uuid(args[1]));
                }
                case "inspect" -> {
                    if (args.length != 2) { Notification.info(sender, Language.mapManage.adminUsage); break; }
                    UUID artwork = uuid(args[1]);
                    Optional<Entry> entry = service.inspect(artwork);
                    if (entry.isEmpty()) Notification.info(sender, Language.mapManage.inspectMissing, artwork);
                    else {
                        Entry value = entry.get();
                        Notification.info(sender, Language.mapManage.inspectEntry, value.artwork(), value.owner(),
                                value.delivery(), value.payment(), value.amount());
                    }
                }
                case "recover" -> {
                    if (args.length != 4 || !"confirm".equals(args[3])) { Notification.info(sender, Language.mapManage.adminUsage); break; }
                    UUID artwork = uuid(args[1]);
                    Player owner = Bukkit.getPlayerExact(args[2]);
                    if (owner == null) { Notification.error(sender, Language.mapManage.playerOffline); break; }
                    Scheduler.runTask(owner, () -> service.recover(owner, artwork), () -> { });
                    Notification.info(sender, Language.mapManage.recoveryScheduled);
                }
                case "resolve" -> {
                    if (args.length != 4 || !"confirm".equals(args[3])) { Notification.info(sender, Language.mapManage.adminUsage); break; }
                    service.resolve(uuid(args[1]), args[2].toLowerCase(Locale.ROOT), failure -> {
                        Runnable response = () -> {
                            if (failure == null) Notification.info(sender, Language.mapManage.resolved);
                            else Notification.error(sender, Language.mapManage.operationFailed, failure.getMessage());
                        };
                        if (sender instanceof Player player) Scheduler.runTask(player, response, () -> { });
                        else Scheduler.runTask(response);
                    });
                }
                default -> Notification.info(sender, Language.mapManage.usage);
            }
        } catch (IllegalArgumentException failure) {
            Notification.error(sender, Language.mapManage.operationFailed, failure.getMessage());
        } catch (Exception failure) {
            Notification.error(sender, Language.mapManage.operationFailed, failure.getMessage());
        }
        return true;
    }

    private static UUID uuid(String input) {
        try {
            UUID value = UUID.fromString(input);
            if (!value.toString().equalsIgnoreCase(input)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException(Language.mapManage.invalidUuid); }
    }

    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("status", "cancel", "list", "claim"));
            if (sender.hasPermission("recolorfulmap.admin")) values.addAll(List.of("inspect", "recover", "resolve"));
            return values.stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("claim") && sender instanceof Player player) {
            return ReColorfulMap.getInstance().getGenerationService().pending(player.getUniqueId()).stream()
                    .filter(entry -> !entry.uncertain()).map(entry -> entry.artwork().toString())
                    .filter(value -> value.startsWith(args[1])).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("resolve") && sender.hasPermission("recolorfulmap.admin")) {
            return List.of("pending", "delivered", "paid", "unpaid", "refunded");
        }
        if (args.length == 4 && Set.of("recover", "resolve").contains(args[0]) && sender.hasPermission("recolorfulmap.admin")) return List.of("confirm");
        return List.of();
    }
}
