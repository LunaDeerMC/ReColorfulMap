package cn.lunadeer.reColorfulMap.utils.VaultConnect;

import cn.lunadeer.reColorfulMap.utils.XLogger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class Vault implements VaultInterface {

    private Economy econ = null;

    @Override
    public boolean init(JavaPlugin plugin) {
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            econ = rsp.getProvider();
            return true;
        }
        XLogger.err("Vault unable to load economy service.");
        return false;
    }

    @Override
    public String currencyNamePlural() {
        return econ.currencyNamePlural();
    }

    @Override
    public String currencyNameSingular() {
        return econ.currencyNameSingular();
    }

    @Override
    public void withdrawPlayer(Player player, double amount) {
        EconomyResponse response = econ.withdrawPlayer(player, amount);
        if (!response.transactionSuccess()) {
            throw new TransactionRejectedException(response.errorMessage);
        }
    }

    @Override
    public void depositPlayer(Player player, double amount) {
        EconomyResponse response = econ.depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            throw new TransactionRejectedException(response.errorMessage);
        }
    }

    @Override
    public double getBalance(Player player) {
        return econ.getBalance(player);
    }
}
