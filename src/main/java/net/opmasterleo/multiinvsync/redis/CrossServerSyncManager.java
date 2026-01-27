package net.opmasterleo.multiinvsync.redis;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

import net.minecraft.server.level.ServerPlayer;
import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;
import net.opmasterleo.multiinvsync.config.ConfigManager;
import net.opmasterleo.multiinvsync.redis.RedisInventoryStorage.InventoryData;
import net.opmasterleo.multiinvsync.redis.RedisPubSubManager.RedisMessage;
import net.opmasterleo.multiinvsync.velocity.VelocityIntegration;

/**
 * Cross-server synchronization manager.
 * Orchestrates Redis storage, Pub/Sub messaging, and Velocity integration.
 */
public class CrossServerSyncManager implements VelocityIntegration.ServerSwitchHandler {
    
    private final MultiInvSyncPlugin plugin;
    private final Logger logger;
    private final ConfigManager config;
    
    private RedisConnectionManager redisConnection;
    private RedisInventoryStorage redisStorage;
    private RedisPubSubManager pubSubManager;
    private VelocityIntegration velocityIntegration;
    
    private final Set<UUID> savingNow = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loadingNow = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastSaveVersion = new ConcurrentHashMap<>();
    private final Map<UUID, Long> appliedVersion = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingLoads = new ConcurrentHashMap<>();
    private final Map<String, Integer> teamPresence = new ConcurrentHashMap<>();
    private final Set<String> subscribedTeams = ConcurrentHashMap.newKeySet();
    
    private volatile boolean enabled = false;
    private String serverId;
    
    public CrossServerSyncManager(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = plugin.getConfigManager();
    }
    
    /**
     * Initialize Redis and Velocity integrations.
     */
    public boolean initialize() {
        if (!config.isRedisEnabled()) {
            logger.info("Cross-server sync is disabled");
            return false;
        }
        
        logger.info("Starting cross-server synchronization...");
        
        // Initialize Redis connection
        redisConnection = new RedisConnectionManager(
            logger,
            config.getRedisHost(),
            config.getRedisPort(),
            config.getRedisPassword(),
            config.getRedisDatabase(),
            config.getRedisTimeout()
        );
        
        if (!redisConnection.connect()) {
            logger.severe("Could not connect to Redis. Cross-server sync disabled.");
            return false;
        }
        
        // Initialize storage and pub/sub
        redisStorage = new RedisInventoryStorage(redisConnection, logger);
        
        // Initialize Velocity integration
        velocityIntegration = new VelocityIntegration(plugin, logger, this);
        velocityIntegration.initialize();
        
        // Wait a moment for Velocity detection
        plugin.getScheduler().runMainLater(() -> {
            serverId = velocityIntegration.getCurrentServerId();
            
            if (!velocityIntegration.isVelocityDetected()) {
                logger.severe("========================================");
                logger.severe("WARNING: Velocity proxy not detected!");
                logger.severe("Without Velocity, you may experience:");
                logger.severe("  • Item duplication");
                logger.severe("  • Lost items");
                logger.severe("  • Data corruption");
                logger.severe("Redis sync requires Velocity for safety!");
                logger.severe("========================================");
                
                if (!config.isRedisAllowWithoutProxy()) {
                    logger.severe("Cross-server sync disabled for safety");
                    shutdown();
                    return;
                }
            }
            
            // Start Pub/Sub after Velocity detection
            pubSubManager = new RedisPubSubManager(redisConnection, logger, serverId);
            pubSubManager.start(this::handleRedisMessage);
            
            enabled = true;
            logger.info("Cross-server sync enabled (Server: " + serverId + ")");

            if (config.isTeamsEnabled()) {
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    updateTeamSubscription(online, true);
                }
            }
        }, 40L);
        
        return true;
    }
    
    /**
     * Called when player joins THIS server.
     * Load their inventory from Redis if coming from another server.
     */
    public void handlePlayerJoin(Player player) {
        if (!enabled) return;
        
        UUID playerId = player.getUniqueId();
        
        // Mark as pending load
        pendingLoads.put(playerId, System.currentTimeMillis());
        
        // Delay load to ensure previous server saved
        plugin.getScheduler().runAtEntityLater(player, () -> {
            if (!player.isOnline()) return;
            
            loadingNow.add(playerId);
            try {
                // Register player on this server
                redisStorage.registerPlayer(playerId, serverId);
                updateTeamSubscription(player, true);
                
                // Load inventory from Redis
                InventoryData data = redisStorage.loadInventory(playerId);
                if (data != null) {
                    applyInventoryFromRedis(player, data);
                    appliedVersion.put(playerId, data.version);
                    logger.info(player.getName() + "'s inventory loaded from Redis (v" + data.version + ")");
                }
                
                // Load economy if enabled
                if (config.isSyncMoney()) {
                    Double balance = redisStorage.loadEconomy(playerId);
                    if (balance != null && plugin.getEconomySyncManager() != null) {
                        plugin.getEconomySyncManager().applyBalance(player, balance);
                    }
                }
            } catch (Exception e) {
                logger.warning("Failed to load inventory for " + player.getName() + ": " + e.getMessage());
            } finally {
                loadingNow.remove(playerId);
                pendingLoads.remove(playerId);
            }
        }, 5L); // 250ms delay
    }
    
    /**
     * Called when player leaves THIS server.
     * Save their inventory to Redis for next server.
     */
    public void handlePlayerQuit(Player player) {
        if (!enabled) return;
        
        UUID playerId = player.getUniqueId();
        
        // Don't save if currently loading
        if (loadingNow.contains(playerId) || pendingLoads.containsKey(playerId)) {
            return;
        }
        
        saveInventoryToRedis(player, false);
        
        // Unregister from this server
        redisStorage.unregisterPlayer(playerId, serverId);
        updateTeamSubscription(player, false);
    }
    
    /**
     * Save player inventory to Redis.
     */
    public void saveInventoryToRedis(Player player, boolean fromLocalSync) {
        if (!enabled) return;
        
        UUID playerId = player.getUniqueId();
        
        // Prevent concurrent saves
        if (!savingNow.add(playerId)) {
            return;
        }
        
        plugin.getScheduler().runAtEntity(player, () -> {
            try {
                if (!player.isOnline()) return;
                
                // Capture inventory
                InventoryData data = captureInventory(player);
                
                // Save to Redis
                long version = redisStorage.saveInventory(playerId, data);
                if (version > 0) {
                    lastSaveVersion.put(playerId, version);
                    appliedVersion.put(playerId, version);
                    
                    // Broadcast update if from local sync (inventory change)
                    if (fromLocalSync && !loadingNow.contains(playerId)) {
                        broadcastInventoryUpdate(player, version);
                    }
                }
                
                // Save economy if enabled
                if (config.isSyncMoney() && plugin.getEconomySyncManager() != null) {
                    double balance = plugin.getEconomySyncManager().getBalance(player);
                    redisStorage.saveEconomy(playerId, balance);
                }
            } catch (Exception e) {
                logger.warning("Failed to save inventory for " + player.getName() + ": " + e.getMessage());
            } finally {
                savingNow.remove(playerId);
            }
        });
    }
    
    /**
     * Broadcast inventory update to other servers where player's team members are.
     */
    private void broadcastInventoryUpdate(Player player, long version) {
        if (!enabled || pubSubManager == null) return;
        String teamId = config.isTeamsEnabled() ? getPlayerTeamId(player) : null;
        pubSubManager.broadcastInventoryUpdate(player.getUniqueId(), version, teamId);
    }
    
    /**
     * Handle incoming Redis Pub/Sub messages.
     */
    private void handleRedisMessage(RedisMessage message) {
        switch (message.type) {
            case INVENTORY_UPDATE:
                handleInventoryUpdate(message);
                break;
            case SERVER_SWITCH:
                handleServerSwitch(message.playerId, message.sourceServer, message.targetServer, message.version);
                break;
            case PLAYER_DEATH:
                handlePlayerDeath(message);
                break;
            case ECONOMY_UPDATE:
                handleEconomyUpdate(message);
                break;
        }
    }
    
    private void handleInventoryUpdate(RedisMessage message) {
        // Check if player is on THIS server
        Player player = Bukkit.getPlayer(message.playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        
        // Don't apply updates we sent ourselves
        if (savingNow.contains(message.playerId)) {
            return;
        }
        
        // Load and apply updated inventory
        plugin.getScheduler().runAtEntity(player, () -> {
            InventoryData data = redisStorage.loadInventory(message.playerId);
            long current = appliedVersion.getOrDefault(message.playerId, 0L);
            if (data != null && data.version >= message.version && data.version > current) {
                applyInventoryFromRedis(player, data);
                appliedVersion.put(message.playerId, data.version);
            }
        });
    }
    
    @Override
    public void handleServerSwitch(UUID playerId, String sourceServer, String targetServer, long version) {
        // Only process if we're the target server
        if (!targetServer.equals(serverId)) {
            return;
        }
        
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            // Player is already here, force reload
            plugin.getScheduler().runAtEntity(player, () -> {
                InventoryData data = redisStorage.loadInventory(playerId);
                long current = appliedVersion.getOrDefault(playerId, 0L);
                if (data != null && data.version > current) {
                    applyInventoryFromRedis(player, data);
                    appliedVersion.put(playerId, data.version);
                    logger.info(player.getName() + "'s inventory synced after server switch");
                }
            });
        }
    }
    
    private void handlePlayerDeath(RedisMessage message) {
        if (!config.isSharedDeath()) {
            return;
        }
        
        // Apply shared death locally
        Player player = Bukkit.getPlayer(message.playerId);
        if (player != null && player.isOnline()) {
            plugin.getScheduler().runAtEntity(player, () -> {
                player.getInventory().clear();
                if (config.isSyncEnderChest()) {
                    player.getEnderChest().clear();
                }
            });
        }
    }
    
    private void handleEconomyUpdate(RedisMessage message) {
        if (!config.isSyncMoney()) {
            return;
        }
        
        Player player = Bukkit.getPlayer(message.playerId);
        if (player != null && player.isOnline() && plugin.getEconomySyncManager() != null) {
            plugin.getEconomySyncManager().applyBalance(player, message.balance);
        }
    }
    
    /**
     * Broadcast player death for shared death feature.
     */
    public void broadcastPlayerDeath(Player player) {
        if (!enabled || pubSubManager == null) return;
        
        String teamId = config.isTeamsEnabled() ? getPlayerTeamId(player) : null;
        pubSubManager.broadcastPlayerDeath(player.getUniqueId(), teamId);
        
        // Also save cleared inventory to Redis
        saveInventoryToRedis(player, false);
    }
    
    private InventoryData captureInventory(Player player) {
        InventoryData data = new InventoryData();
        PlayerInventory inv = player.getInventory();
        
        // Main inventory (36 slots)
        if (config.isSyncMainInventory()) {
            for (int i = 0; i < 36; i++) {
                data.mainInventory.add(CraftItemStack.asNMSCopy(inv.getItem(i)));
            }
        }
        
        // Armor (4 slots)
        if (config.isSyncArmor()) {
            data.armorContents.add(CraftItemStack.asNMSCopy(inv.getHelmet()));
            data.armorContents.add(CraftItemStack.asNMSCopy(inv.getChestplate()));
            data.armorContents.add(CraftItemStack.asNMSCopy(inv.getLeggings()));
            data.armorContents.add(CraftItemStack.asNMSCopy(inv.getBoots()));
        }
        
        // Offhand
        if (config.isSyncOffhand()) {
            data.offhand = CraftItemStack.asNMSCopy(inv.getItemInOffHand());
        }
        
        // Ender chest (27 slots)
        if (config.isSyncEnderChest()) {
            for (int i = 0; i < 27; i++) {
                data.enderChest.add(CraftItemStack.asNMSCopy(player.getEnderChest().getItem(i)));
            }
        }
        
        // Cursor
        if (config.isSyncCursor()) {
            data.cursor = CraftItemStack.asNMSCopy(player.getItemOnCursor());
        }
        
        // Experience
        if (config.isSyncExperience()) {
            data.xpLevel = player.getLevel();
            data.xpTotal = player.getTotalExperience();
            data.xpExp = player.getExp();
        }
        
        return data;
    }
    
    private void applyInventoryFromRedis(Player player, InventoryData data) {
        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        PlayerInventory inv = player.getInventory();
        
        // Apply main inventory
        if (config.isSyncMainInventory() && !data.mainInventory.isEmpty()) {
            for (int i = 0; i < Math.min(36, data.mainInventory.size()); i++) {
                inv.setItem(i, CraftItemStack.asBukkitCopy(data.mainInventory.get(i)));
            }
        }
        
        // Apply armor
        if (config.isSyncArmor() && data.armorContents.size() >= 4) {
            inv.setHelmet(CraftItemStack.asBukkitCopy(data.armorContents.get(0)));
            inv.setChestplate(CraftItemStack.asBukkitCopy(data.armorContents.get(1)));
            inv.setLeggings(CraftItemStack.asBukkitCopy(data.armorContents.get(2)));
            inv.setBoots(CraftItemStack.asBukkitCopy(data.armorContents.get(3)));
        }
        
        // Apply offhand
        if (config.isSyncOffhand() && data.offhand != null) {
            inv.setItemInOffHand(CraftItemStack.asBukkitCopy(data.offhand));
        }
        
        // Apply ender chest
        if (config.isSyncEnderChest() && !data.enderChest.isEmpty()) {
            for (int i = 0; i < Math.min(27, data.enderChest.size()); i++) {
                player.getEnderChest().setItem(i, CraftItemStack.asBukkitCopy(data.enderChest.get(i)));
            }
        }
        
        // Apply cursor
        if (config.isSyncCursor() && data.cursor != null) {
            player.setItemOnCursor(CraftItemStack.asBukkitCopy(data.cursor));
        }
        
        // Apply experience
        if (config.isSyncExperience() && data.xpLevel >= 0) {
            player.setLevel(data.xpLevel);
            player.setTotalExperience(data.xpTotal);
            player.setExp(data.xpExp);
        }
        
        // Force client update
        nmsPlayer.containerMenu.sendAllDataToRemote();
    }
    
    private String getPlayerTeamId(Player player) {
        if (plugin.getTeamManager() == null) {
            return null;
        }
        UUID team = plugin.getTeamManager().getTeamId(player);
        return team != null ? team.toString() : null;
    }
    
    public void shutdown() {
        if (pubSubManager != null) {
            pubSubManager.shutdown();
        }
        if (velocityIntegration != null) {
            velocityIntegration.shutdown();
        }
        if (redisConnection != null) {
            redisConnection.shutdown();
        }
        enabled = false;
        logger.info("Cross-server sync stopped");
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public String getServerId() {
        return serverId;
    }

    private void updateTeamSubscription(Player player, boolean join) {
        String teamId = getPlayerTeamId(player);
        if (teamId == null || !config.isTeamsEnabled() || pubSubManager == null) {
            return;
        }
        teamPresence.compute(teamId, (id, count) -> {
            int current = count == null ? 0 : count;
            int updated = join ? current + 1 : Math.max(0, current - 1);
            if (updated == 0) {
                if (subscribedTeams.remove(id)) {
                    pubSubManager.unsubscribeFromTeam(id);
                }
                return null;
            }
            if (join && subscribedTeams.add(id)) {
                pubSubManager.subscribeToTeam(id);
            }
            return updated;
        });
    }
}
