package test.recolorfulmap.copy;

import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import com.google.gson.GsonBuilder;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Disposable-server fixture and observer. Native copies are requested by actual client packets. */
public final class CopyProbe extends JavaPlugin implements Listener {
    private static final NamespacedKey IMAGE = new NamespacedKey("re_colorful_map", "image_path");
    private final List<Map<String, String>> removals = new CopyOnWriteArrayList<>();
    private String original;
    private String tiles;
    private String action = "starting";
    private int commands;

    @Override public void onEnable() {
        try {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(getDataFolder().toPath().resolve("fixture.properties"))) {
                properties.load(reader);
            }
            original = properties.getProperty("original");
            tiles = properties.getProperty("tiles");
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler public void joined(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.getName().equals("CopyProbe")) return;
        player.setOp(true);
        player.setInvulnerable(true);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.getScheduler().runAtFixedRate(this, task -> snapshot(player), null, 1, 4);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.getName().equals("CopyProbe")) return true;
        try {
            switch (args[0]) {
                case "craft-prepare" -> {
                    reset(player, GameMode.SURVIVAL);
                    CraftingInventory inventory = (CraftingInventory) player.openWorkbench(player.getLocation(), true).getTopInventory();
                    ItemStack image = image(player, "maps/" + original + "/thumb.png");
                    inventory.setMatrix(new ItemStack[]{image, new ItemStack(Material.MAP, 4), new ItemStack(Material.MAP, 3), null, null, null, null, null, null});
                }
                case "cart-prepare" -> {
                    reset(player, GameMode.SURVIVAL);
                    Inventory inventory = player.openCartographyTable(player.getLocation(), true).getTopInventory();
                    inventory.setItem(0, image(player, "maps/" + original + "/thumb.png"));
                    inventory.setItem(1, new ItemStack(Material.MAP, 5));
                }
                case "command-prepare" -> {
                    reset(player, GameMode.SURVIVAL);
                    player.getInventory().setItem(0, image(player, "maps/" + original + "/thumb.png"));
                }
                case "legacy-prepare" -> {
                    reset(player, GameMode.CREATIVE);
                    player.getInventory().setItem(0, image(player, "maps/8aa62018-68c2-4b20-92b2-704f85c335ab/thumb.png"));
                }
                case "fill" -> {
                    for (int slot = 1; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
                }
                case "pause-copy" -> {
                    // Fault injection: keep the copy worker waiting while the real player changes state.
                    Object storage = ReColorfulMap.getInstance().getMapStorage();
                    CountDownLatch acquired = new CountDownLatch(1);
                    Thread pause = new Thread(() -> {
                        synchronized (storage) {
                            acquired.countDown();
                            try { Thread.sleep(2000); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        }
                    }, "CopyProbe-test-delay");
                    pause.setDaemon(true); pause.start();
                    if (!acquired.await(1, TimeUnit.SECONDS)) throw new IllegalStateException("Test delay could not acquire storage");
                }
                case "tile-prepare" -> {
                    reset(player, GameMode.CREATIVE);
                    for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++)
                        player.getInventory().setItem(x * 2 + y, image(player, "maps/" + tiles + "/" + x + "_" + y + ".png"));
                }
                case "destroy" -> {
                    player.closeInventory();
                    int slot = Integer.parseInt(args[1]);
                    ItemStack stack = player.getInventory().getItem(slot);
                    if (stack == null || path(stack) == null) throw new IllegalStateException("No test image in selected slot");
                    player.getInventory().setItem(slot, null);
                    Item item = player.getWorld().dropItem(player.getLocation(), stack);
                    item.setPickupDelay(32767);
                    item.getScheduler().run(this, task -> {
                        if (args[2].equals("despawn")) {
                            item.setUnlimitedLifetime(false); item.setWillAge(true); item.setTicksLived(5999);
                        } else if (args[2].equals("death")) {
                            item.setHealth(1); item.setFireTicks(80);
                        } else {
                            var location = item.getLocation(); location.setY(item.getWorld().getMinHeight() - 128);
                            item.teleportAsync(location);
                        }
                    }, null);
                }
                case "hold" -> {
                    player.closeInventory();
                    player.getInventory().setHeldItemSlot(Integer.parseInt(args[1]));
                }
                case "resume" -> { player.setGameMode(GameMode.CREATIVE); player.getInventory().setHeldItemSlot(1); }
                default -> throw new IllegalArgumentException("Unknown probe command");
            }
            commands++;
            action = String.join(" ", args);
            player.updateInventory();
            snapshot(player);
        } catch (Exception failure) {
            action = "FAILED " + failure;
            getLogger().log(java.util.logging.Level.SEVERE, "Probe failed", failure);
            snapshot(player);
        }
        return true;
    }

    private void reset(Player player, GameMode mode) {
        player.closeInventory();
        player.getInventory().clear();
        player.setItemOnCursor(null);
        player.setGameMode(mode);
        player.setAllowFlight(true); player.setFlying(true);
        player.getInventory().setHeldItemSlot(0);
    }

    private ItemStack image(Player player, String path) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(getServer().createMap(player.getWorld()));
        MapItemData.setReference(meta, MapStorage.ImageReference.parse(path).orElseThrow());
        item.setItemMeta(meta);
        return item;
    }

    private static String path(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof MapMeta meta)) return null;
        return meta.getPersistentDataContainer().get(IMAGE, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void removed(EntityRemoveEvent event) {
        if (event.getEntity() instanceof Item item) {
            String path = path(item.getItemStack());
            if (path != null) removals.add(Map.of("path", path, "cause", event.getCause().name()));
        }
    }

    private void snapshot(Player player) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("action", action); state.put("commands", commands); state.put("removals", removals);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int slot = 0; slot < 36; slot++) {
                ItemStack item = player.getInventory().getItem(slot);
                String path = path(item);
                if (path == null) continue;
                MapMeta meta = (MapMeta) item.getItemMeta();
                items.add(Map.of("slot", slot, "path", path, "amount", item.getAmount(), "mapId", meta.getMapView().getId()));
            }
            state.put("items", items);
            int occupied = 0;
            for (ItemStack item : player.getInventory().getStorageContents()) if (item != null && !item.getType().isAir()) occupied++;
            state.put("occupiedSlots", occupied);
            state.put("heldSlot", player.getInventory().getHeldItemSlot());
            Inventory top = player.getOpenInventory().getTopInventory();
            state.put("top", top.getType().name());
            List<Integer> amounts = new ArrayList<>();
            for (ItemStack item : top.getContents()) amounts.add(item == null ? 0 : item.getAmount());
            state.put("topAmounts", amounts);
            Path directory = getDataFolder().toPath();
            Path temporary = directory.resolve("state.tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(state));
            Files.move(temporary, directory.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) { getLogger().log(java.util.logging.Level.WARNING, "Cannot record probe state", failure); }
    }
}
