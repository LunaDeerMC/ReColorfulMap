package cn.lunadeer.reColorfulMap.utils.VaultConnect;

import cn.lunadeer.reColorfulMap.utils.XLogger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class VaultConnect implements Listener {

    public static VaultConnect instance;
    private VaultInterface vaultInstance = null;
    private JavaPlugin plugin;

    public VaultConnect(JavaPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnable(ServiceRegisterEvent event) {
    }

    public boolean economyAvailable() {
        if (vaultInstance == null) {
            if (foundClass("net.milkbowl.vault.economy.Economy")) {
                vaultInstance = new Vault();
            } else if (foundClass("net.milkbowl.vault2.economy.Economy")) {
                vaultInstance = new Vault2();
            } else {
                XLogger.err("No Vault or VaultUnlocked found");
                return false;
            }
            if (!vaultInstance.init(plugin)) {
                vaultInstance = null;
                XLogger.err("No economy plugin available (Vault found but no economy plugin)");
                return false;
            }
        }
        return true;
    }

    public String currencyNamePlural() {
        if (economyAvailable()) {
            return vaultInstance.currencyNamePlural();
        }
        return "";
    }

    public String currencyNameSingular() {
        if (economyAvailable()) {
            return vaultInstance.currencyNameSingular();
        }
        return "";
    }

    public void withdrawPlayer(Player player, double amount) {
        if (!economyAvailable()) {
            throw new IllegalStateException("Economy service is unavailable");
        }
        vaultInstance.withdrawPlayer(player, amount);
    }

    public void depositPlayer(Player player, double amount) {
        if (!economyAvailable()) {
            throw new IllegalStateException("Economy service is unavailable");
        }
        vaultInstance.depositPlayer(player, amount);
    }

    public double getBalance(Player player) {
        if (economyAvailable()) {
            return vaultInstance.getBalance(player);
        }
        return 0;
    }

    private static boolean foundClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
