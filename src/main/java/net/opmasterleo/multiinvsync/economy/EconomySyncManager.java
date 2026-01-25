package net.opmasterleo.multiinvsync.economy;

import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EconomySyncManager {
    private final MultiInvSyncPlugin plugin;
    private MoneyProvider provider;
    private boolean enabled;
    private final Set<UUID> syncingMoney = ConcurrentHashMap.newKeySet();

    public EconomySyncManager(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        enabled = plugin.getConfigManager().isSyncMoney();
        if (!enabled) {
            plugin.getLogger().info("Economy sync disabled - skipping");
            return;
        }

        provider = createProvider(plugin.getConfigManager().getEconomyProvider());
        if (provider == null || !provider.isAvailable()) {
            plugin.getLogger().warning("Economy sync requested but no provider available");
            enabled = false;
            return;
        }

        registerBalanceListener();
        plugin.getLogger().info("Economy sync enabled via provider: " + provider.getName());
    }

    public void shutdown() {
        enabled = false;
        if (provider != null) {
            provider.shutdown();
        }
        syncingMoney.clear();
    }

    public void syncBalanceFromSource(Player source) {
        if (!enabled || provider == null || !provider.isAvailable()) {
            return;
        }
        double balance = provider.getBalance(source);
        syncBalance(source, balance);
    }

    private void syncBalance(Player source, double balance) {
        if (!enabled || provider == null || !provider.isAvailable()) {
            return;
        }

        UUID sourceId = source.getUniqueId();
        if (!syncingMoney.add(sourceId)) {
            return;
        }

        Collection<Player> targets = plugin.getTeamManager().getTeamMembers(source);
        if (targets.isEmpty()) {
            syncingMoney.remove(sourceId);
            return;
        }

        plugin.getScheduler().runMain(() -> {
            try {
                for (Player target : targets) {
                    if (target.getUniqueId().equals(sourceId)) {
                        continue;
                    }
                    UUID targetId = target.getUniqueId();
                    syncingMoney.add(targetId);
                    try {
                        provider.setBalance(target, balance);
                    } finally {
                        syncingMoney.remove(targetId);
                    }
                }
            } finally {
                syncingMoney.remove(sourceId);
            }
        });
    }

    private MoneyProvider createProvider(String providerName) {
        if (providerName == null || providerName.equalsIgnoreCase("essentials") || providerName.equalsIgnoreCase("auto")) {
            MoneyProvider essentials = new EssentialsMoneyProvider(plugin);
            if (essentials.isAvailable()) {
                return essentials;
            }
            if (!"auto".equalsIgnoreCase(providerName)) {
                plugin.getLogger().warning("EssentialsX provider requested but unavailable");
            }
        }
        return null;
    }

    private void registerBalanceListener() {
        if (!(provider instanceof EssentialsMoneyProvider essentialsProvider)) {
            return;
        }

        Class<?> eventClass = essentialsProvider.getBalanceUpdateEventClass();
        if (eventClass == null) {
            plugin.getLogger().warning("EssentialsX balance event not found; money sync will not auto-trigger");
            return;
        }

        Listener dummyListener = new Listener() { };
        EventExecutor executor = (listener, event) -> handleEssentialsBalanceEvent(event, essentialsProvider);

        try {
            plugin.getServer().getPluginManager().registerEvent((Class<? extends Event>) eventClass, dummyListener, EventPriority.MONITOR, executor, plugin, true);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register EssentialsX balance listener: " + e.getMessage());
        }
    }

    private void handleEssentialsBalanceEvent(Event event, EssentialsMoneyProvider essentialsProvider) {
        if (event == null || !enabled || provider == null || !provider.isAvailable()) {
            return;
        }
        try {
            Object user = event.getClass().getMethod("getUser").invoke(event);
            if (user == null) {
                return;
            }
            Player player = essentialsProvider.resolvePlayerFromUser(user);
            if (player == null) {
                return;
            }
            if (syncingMoney.contains(player.getUniqueId())) {
                return; // ignore cascades from our own writes
            }
            syncBalanceFromSource(player);
        } catch (NoSuchMethodException ignored) {
            plugin.getLogger().warning("EssentialsX balance event missing getUser(); money sync skipped");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle EssentialsX balance event: " + e.getMessage());
        }
    }
}
