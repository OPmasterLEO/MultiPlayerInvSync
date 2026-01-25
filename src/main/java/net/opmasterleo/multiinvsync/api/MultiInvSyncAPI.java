package net.opmasterleo.multiinvsync.api;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

/**
 * API for MultiPlayerInvSync plugin
 * Provides programmatic access to inventory synchronization features
 */
public interface MultiInvSyncAPI {
    
    /**
     * Force synchronize a player's inventory to all connected players/team members
     * 
     * @param player The player whose inventory should be synced
     */
    void syncInventory(Player player);
    
    /**
     * Clear all synchronized inventories (useful for custom death handlers)
     * 
     * @param sourcePlayer The player triggering the clear
     * @param clearSource Whether to also clear the source player's inventory
     */
    void clearAllInventories(Player sourcePlayer, boolean clearSource);
    
    /**
     * Add a player to the bypass list (their inventory won't sync)
     * 
     * @param playerUUID The UUID of the player to bypass
     */
    void addBypass(UUID playerUUID);
    
    /**
     * Remove a player from the bypass list
     * 
     * @param playerUUID The UUID of the player to remove from bypass
     */
    void removeBypass(UUID playerUUID);
    
    /**
     * Check if a player is currently bypassed
     * 
     * @param playerUUID The UUID of the player to check
     * @return true if the player is bypassed
     */
    boolean isBypassed(UUID playerUUID);
    
    /**
     * Get all players who share inventory with the specified player
     * 
     * @param player The player to check
     * @return Collection of players in the same sync group
     */
    Collection<Player> getSyncGroup(Player player);
    
    /**
     * Check if team mode is enabled
     * 
     * @return true if inventory syncing is team-based
     */
    boolean isTeamModeEnabled();
    
    /**
     * Check if two players are in the same sync group
     * 
     * @param player1 First player
     * @param player2 Second player
     * @return true if they share inventory
     */
    boolean isInSameGroup(Player player1, Player player2);
    
    /**
     * Get the team ID for a player (null if not in team mode or no team)
     * 
     * @param player The player to check
     * @return UUID of the team, or null
     */
    UUID getTeamId(Player player);
    
    /**
     * Reload the plugin configuration
     */
    void reload();
}
