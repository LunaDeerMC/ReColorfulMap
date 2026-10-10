package test.recolorfulmap.folia;

import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test-only observer. All player/item reads and mutations execute on their owning entity region. */
public final class RCMFoliaE2E extends JavaPlugin implements Listener {
    private static final NamespacedKey IMAGE = new NamespacedKey("re_colorful_map", "image_path");
    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private volatile String destruction = "none";
    private final List<ItemFrame> frames = new ArrayList<>();

    @Override public void onEnable() {
        getDataFolder().mkdirs();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Folia observer enabled with entity schedulers only");
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.getName().equals("RCMFolia")) return;
        player.setOp(true);
        player.getScheduler().runAtFixedRate(this, task -> snapshot(player), null, 1, 5);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.getName().equals("RCMFolia")) return true;
        try {
            String action = args.length == 0 ? "state" : args[0];
            switch (action) {
                case "setup" -> {
                    player.setGameMode(GameMode.SURVIVAL);
                    player.setAllowFlight(true);
                    player.setFlying(true);
                    player.setInvulnerable(true);
                    player.getInventory().clear();
                    player.getInventory().setHeldItemSlot(0);
                }
                case "wall" -> {
                    for (ItemFrame frame : frames) if (frame.isValid() && Bukkit.isOwnedByCurrentRegion(frame)) frame.remove();
                    frames.clear();
                    Location origin = player.getLocation().getBlock().getLocation().add(0, 1, -3);
                    for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) {
                        Location tile = origin.clone().add(x, y, 0);
                        if (!Bukkit.isOwnedByCurrentRegion(tile)) throw new IllegalStateException("Fixture wall crosses a region");
                        tile.clone().add(0, 0, -1).getBlock().setType(Material.STONE);
                        tile.getBlock().setType(Material.AIR);
                        ItemFrame frame = player.getWorld().spawn(tile.clone().add(0.5, 0.5, 0.5), ItemFrame.class,
                                entity -> entity.setFacingDirection(BlockFace.SOUTH, true));
                        frames.add(frame);
                    }
                }
                case "teleport-frame" -> {
                    ItemFrame frame = frames.get(Integer.parseInt(args[1]));
                    Location target = frame.getLocation().clone().add(0, -1, 2.5); target.setYaw(180); target.setPitch(0);
                    player.teleportAsync(target);
                }
                case "equip" -> {
                    for (int slot = 0; slot < 36; slot++) {
                        ItemStack item = player.getInventory().getItem(slot);
                        if (path(item) != null) {
                            ItemStack old = player.getInventory().getItem(0);
                            player.getInventory().setItem(0, item);
                            player.getInventory().setItem(slot, old);
                            player.getInventory().setHeldItemSlot(0);
                            break;
                        }
                    }
                }
                case "collect" -> {
                    for (Entity nearby : player.getNearbyEntities(8, 8, 8)) {
                        if (nearby instanceof Item item && Bukkit.isOwnedByCurrentRegion(item)) {
                            item.setPickupDelay(0);
                            item.setVelocity(new Vector());
                            item.teleportAsync(player.getLocation());
                        }
                    }
                }
                case "drop-mode" -> destruction = args[1];
                case "state" -> { }
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            }
            record("command", Map.of("action", action));
            snapshot(player);
        } catch (Exception failure) {
            record("failure", Map.of("message", failure.toString()));
            getLogger().log(java.util.logging.Level.SEVERE, "Folia observer command failed", failure);
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void dropped(PlayerDropItemEvent event) {
        if (!event.getPlayer().getName().equals("RCMFolia")) return;
        Item item = event.getItemDrop();
        String reference = path(item.getItemStack());
        if (reference == null) return;
        record("drop", Map.of("path", reference, "mode", destruction));
        if (destruction.equals("void")) {
            item.setPickupDelay(Integer.MAX_VALUE);
            item.getScheduler().run(this, task -> {
                var location = item.getLocation();
                location.setY(item.getWorld().getMinHeight() - 100);
                item.teleportAsync(location);
            }, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pickedUp(EntityPickupItemEvent event) {
        String reference = path(event.getItem().getItemStack());
        if (reference != null) record("pickup", Map.of("path", reference));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void removed(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;
        String reference = path(item.getItemStack());
        if (reference != null) record("remove", Map.of("path", reference, "cause", event.getCause().name()));
    }

    private void record(String event, Map<String, Object> fields) {
        Map<String, Object> row = new LinkedHashMap<>(fields);
        row.put("event", event);
        events.add(row);
        getLogger().info("FOLIA_E2E " + new GsonBuilder().create().toJson(row));
    }

    private void snapshot(Player player) {
        try {
            List<Map<String, Object>> inventory = new ArrayList<>();
            for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                ItemStack item = player.getInventory().getItem(slot);
                String reference = path(item);
                if (reference == null || !(item.getItemMeta() instanceof MapMeta meta)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("slot", slot);
                entry.put("path", reference);
                entry.put("amount", item.getAmount());
                if (meta.getMapView() != null) {
                    entry.put("mapId", meta.getMapView().getId());
                    entry.put("renderers", meta.getMapView().getRenderers().stream()
                            .map(renderer -> renderer.getClass().getName()).toList());
                }
                inventory.add(entry);
            }
            List<Map<String, Object>> walls = new ArrayList<>();
            for (ItemFrame frame : frames) {
                if (!Bukkit.isOwnedByCurrentRegion(frame)) continue;
                ItemStack item = frame.getItem();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("entityId", frame.getEntityId()); entry.put("path", path(item));
                if (item.getItemMeta() instanceof MapMeta meta && meta.getMapView() != null) entry.put("mapId", meta.getMapView().getId());
                walls.add(entry);
            }
            Map<String, Object> state = Map.of("inventory", inventory, "frames", walls, "events", List.copyOf(events),
                    "player", player.getName(), "time", System.currentTimeMillis());
            var directory = getDataFolder().toPath();
            var temporary = directory.resolve("state.json.tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(state));
            Files.move(temporary, directory.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "Folia snapshot failed", failure);
        }
    }

    private static String path(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof MapMeta meta)) return null;
        return meta.getPersistentDataContainer().get(IMAGE, PersistentDataType.STRING);
    }
}
