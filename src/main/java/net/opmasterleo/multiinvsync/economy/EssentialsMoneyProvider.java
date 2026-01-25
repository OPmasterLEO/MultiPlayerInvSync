package net.opmasterleo.multiinvsync.economy;

import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.lang.reflect.Method;

public class EssentialsMoneyProvider implements MoneyProvider {
    private final MultiInvSyncPlugin plugin;
    private Object essentials;
    private Method getUser;
    private Method getMoney;
    private Method setMoney;
    private Method getBase;
    private Method getPlayerFromUser;
    private boolean available;

    public EssentialsMoneyProvider(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        try {
            Plugin ess = plugin.getServer().getPluginManager().getPlugin("Essentials");
            if (ess == null || !ess.isEnabled()) {
                available = false;
                return;
            }
            essentials = ess;
            Class<?> essClass = Class.forName("com.earth2me.essentials.Essentials");
            Class<?> userClass = Class.forName("com.earth2me.essentials.User");

            getUser = essClass.getMethod("getUser", Player.class);
            getMoney = userClass.getMethod("getMoney");
            setMoney = userClass.getMethod("setMoney", BigDecimal.class);

            try {
                getBase = userClass.getMethod("getBase");
            } catch (NoSuchMethodException ignored) {
                getBase = null;
            }

            try {
                getPlayerFromUser = userClass.getMethod("getPlayer");
            } catch (NoSuchMethodException ignored) {
                getPlayerFromUser = null;
            }

            available = true;
        } catch (Exception e) {
            available = false;
            plugin.getLogger().warning("Failed to initialize Essentials money provider: " + e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "EssentialsX";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public double getBalance(Player player) {
        if (!available) {
            return 0.0D;
        }
        try {
            Object user = getUser.invoke(essentials, player);
            if (user == null) {
                return 0.0D;
            }
            Object money = getMoney.invoke(user);
            if (money instanceof BigDecimal bd) {
                return bd.doubleValue();
            }
            if (money instanceof Number number) {
                return number.doubleValue();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get balance from EssentialsX: " + e.getMessage());
        }
        return 0.0D;
    }

    @Override
    public void setBalance(Player player, double amount) {
        if (!available) {
            return;
        }
        try {
            Object user = getUser.invoke(essentials, player);
            if (user == null) {
                return;
            }
            setMoney.invoke(user, BigDecimal.valueOf(amount));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to set balance in EssentialsX: " + e.getMessage());
        }
    }

    public Class<?> getBalanceUpdateEventClass() {
        try {
            return Class.forName("net.ess3.api.events.UserBalanceUpdateEvent");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public Player resolvePlayerFromUser(Object user) {
        try {
            if (user == null) {
                return null;
            }
            if (getPlayerFromUser != null) {
                Object base = getPlayerFromUser.invoke(user);
                if (base instanceof Player player) {
                    return player;
                }
            }
            if (getBase != null) {
                Object base = getBase.invoke(user);
                if (base instanceof Player player) {
                    return player;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to resolve player from Essentials user: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void shutdown() {
        available = false;
    }
}
