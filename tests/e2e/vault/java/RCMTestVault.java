package test.recolorfulmap.vault;

import com.google.gson.GsonBuilder;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Disposable deterministic Vault provider. Real plugin economy calls cross Bukkit ServicesManager. */
public final class RCMTestVault extends JavaPlugin {
    private double balance = 1_000_000;
    private boolean failWithdraw;
    private final List<Map<String, Object>> transactions = new ArrayList<>();

    @Override public void onEnable() {
        getDataFolder().mkdirs();
        Economy provider = (Economy) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{Economy.class}, (proxy, method, args) -> {
            String name = method.getName();
            return switch (name) {
                case "isEnabled", "hasAccount", "createPlayerAccount" -> true;
                case "hasBankSupport" -> false;
                case "getName", "toString" -> "RCM deterministic E2E economy";
                case "currencyNameSingular" -> "coin";
                case "currencyNamePlural" -> "coins";
                case "fractionalDigits" -> 2;
                case "format" -> String.format(java.util.Locale.ROOT, "%.2f coins", args[0]);
                case "getBalance" -> balance;
                case "has" -> balance >= ((Number) args[args.length - 1]).doubleValue();
                case "withdrawPlayer", "depositPlayer" -> transaction(name, ((Number) args[args.length - 1]).doubleValue());
                case "getBanks" -> List.of();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Unexpected economy API: " + method);
            };
        });
        getServer().getServicesManager().register(Economy.class, provider, this, ServicePriority.Highest);
        save();
    }

    private EconomyResponse transaction(String kind, double amount) {
        boolean success = !(kind.equals("withdrawPlayer") && (failWithdraw || amount > balance));
        if (success) balance += kind.equals("depositPlayer") ? amount : -amount;
        transactions.add(Map.of("kind", kind, "amount", amount, "success", success, "balance", balance, "tick", getServer().getCurrentTick()));
        save();
        return new EconomyResponse(amount, balance, success ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                success ? null : "E2E withdrawal rejected");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.getName().equals("RCMTester") && !sender.getName().equals("CONSOLE")) return true;
        if (args.length > 0 && args[0].equals("reset")) {
            balance = args.length > 1 ? Double.parseDouble(args[1]) : 1_000_000;
            transactions.clear(); failWithdraw = false;
        } else if (args.length > 1 && args[0].equals("fail")) failWithdraw = args[1].equals("on");
        save(); return true;
    }

    private void save() {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("balance", balance); state.put("failWithdraw", failWithdraw); state.put("transactions", transactions);
            state.put("updated", System.currentTimeMillis());
            var file = getDataFolder().toPath().resolve("economy.json");
            var temporary = file.resolveSibling("economy.json.tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(state));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
