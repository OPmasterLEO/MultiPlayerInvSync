package net.opmasterleo.multiinvsync.team;

import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;
import net.opmasterleo.multiinvsync.team.providers.BetterTeamsProvider;
import org.bukkit.entity.Player;

import java.util.*;

public class TeamManager {
    
    private final MultiInvSyncPlugin plugin;
    private TeamProvider activeProvider;
    private final List<TeamProvider> availableProviders = new ArrayList<>();
    
    public TeamManager(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
    }
    
    public void initialize() {
        availableProviders.clear();
        
        availableProviders.add(new BetterTeamsProvider());
        
        if (!plugin.getConfigManager().isTeamsEnabled()) {
            plugin.getLogger().info("Team mode disabled - using global inventory sync");
            activeProvider = null;
            return;
        }
        
        String configuredPlugin = plugin.getConfigManager().getTeamPlugin();
        
        if ("auto".equalsIgnoreCase(configuredPlugin)) {
            for (TeamProvider provider : availableProviders) {
                if (provider.isAvailable()) {
                    activeProvider = provider;
                    plugin.getLogger().info("Auto-detected team plugin: " + provider.getName());
                    return;
                }
            }
        } else {
            for (TeamProvider provider : availableProviders) {
                if (provider.getName().equalsIgnoreCase(configuredPlugin) && provider.isAvailable()) {
                    activeProvider = provider;
                    plugin.getLogger().info("Using configured team plugin: " + provider.getName());
                    return;
                }
            }
        }
        
        if (activeProvider == null) {
            if (plugin.getConfigManager().isFallbackToGlobal()) {
                plugin.getLogger().warning("No team plugin found, falling back to global sync");
            } else {
                plugin.getLogger().warning("No team plugin found and fallback disabled - inventory sync disabled");
            }
        }
    }
    
    public boolean isTeamModeEnabled() {
        return activeProvider != null && plugin.getConfigManager().isTeamsEnabled();
    }
    
    public Collection<Player> getTeamMembers(Player player) {
        if (activeProvider == null) {
            if (plugin.getConfigManager().isFallbackToGlobal()) {
                return new ArrayList<>(plugin.getServer().getOnlinePlayers());
            }
            return Collections.emptyList();
        }
        
        if (!activeProvider.isInTeam(player)) {
            return Collections.singletonList(player);
        }
        
        return activeProvider.getTeamMembers(player);
    }
    
    public UUID getTeamId(Player player) {
        if (activeProvider == null || !activeProvider.isInTeam(player)) {
            return null;
        }
        return activeProvider.getTeamId(player);
    }
    
    public boolean isInSameTeam(Player player1, Player player2) {
        if (activeProvider == null) {
            return plugin.getConfigManager().isFallbackToGlobal();
        }
        
        if (!activeProvider.isInTeam(player1) || !activeProvider.isInTeam(player2)) {
            return false;
        }
        
        UUID team1 = activeProvider.getTeamId(player1);
        UUID team2 = activeProvider.getTeamId(player2);
        
        return team1 != null && team1.equals(team2);
    }
    
    public String getActiveProviderName() {
        return activeProvider != null ? activeProvider.getName() : "None";
    }
}
