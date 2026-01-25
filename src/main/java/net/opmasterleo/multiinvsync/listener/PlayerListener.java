package net.opmasterleo.multiinvsync.listener;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;

public class PlayerListener implements Listener {
    
    private final MultiInvSyncPlugin plugin;
    
    public PlayerListener(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        plugin.getSyncManager().injectPlayer(player);
        
        plugin.getScheduler().runMainLater(() -> {
            Player source = findSourceFor(player);
            if (source != null) {
                plugin.getSyncManager().syncInventory(source);
                if (plugin.getConfigManager().isSyncMoney() && plugin.getEconomySyncManager() != null) {
                    plugin.getEconomySyncManager().syncBalanceFromSource(source);
                }
            }
        }, 20L);
    }

    private Player findSourceFor(Player joined) {
        // Find a player in the same sync group to copy from
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(joined)) {
                continue;
            }
            if (plugin.getConfigManager().isTeamsEnabled()) {
                if (plugin.getTeamManager().isInSameTeam(joined, online)) {
                    return online;
                }
            } else {
                return online;
            }
        }
        return null;
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getSyncManager().uninjectPlayer(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase();
        // Catch common inventory-affecting commands (/give, /i, /item, /loot, /clear, /kit, /replaceitem)
        if (msg.startsWith("/give") || msg.startsWith("/i ") || msg.startsWith("/item ") ||
            msg.startsWith("/loot") || msg.startsWith("/clear") || msg.startsWith("/kit") ||
            msg.startsWith("/replaceitem") || msg.startsWith("/mi ") || msg.startsWith("/essentials:give")) {
            plugin.getScheduler().runMainLater(() -> plugin.getSyncManager().syncInventory(event.getPlayer()), 2L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExpChange(PlayerExpChangeEvent event) {
        if (!plugin.getConfigManager().isSyncExperience()) {
            return;
        }
        plugin.getScheduler().runMainLater(() -> plugin.getSyncManager().syncInventory(event.getPlayer()), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLevelChange(PlayerLevelChangeEvent event) {
        if (!plugin.getConfigManager().isSyncExperience()) {
            return;
        }
        plugin.getScheduler().runMainLater(() -> plugin.getSyncManager().syncInventory(event.getPlayer()), 1L);
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfigManager().isSharedDeath()) {
            return;
        }
        
        Player player = event.getEntity();
        
        if (event.getKeepInventory()) {
            return; // respect keepInventory; nothing to clear
        }

        plugin.getScheduler().runMainLater(() -> {
            // Clear everyone except the dead player (dead player's items drop naturally)
            plugin.getSyncManager().clearAllInventories(player, false);
            
            if (plugin.getConfigManager().isBroadcastSharedDeath()) {
                String message = plugin.getConfigManager().getDeathMessage()
                    .replace("{player}", player.getName());
                message = ChatColor.translateAlternateColorCodes('&', message);
                
                for (Player target : plugin.getTeamManager().getTeamMembers(player)) {
                    target.sendMessage(message);
                }
            }
        }, 1L);
    }
}
