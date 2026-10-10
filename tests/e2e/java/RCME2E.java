package test.recolorfulmap;

import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test-only fixture/observation plugin. Player interactions come from a real protocol client. */
public final class RCME2E extends JavaPlugin implements Listener {
    private static final NamespacedKey IMAGE = new NamespacedKey("re_colorful_map", "image_path");
    private static final String LEGACY = "aabbccdd-0000-4000-8000-000000000001";
    private static final int X = 100, Y = 81, Z = 100;
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final List<UUID> frames = new ArrayList<>();
    private final Map<UUID, String> itemPaths = new HashMap<>();
    private UUID deniedFrame;
    private boolean sourceOnly;
    private boolean cancelSpawn;
    private boolean cancelDespawn;
    private UUID lastDropped;
    private String destruction = "";
    private String lastError = "";
    private String phase = "enabled";
    private BlockFace facing = BlockFace.SOUTH;

    @Override public void onEnable() {
        getDataFolder().mkdirs();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Player player = Bukkit.getPlayerExact("RCMTester");
            if (player != null) snapshot(player);
        }, 10, 5);
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        if (!event.getPlayer().getName().equals("RCMTester")) return;
        event.getPlayer().setOp(true);
        Bukkit.getScheduler().runTaskLater(this, () -> snapshot(event.getPlayer()), 10);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.getName().equals("RCMTester")) return true;
        try {
            String action = args.length == 0 ? "state" : args[0];
            switch (action) {
                case "setup" -> setup(player);
                case "wall" -> {
                    if (args.length > 1) facing = BlockFace.valueOf(args[1].toUpperCase(java.util.Locale.ROOT));
                    wall(player.getWorld());
                }
                case "equip" -> equip(player);
                case "collect" -> collect(player, args.length > 1 ? args[1] : null);
                case "protect" -> deniedFrame = args[1].equals("off") ? null : frames.get(Integer.parseInt(args[1]));
                case "protect-source" -> {
                    deniedFrame = args[1].equals("off") ? null : frames.get(Integer.parseInt(args[1]));
                    sourceOnly = !args[1].equals("off");
                }
                case "cancel-spawn" -> cancelSpawn = args[1].equals("on");
                case "cancel-despawn" -> cancelDespawn = args[1].equals("on");
                case "age-drop" -> ((Item) Bukkit.getEntity(lastDropped)).setTicksLived(5999);
                case "track" -> {
                    frames.clear();
                    for (String id : args[1].split(",")) frames.add(UUID.fromString(id));
                }
                case "merge-items" -> {
                    ItemStack held = player.getInventory().getItemInMainHand();
                    if (imagePath(held) == null) throw new IllegalStateException("Need image in hand");
                    player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                    for (int copy = 0; copy < 2; copy++) {
                        Item item = player.getWorld().dropItem(new Location(player.getWorld(), X + 2.5, Y, Z + 2.5), held.clone());
                        // Vanilla's permanent 32767 pickup delay also disables
                        // merging; a finite delay permits a real MERGE event.
                        item.setPickupDelay(200); item.setVelocity(new Vector());
                    }
                }
                case "full" -> {
                    for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i) == null)
                        player.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64));
                }
                case "clear-stone" -> {
                    for (int i = 0; i < 36; i++) {
                        ItemStack item = player.getInventory().getItem(i);
                        if (item != null && item.getType() == Material.COBBLESTONE) player.getInventory().setItem(i, null);
                    }
                }
                case "drop-mode" -> destruction = args[1];
                case "teleport-frame" -> {
                    ItemFrame frame = (ItemFrame) Bukkit.getEntity(frames.get(Integer.parseInt(args[1])));
                    Location target = frame.getLocation().clone().add(facing.getModX() * 2.5, -1, facing.getModZ() * 2.5);
                    target.setYaw(yaw()); target.setPitch(0);
                    player.teleport(target);
                }
                case "remove-frame" -> Bukkit.getEntity(frames.get(Integer.parseInt(args[1]))).remove();
                case "mix-frame" -> {
                    ItemFrame frame = (ItemFrame) Bukkit.getEntity(frames.get(Integer.parseInt(args[1])));
                    player.getInventory().addItem(frame.getItem());
                    frame.setItem(map(player.getWorld(), null, "Vanilla neighbour"));
                }
                case "unload" -> unload(player);
                case "reload-chunk" -> {
                    player.teleport(new Location(player.getWorld(), X + 1.0, Y - 1, Z + 3, 180, 0));
                    player.getWorld().getChunkAt(X >> 4, Z >> 4).load();
                }
                case "legacy-fixture" -> legacyFixture(player);
                case "migration-check" -> migrationCheck(player);
                case "state" -> { }
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            }
            phase = action;
            lastError = "";
            record("command", Map.of("action", action));
        } catch (Exception error) {
            lastError = error.toString();
            getLogger().warning(lastError);
        }
        snapshot(player);
        return true;
    }

    private void setup(Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);
        player.getInventory().clear();
        player.getEnderChest().clear();
        player.setFoodLevel(20);
        World world = player.getWorld();
        world.setTime(6000);
        for (Entity entity : world.getNearbyEntities(new Location(world, X, Y, Z), 30, 30, 30))
            if (entity instanceof Item) entity.remove();
        for (int x = X - 4; x <= X + 5; x++) for (int z = Z - 3; z <= Z + 7; z++) {
            world.getBlockAt(x, Y - 2, z).setType(Material.STONE);
            for (int y = Y - 1; y <= Y + 4; y++) world.getBlockAt(x, y, z).setType(Material.AIR);
        }
        facing = BlockFace.SOUTH;
        wall(world);
        player.teleport(new Location(world, X + 0.5, Y - 1, Z + 3, 180, 0));
        player.getInventory().setHeldItemSlot(0);
        deniedFrame = null; sourceOnly = false; cancelSpawn = false; cancelDespawn = false; destruction = "";
    }

    private void wall(World world) {
        for (Entity entity : world.getNearbyEntities(new Location(world, X + 1, Y + 1, Z), 4, 4, 4))
            if (entity instanceof ItemFrame) entity.remove();
        frames.clear();
        // Order: bottom-left origin, top-left, bottom-right, top-right.
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) {
            int tileX = X + facing.getModZ() * x, tileZ = Z - facing.getModX() * x;
            world.getBlockAt(tileX - facing.getModX(), Y + y, tileZ - facing.getModZ()).setType(Material.STONE);
            world.getBlockAt(tileX, Y + y, tileZ).setType(Material.AIR);
            ItemFrame frame = world.spawn(new Location(world, tileX + 0.5, Y + y + 0.5, tileZ + 0.5), ItemFrame.class,
                    entity -> entity.setFacingDirection(facing, true));
            frame.setPersistent(true);
            frames.add(frame.getUniqueId());
        }
    }

    private float yaw() {
        return switch (facing) { case SOUTH -> 180; case NORTH -> 0; case EAST -> 90; case WEST -> 270; default -> 0; };
    }

    private static void equip(Player player) {
        for (int i = 0; i < 36; i++) {
            ItemStack candidate = player.getInventory().getItem(i);
            String path = imagePath(candidate);
            if (path != null && path.endsWith("/thumb.png")) {
                ItemStack old = player.getInventory().getItem(0);
                player.getInventory().setItem(0, candidate);
                if (i != 0) player.getInventory().setItem(i, old);
                player.getInventory().setHeldItemSlot(0);
                return;
            }
        }
        throw new IllegalStateException("No image thumbnail in player inventory");
    }

    private void collect(Player player, String expectedPath) {
        Item found = player.getWorld().getEntitiesByClass(Item.class).stream()
                .filter(item -> imagePath(item.getItemStack()) != null)
                .filter(item -> expectedPath == null || expectedPath.equals(imagePath(item.getItemStack())))
                .filter(item -> item.getLocation().distanceSquared(player.getLocation()) < 400)
                .findFirst().orElseThrow();
        found.setPickupDelay(0);
        player.teleport(found.getLocation());
    }

    private void unload(Player player) {
        World world = player.getWorld();
        player.teleport(new Location(world, X + 1024, Y + 2, Z + 1024));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            boolean unloaded = world.unloadChunk(X >> 4, Z >> 4, true);
            record("unload-result", Map.of("success", unloaded, "loaded", world.isChunkLoaded(X >> 4, Z >> 4)));
        }, 40);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void protect(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getUniqueId().equals(deniedFrame)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void protect(EntityDamageByEntityEvent event) {
        if (event.getEntity().getUniqueId().equals(deniedFrame)) {
            boolean playerAttack = event.getDamageSource().getCausingEntity() instanceof Player;
            record("protection-damage", Map.of("playerSource", playerAttack,
                    "type", event.getDamageSource().getDamageType().getKey().toString()));
            if (!sourceOnly || playerAttack) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void spawn(ItemSpawnEvent event) {
        if (cancelSpawn && imagePath(event.getEntity().getItemStack()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void observeSpawn(ItemSpawnEvent event) {
        remember(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void dropped(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        if (imagePath(item.getItemStack()) == null) return;
        lastDropped = item.getUniqueId();
        String mode = destruction;
        destruction = "";
        item.setVelocity(new Vector());
        item.setPickupDelay(32767);
        record("client-drop", Map.of("mode", mode, "entity", item.getEntityId(), "path", imagePath(item.getItemStack())));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            switch (mode) {
                case "damage" -> { item.setHealth(1); item.setFireTicks(80); }
                case "despawn" -> {
                    item.setUnlimitedLifetime(false);
                    item.setWillAge(true);
                    item.setTicksLived(5999);
                }
                case "void" -> item.teleport(new Location(item.getWorld(), X, item.getWorld().getMinHeight() - 128, Z));
                case "keep" -> item.teleport(new Location(item.getWorld(), X + 2.5, Y, Z + 2.5));
                default -> { }
            }
        }, 2);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void removed(EntityRemoveEvent event) {
        if (event.getEntity() instanceof Item item) {
            String path = imagePath(item.getItemStack());
            if (path == null) path = itemPaths.get(item.getUniqueId());
            if (path != null) record("remove", Map.of("cause", event.getCause().name(), "entity", item.getEntityId(), "path", path));
            itemPaths.remove(item.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void pickup(EntityPickupItemEvent event) {
        remember(event.getItem());
        if (imagePath(event.getItem().getItemStack()) != null)
            record("pickup", Map.of("cancelled", event.isCancelled(), "path", imagePath(event.getItem().getItemStack())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void despawn(ItemDespawnEvent event) {
        if (imagePath(event.getEntity().getItemStack()) != null)
            record("despawn", Map.of("cancelled", event.isCancelled(), "path", imagePath(event.getEntity().getItemStack())));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void cancelDespawn(ItemDespawnEvent event) {
        if (cancelDespawn && imagePath(event.getEntity().getItemStack()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void merge(ItemMergeEvent event) {
        remember(event.getEntity()); remember(event.getTarget());
        if (imagePath(event.getEntity().getItemStack()) != null)
            record("merge", Map.of("cancelled", event.isCancelled(), "path", imagePath(event.getEntity().getItemStack())));
    }

    private void remember(Item item) {
        String path = imagePath(item.getItemStack());
        if (path != null) itemPaths.put(item.getUniqueId(), path);
    }

    private static String imagePath(ItemStack item) {
        return item == null || !item.hasItemMeta() ? null : item.getItemMeta().getPersistentDataContainer().get(IMAGE, PersistentDataType.STRING);
    }

    private Map<String, Object> itemState(ItemStack item) {
        Map<String, Object> state = new LinkedHashMap<>();
        if (item == null) { state.put("type", "AIR"); return state; }
        state.put("type", item.getType().name()); state.put("amount", item.getAmount());
        String path = imagePath(item); state.put("path", path);
        if (path != null) state.put("fileExists", Files.isRegularFile(getDataFolder().toPath().getParent().resolve("ReColorfulMap").resolve(path)));
        if (item.getItemMeta() instanceof MapMeta meta) {
            MapView view = meta.getMapView();
            if (view != null) {
                state.put("mapId", view.getId());
                state.put("renderers", view.getRenderers().stream().map(renderer -> renderer.getClass().getName()).toList());
            }
            state.put("name", meta.getDisplayName());
            state.put("sentinel", meta.getPersistentDataContainer().get(new NamespacedKey("rcme2e", "sentinel"), PersistentDataType.STRING));
        }
        return state;
    }

    private void snapshot(Player player) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("phase", phase); state.put("error", lastError); state.put("tick", Bukkit.getCurrentTick());
            state.put("facing", facing.name());
            state.put("player", player.getUniqueId().toString()); state.put("world", player.getWorld().getName());
            state.put("worlds", worlds());
            state.put("x", player.getX()); state.put("y", player.getY()); state.put("z", player.getZ());
            state.put("hand", itemState(player.getInventory().getItemInMainHand()));
            state.put("inventory", java.util.Arrays.stream(player.getInventory().getContents()).map(this::itemState).toList());
            state.put("enderChest", java.util.Arrays.stream(player.getEnderChest().getContents()).map(this::itemState).toList());
            List<Map<String, Object>> frameStates = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                Entity entity = Bukkit.getEntity(frames.get(i));
                Map<String, Object> frame = new LinkedHashMap<>(); frame.put("index", i); frame.put("uuid", frames.get(i).toString());
                if (entity instanceof ItemFrame image) {
                    frame.put("entityId", image.getEntityId()); frame.put("item", itemState(image.getItem()));
                    frame.put("x", image.getX()); frame.put("y", image.getY()); frame.put("z", image.getZ());
                }
                frameStates.add(frame);
            }
            state.put("frames", frameStates);
            List<Map<String, Object>> drops = new ArrayList<>();
            for (Item item : player.getWorld().getEntitiesByClass(Item.class)) {
                if (imagePath(item.getItemStack()) == null) continue;
                remember(item);
                Map<String, Object> drop = new LinkedHashMap<>(itemState(item.getItemStack()));
                drop.put("entityId", item.getEntityId()); drop.put("uuid", item.getUniqueId().toString()); drops.add(drop);
            }
            state.put("drops", drops); state.put("events", List.copyOf(events));
            writeJson(getDataFolder().toPath().resolve("state.json"), state);
        } catch (Exception error) { getLogger().warning("snapshot: " + error); }
    }

    private void record(String event, Map<String, ?> values) {
        Map<String, Object> entry = new LinkedHashMap<>(values); entry.put("event", event); entry.put("tick", Bukkit.getCurrentTick());
        events.add(entry); if (events.size() > 200) events.remove(0);
        getLogger().info("E2E " + new GsonBuilder().create().toJson(entry));
    }

    private static ItemStack map(World world, String sentinel, String name) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta(); meta.setMapView(Bukkit.createMap(world));
        if (name != null) { meta.setDisplayName(name); meta.setLore(List.of("迁移保留测试 🌈", "unchanged lore")); }
        if (sentinel != null) {
            meta.getPersistentDataContainer().set(new NamespacedKey("rcme2e", "sentinel"), PersistentDataType.STRING, sentinel);
            meta.getPersistentDataContainer().set(new NamespacedKey("rcme2e", "bytes"), PersistentDataType.BYTE_ARRAY, new byte[]{0, 1, -1, 42});
        }
        item.setItemMeta(meta); return item;
    }

    private void legacyFixture(Player player) throws Exception {
        World world = player.getWorld();
        Path source = getDataFolder().toPath().getParent().resolve("ColorfulMap");
        Path artwork = source.resolve("maps").resolve(LEGACY); Files.createDirectories(artwork);
        BufferedImage original = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 256; x++) for (int y = 0; y < 256; y++) original.setRGB(x, y, new Color(x, y, 100).getRGB());
        ImageIO.write(original, "png", artwork.resolve("raw.png").toFile());
        ImageIO.write(original.getSubimage(0, 0, 128, 128), "png", artwork.resolve("thumb.png").toFile());
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++)
            ImageIO.write(original.getSubimage(x * 128, y * 128, 128, 128), "png", artwork.resolve(x + "_" + y + ".png").toFile());
        YamlConfiguration index = new YamlConfiguration();
        Map<String, Object> fixture = new LinkedHashMap<>();
        Map<Integer, String> maps = new LinkedHashMap<>();
        List<Map<String, Object>> locations = new ArrayList<>();
        java.util.function.BiFunction<String, String, ItemStack> legacyMap = (relative, sentinel) -> {
            ItemStack item = map(world, sentinel, "旧版地图 中文 🌈 " + sentinel);
            int id = ((MapMeta) item.getItemMeta()).getMapView().getId();
            maps.put(id, "maps/" + LEGACY + "/" + relative);
            index.set(world.getName() + "." + id, "maps/" + LEGACY + "/" + relative);
            return item;
        };
        player.getInventory().setItem(10, legacyMap.apply("thumb.png", "player-inventory"));
        player.getEnderChest().setItem(0, legacyMap.apply("thumb.png", "ender-chest"));
        world.getBlockAt(X + 4, Y - 1, Z + 1).setType(Material.CHEST);
        Chest chest = (Chest) world.getBlockAt(X + 4, Y - 1, Z + 1).getState();
        chest.getInventory().setItem(0, legacyMap.apply("thumb.png", "chest"));
        ItemStack shulker = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta boxMeta = (BlockStateMeta) shulker.getItemMeta();
        ShulkerBox box = (ShulkerBox) boxMeta.getBlockState();
        // This item has no custom_data/PDC before migration; its name/lore still survive.
        box.getInventory().setItem(0, legacyMap.apply("thumb.png", null));
        boxMeta.setBlockState(box); shulker.setItemMeta(boxMeta); chest.getInventory().setItem(1, shulker);
        ItemStack vanilla = map(world, "unindexed-vanilla", "原版地图 🌈");
        player.getInventory().setItem(11, vanilla); chest.getInventory().setItem(2, vanilla.clone());
        fixture.put("vanillaMapId", ((MapMeta) vanilla.getItemMeta()).getMapView().getId());
        facing = BlockFace.SOUTH;
        wall(world);
        for (int i = 0; i < 4; i++) {
            ItemFrame frame = (ItemFrame) Bukkit.getEntity(frames.get(i));
            String filename = (i / 2) + "_" + (1 - i % 2) + ".png";
            frame.setItem(legacyMap.apply(filename, "frame-" + i), false);
            locations.add(Map.of("kind", "frame", "uuid", frame.getUniqueId().toString(), "x", frame.getX(), "y", frame.getY(), "z", frame.getZ(), "file", filename));
        }
        Item dropped = world.dropItem(new Location(world, X + 3, Y, Z + 1), legacyMap.apply("thumb.png", "dropped"));
        dropped.setUnlimitedLifetime(true); dropped.setPickupDelay(32767); dropped.setPersistent(true); dropped.setGravity(false);
        locations.add(Map.of("kind", "dropped", "uuid", dropped.getUniqueId().toString()));
        locations.add(Map.of("kind", "chest", "x", X + 4, "y", Y - 1, "z", Z + 1));
        fixture.put("world", world.getName()); fixture.put("worldUid", world.getUID().toString());
        fixture.put("worlds", worlds());
        fixture.put("playerUuid", player.getUniqueId().toString()); fixture.put("artworkUuid", LEGACY);
        fixture.put("maps", maps); fixture.put("locations", locations);
        index.save(source.resolve("maps/maps.yml").toFile());
        player.saveData(); world.save();
        writeJson(getDataFolder().toPath().resolve("legacy-fixture.json"), fixture);
    }

    private void migrationCheck(Player player) throws Exception {
        Path path = getDataFolder().toPath().resolve("legacy-fixture.json");
        Map<?, ?> fixture = new GsonBuilder().create().fromJson(Files.readString(path), Map.class);
        frames.clear();
        for (Object location : (List<?>) fixture.get("locations")) {
            Map<?, ?> entry = (Map<?, ?>) location;
            if (entry.get("kind").equals("frame")) frames.add(UUID.fromString((String) entry.get("uuid")));
        }
        player.openInventory(((Chest) player.getWorld().getBlockAt(X + 4, Y - 1, Z + 1).getState()).getInventory());
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("inventory", itemState(player.getInventory().getItem(10)));
        observed.put("enderChest", itemState(player.getEnderChest().getItem(0)));
        observed.put("vanilla", itemState(player.getInventory().getItem(11)));
        Chest chest = (Chest) player.getWorld().getBlockAt(X + 4, Y - 1, Z + 1).getState();
        observed.put("chest", itemState(chest.getInventory().getItem(0)));
        BlockStateMeta shulkerMeta = (BlockStateMeta) chest.getInventory().getItem(1).getItemMeta();
        ShulkerBox nested = (ShulkerBox) shulkerMeta.getBlockState();
        player.openInventory(nested.getInventory());
        observed.put("shulker", itemState(nested.getInventory().getItem(0)));
        writeJson(getDataFolder().toPath().resolve("migration-observed.json"), observed);
        player.closeInventory();
    }

    private static void writeJson(Path target, Object value) throws Exception {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(value));
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<Map<String, String>> worlds() {
        return Bukkit.getWorlds().stream().map(world -> Map.of("name", world.getName(), "key", world.getKey().toString(),
                "uuid", world.getUID().toString(), "folder", world.getWorldFolder().getAbsolutePath())).toList();
    }
}
