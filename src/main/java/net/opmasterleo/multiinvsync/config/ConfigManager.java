package net.opmasterleo.multiinvsync.config;

import org.bukkit.configuration.file.FileConfiguration;

import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;

public class ConfigManager {
    
    private final MultiInvSyncPlugin plugin;
    private FileConfiguration config;
    
    public ConfigManager(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
    }
    
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
    }
    
    public boolean isEnabled() {
        return config.getBoolean("enabled", true);
    }
    
    public boolean isSyncMainInventory() {
        return config.getBoolean("sync.main-inventory", true);
    }
    
    public boolean isSyncArmor() {
        return config.getBoolean("sync.armor", true);
    }
    
    public boolean isSyncOffhand() {
        return config.getBoolean("sync.offhand", true);
    }
    
    public boolean isSyncEnderChest() {
        return config.getBoolean("sync.ender-chest", true);
    }
    
    public boolean isSyncCursor() {
        return config.getBoolean("sync.cursor", false);
    }

    public boolean isSyncExperience() {
        return config.getBoolean("sync.experience", false);
    }

    public boolean isSyncMoney() {
        return config.getBoolean("economy.sync-money", false);
    }

    public String getEconomyProvider() {
        return config.getString("economy.provider", "essentials");
    }
    
    public int getSyncDelayTicks() {
        return config.getInt("sync.delay-ticks", 1);
    }
    
    public boolean isSharedDeath() {
        return config.getBoolean("death.shared-death", true);
    }
    
    public boolean isBroadcastSharedDeath() {
        return config.getBoolean("death.broadcast-shared-death", true);
    }
    
    public String getDeathMessage() {
        return config.getString("death.death-message", "&c{player} died! All synchronized inventories have been cleared.");
    }
    
    public boolean isTeamsEnabled() {
        return config.getBoolean("teams.enabled", false);
    }
    
    public String getTeamPlugin() {
        return config.getString("teams.plugin", "auto");
    }
    
    public boolean isFallbackToGlobal() {
        return config.getBoolean("teams.fallback-to-global", true);
    }
    
    public boolean isDebugEnabled() {
        return config.getBoolean("debug.enabled", false);
    }
    
    public boolean isLogSyncEvents() {
        return config.getBoolean("debug.log-sync-events", false);
    }
    
    // Redis configuration
    public boolean isRedisEnabled() {
        return config.getBoolean("redis.enabled", false);
    }
    
    public String getRedisHost() {
        return config.getString("redis.host", "localhost");
    }
    
    public int getRedisPort() {
        return config.getInt("redis.port", 6379);
    }
    
    public String getRedisPassword() {
        String password = config.getString("redis.password", "");
        return password.isEmpty() ? null : password;
    }
    
    public int getRedisDatabase() {
        return config.getInt("redis.database", 0);
    }
    
    public int getRedisTimeout() {
        return config.getInt("redis.timeout", 3000);
    }
    
    public boolean isRedisAllowWithoutProxy() {
        return config.getBoolean("redis.allow-without-proxy", false);
    }
    
    public boolean isSyncHealth() {
        return config.getBoolean("sync.health", false);
    }
    
    public boolean isSyncHunger() {
        return config.getBoolean("sync.hunger", false);
    }
    
    public boolean isSyncPose() {
        return config.getBoolean("sync.pose", false);
    }
    
    public boolean isSyncEffects() {
        return config.getBoolean("sync.effects", false);
    }
}
