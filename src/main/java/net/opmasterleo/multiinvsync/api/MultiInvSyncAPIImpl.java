package net.opmasterleo.multiinvsync.api;

import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

public class MultiInvSyncAPIImpl implements MultiInvSyncAPI {
    
    private final MultiInvSyncPlugin plugin;
    
    public MultiInvSyncAPIImpl(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void syncInventory(Player player) {
        plugin.getSyncManager().syncInventory(player);
    }
    
    @Override
    public void clearAllInventories(Player sourcePlayer, boolean clearSource) {
        plugin.getSyncManager().clearAllInventories(sourcePlayer, clearSource);
    }
    
    @Override
    public void addBypass(UUID playerUUID) {
        plugin.getSyncManager().addBypassPlayer(playerUUID);
    }
    
    @Override
    public void removeBypass(UUID playerUUID) {
        plugin.getSyncManager().removeBypassPlayer(playerUUID);
    }
    
    @Override
    public boolean isBypassed(UUID playerUUID) {
        return plugin.getSyncManager().isBypassed(playerUUID);
    }
    
    @Override
    public Collection<Player> getSyncGroup(Player player) {
        return plugin.getTeamManager().getTeamMembers(player);
    }
    
    @Override
    public boolean isTeamModeEnabled() {
        return plugin.getTeamManager().isTeamModeEnabled();
    }
    
    @Override
    public boolean isInSameGroup(Player player1, Player player2) {
        return plugin.getTeamManager().isInSameTeam(player1, player2);
    }
    
    @Override
    public UUID getTeamId(Player player) {
        return plugin.getTeamManager().getTeamId(player);
    }
    
    @Override
    public void reload() {
        plugin.reload();
    }
}
