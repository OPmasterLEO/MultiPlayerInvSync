package net.opmasterleo.multiinvsync;

import java.util.logging.Level;

import org.bukkit.plugin.java.JavaPlugin;

import net.opmasterleo.multiinvsync.api.MultiInvSyncAPI;
import net.opmasterleo.multiinvsync.api.MultiInvSyncAPIImpl;
import net.opmasterleo.multiinvsync.command.MainCommand;
import net.opmasterleo.multiinvsync.config.ConfigManager;
import net.opmasterleo.multiinvsync.economy.EconomySyncManager;
import net.opmasterleo.multiinvsync.listener.InventoryListener;
import net.opmasterleo.multiinvsync.listener.PlayerListener;
import net.opmasterleo.multiinvsync.scheduler.BukkitSchedulerAdapter;
import net.opmasterleo.multiinvsync.scheduler.FoliaSchedulerAdapter;
import net.opmasterleo.multiinvsync.scheduler.SchedulerAdapter;
import net.opmasterleo.multiinvsync.sync.InventorySyncManager;
import net.opmasterleo.multiinvsync.team.TeamManager;

public class MultiInvSyncPlugin extends JavaPlugin {
    
    private static MultiInvSyncPlugin instance;
    private ConfigManager configManager;
    private InventorySyncManager syncManager;
    private TeamManager teamManager;
    private MultiInvSyncAPI api;
    private SchedulerAdapter scheduler;
    private EconomySyncManager economySyncManager;
    
    @Override
    public void onEnable() {
        instance = this;
        
        long startTime = System.currentTimeMillis();
        
        printStartupBanner();
        
        try {
            configManager = new ConfigManager(this);
            configManager.load();
            
            if (!configManager.isEnabled()) {
                getLogger().warning("Plugin is disabled in config.yml");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            
            scheduler = createScheduler();

            teamManager = new TeamManager(this);
            teamManager.initialize();

            economySyncManager = new EconomySyncManager(this);
            economySyncManager.initialize();
            
            syncManager = new InventorySyncManager(this);
            syncManager.initialize();
            
            api = new MultiInvSyncAPIImpl(this);
            
            registerListeners();
            registerCommands();
            
            long loadTime = System.currentTimeMillis() - startTime;
            
            getLogger().info("========================================");
            getLogger().info("MultiPlayerInvSync v" + getDescription().getVersion());
            getLogger().info("Author: OPMasterLeo");
            getLogger().info("========================================");
            getLogger().info("✓ NMS & Netty: ENABLED");
            getLogger().info("✓ Sync Mode: " + (teamManager.isTeamModeEnabled() ? "TEAM-BASED" : "GLOBAL"));
            if (teamManager.isTeamModeEnabled()) {
                getLogger().info("✓ Team Provider: " + teamManager.getActiveProviderName());
            }
            getLogger().info("✓ Main Inventory: " + (configManager.isSyncMainInventory() ? "YES" : "NO"));
            getLogger().info("✓ Armor Slots: " + (configManager.isSyncArmor() ? "YES" : "NO"));
            getLogger().info("✓ Offhand: " + (configManager.isSyncOffhand() ? "YES" : "NO"));
            getLogger().info("✓ Ender Chest: " + (configManager.isSyncEnderChest() ? "YES" : "NO"));
            getLogger().info("✓ Shared Death: " + (configManager.isSharedDeath() ? "YES" : "NO"));
            getLogger().info("========================================");
            getLogger().info("Loaded in " + loadTime + "ms - Ready!");
            getLogger().info("========================================");
            
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable plugin", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    
    private void printStartupBanner() {
        getLogger().info("========================================");
        getLogger().info("   __  __ _____  _____                ");
        getLogger().info("  |  \\/  |_   _|/ ____|               ");
        getLogger().info("  | \\  / | | | | (___                 ");
        getLogger().info("  | |\\/| | | |  \\___ \\                ");
        getLogger().info("  | |  | |_| |_ ____) |               ");
        getLogger().info("  |_|  |_|_____|_____/                ");
        getLogger().info("                                      ");
        getLogger().info("  Multi-Inventory Synchronization    ");
        getLogger().info("========================================");
    }
    
    @Override
    public void onDisable() {
        if (syncManager != null) {
            syncManager.shutdown();
        }
        if (economySyncManager != null) {
            economySyncManager.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        getLogger().info("MultiPlayerInvSync disabled");
    }
    
    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new InventoryListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getLogger().info("Registered event listeners");
    }
    
    public MultiInvSyncAPI getAPI() {
        return api;
    }

    public SchedulerAdapter getScheduler() {
        return scheduler;
    }
    
    private void registerCommands() {
        getCommand("multiinvsync").setExecutor(new MainCommand(this));
        getLogger().info("Registered commands");
    }
    
    public void reload() {
        configManager.load();
        if (syncManager != null) {
            syncManager.shutdown();
        }
        if (economySyncManager != null) {
            economySyncManager.shutdown();
        }
        syncManager = new InventorySyncManager(this);
        syncManager.initialize();
        economySyncManager = new EconomySyncManager(this);
        economySyncManager.initialize();
        teamManager.initialize();
        getLogger().info("Plugin reloaded successfully");
    }
    
    public static MultiInvSyncPlugin getInstance() {
        return instance;
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public InventorySyncManager getSyncManager() {
        return syncManager;
    }
    
    public TeamManager getTeamManager() {
        return teamManager;
    }

    public EconomySyncManager getEconomySyncManager() {
        return economySyncManager;
    }

    private SchedulerAdapter createScheduler() {
        boolean folia = isClassPresent("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
        if (folia) {
            getLogger().info("Detected Folia scheduler");
            return new FoliaSchedulerAdapter(this);
        }
        getLogger().info("Using Bukkit/Paper scheduler");
        return new BukkitSchedulerAdapter(this);
    }

    private boolean isClassPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
