package net.opmasterleo.multiinvsync.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

/**
 * Handles Redis storage and retrieval of player inventory data.
 * 
 * KEY STRUCTURE:
 * - misinv:player:{uuid}:inventory - Main inventory snapshot (36 slots + armor + offhand + ender chest)
 * - misinv:player:{uuid}:xp - Experience data (level, total xp, progress)
 * - misinv:player:{uuid}:economy - Economy balance
 * - misinv:player:{uuid}:version - Version number to detect concurrent updates
 * - misinv:server:{serverId}:players - Set of players currently on this server
 * 
 * TTL: Keys expire after 24 hours of inactivity to prevent stale data.
 */
public class RedisInventoryStorage {
    
    private final RedisConnectionManager redis;
    private final Logger logger;
    private final Gson gson;
    private final String keyPrefix = "mis";
    private final int ttlSeconds = 43200; // 12 hours (reduced for efficiency)
    
    public RedisInventoryStorage(RedisConnectionManager redis, Logger logger) {
        this.redis = redis;
        this.logger = logger;
        this.gson = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();
    }
    
    /**
     * Save complete inventory snapshot to Redis with versioning.
     * Returns the new version number, or -1 if save failed.
     */
    public long saveInventory(UUID playerId, InventoryData data) {
        String inventoryKey = getInventoryKey(playerId);
        String versionKey = getVersionKey(playerId);
        String xpKey = getXpKey(playerId);
        
        return redis.execute(jedis -> {
            try {
                String currentVersion = jedis.get(versionKey);
                long newVersion = currentVersion != null ? Long.parseLong(currentVersion) + 1 : 1;
                
                JsonObject root = new JsonObject();
                root.addProperty("v", newVersion);
                root.addProperty("t", System.currentTimeMillis());
                root.add("i", serializeItems(data.mainInventory));
                root.add("a", serializeItems(data.armorContents));
                root.addProperty("o", serializeItem(data.offhand));
                root.add("e", serializeItems(data.enderChest));
                root.addProperty("c", serializeItem(data.cursor));
                
                var pipeline = jedis.pipelined();
                pipeline.setex(inventoryKey, ttlSeconds, root.toString());
                pipeline.setex(versionKey, ttlSeconds, String.valueOf(newVersion));
                
                if (data.xpLevel >= 0) {
                    JsonObject xpData = new JsonObject();
                    xpData.addProperty("l", data.xpLevel);
                    xpData.addProperty("x", data.xpTotal);
                    xpData.addProperty("p", data.xpExp);
                    pipeline.setex(xpKey, ttlSeconds, xpData.toString());
                }
                
                pipeline.sync();
                
                logger.fine("Saved inventory for " + playerId + " (version: " + newVersion + ")");
                return newVersion;
            } catch (Exception e) {
                logger.warning("Failed to save inventory for " + playerId + ": " + e.getMessage());
                return -1L;
            }
        });
    }
    
    /**
     * Load inventory from Redis.
     * Returns null if not found or corrupted.
     */
    public InventoryData loadInventory(UUID playerId) {
        String inventoryKey = getInventoryKey(playerId);
        String xpKey = getXpKey(playerId);
        
        return redis.execute(jedis -> {
            try {
                String inventoryJson = jedis.get(inventoryKey);
                if (inventoryJson == null) {
                    return null;
                }
                
                JsonObject root = gson.fromJson(inventoryJson, JsonObject.class);
                InventoryData data = new InventoryData();
                data.version = root.get("v").getAsLong();
                data.timestamp = root.get("t").getAsLong();
                data.mainInventory = deserializeItems(root.getAsJsonArray("i"), 36);
                data.armorContents = deserializeItems(root.getAsJsonArray("a"), 4);
                data.offhand = deserializeItem(root.get("o"));
                data.enderChest = deserializeItems(root.getAsJsonArray("e"), 27);
                data.cursor = deserializeItem(root.get("c"));
                
                String xpJson = jedis.get(xpKey);
                if (xpJson != null) {
                    JsonObject xpData = gson.fromJson(xpJson, JsonObject.class);
                    data.xpLevel = xpData.get("l").getAsInt();
                    data.xpTotal = xpData.get("x").getAsInt();
                    data.xpExp = xpData.get("p").getAsFloat();
                }
                
                logger.fine("Loaded inventory for " + playerId + " (version: " + data.version + ")");
                return data;
            } catch (Exception e) {
                logger.warning("Failed to load inventory for " + playerId + ": " + e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Save economy balance to Redis.
     */
    public void saveEconomy(UUID playerId, double balance) {
        String economyKey = getEconomyKey(playerId);
        redis.executeVoid(jedis -> {
            jedis.set(economyKey, String.valueOf(balance));
            jedis.expire(economyKey, ttlSeconds);
            return null;
        });
    }
    
    /**
     * Load economy balance from Redis.
     */
    public Double loadEconomy(UUID playerId) {
        String economyKey = getEconomyKey(playerId);
        return redis.execute(jedis -> {
            String balance = jedis.get(economyKey);
            return balance != null ? Double.parseDouble(balance) : null;
        });
    }
    
    /**
     * Mark player as active on this server.
     */
    public void registerPlayer(UUID playerId, String serverId) {
        String serverPlayersKey = getServerPlayersKey(serverId);
        redis.executeVoid(jedis -> {
            jedis.sadd(serverPlayersKey, playerId.toString());
            jedis.expire(serverPlayersKey, 300); // 5 minutes
            return null;
        });
    }
    
    /**
     * Remove player from this server's active set.
     */
    public void unregisterPlayer(UUID playerId, String serverId) {
        String serverPlayersKey = getServerPlayersKey(serverId);
        redis.executeVoid(jedis -> {
            jedis.srem(serverPlayersKey, playerId.toString());
            return null;
        });
    }
    
    /**
     * Check if player is active on another server.
     */
    public boolean isPlayerActiveElsewhere(UUID playerId, String currentServerId) {
        return redis.execute(jedis -> {
            var keys = jedis.keys(keyPrefix + ":server:*:players");
            for (String key : keys) {
                if (key.contains(currentServerId)) continue;
                if (jedis.sismember(key, playerId.toString())) {
                    return true;
                }
            }
            return false;
        });
    }
    
    /**
     * Delete all data for a player (for cleanup/reset).
     */
    public void deletePlayer(UUID playerId) {
        redis.executeVoid(jedis -> {
            jedis.del(
                getInventoryKey(playerId),
                getVersionKey(playerId),
                getXpKey(playerId),
                getEconomyKey(playerId)
            );
            return null;
        });
    }
    
    private String getInventoryKey(UUID playerId) {
        return keyPrefix + ":player:" + playerId + ":inventory";
    }
    
    private String getVersionKey(UUID playerId) {
        return keyPrefix + ":player:" + playerId + ":version";
    }
    
    private String getXpKey(UUID playerId) {
        return keyPrefix + ":player:" + playerId + ":xp";
    }
    
    private String getEconomyKey(UUID playerId) {
        return keyPrefix + ":player:" + playerId + ":economy";
    }
    
    private String getServerPlayersKey(String serverId) {
        return keyPrefix + ":server:" + serverId + ":players";
    }
    
    private JsonArray serializeItems(List<net.minecraft.world.item.ItemStack> items) {
        JsonArray array = new JsonArray();
        for (net.minecraft.world.item.ItemStack item : items) {
            array.add(gson.toJsonTree(serializeItem(item)));
        }
        return array;
    }
    
    private String serializeItem(net.minecraft.world.item.ItemStack item) {
        if (item == null || item.isEmpty()) {
            return "";
        }
        try {
            CompoundTag tag = new CompoundTag();
            item.save(null, tag);
            return NbtUtils.structureToSnbt(tag);
        } catch (Exception e) {
            logger.warning("Failed to serialize item: " + e.getMessage());
            return "";
        }
    }
    
    private List<net.minecraft.world.item.ItemStack> deserializeItems(JsonArray array, int expectedSize) {
        List<net.minecraft.world.item.ItemStack> items = new ArrayList<>();
        for (int i = 0; i < expectedSize; i++) {
            if (i < array.size()) {
                items.add(deserializeItem(array.get(i)));
            } else {
                items.add(net.minecraft.world.item.ItemStack.EMPTY);
            }
        }
        return items;
    }
    
    private net.minecraft.world.item.ItemStack deserializeItem(com.google.gson.JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        String snbt = element.getAsString();
        if (snbt.isEmpty()) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        try {
            CompoundTag tag = NbtUtils.snbtToStructure(snbt);
            return net.minecraft.world.item.ItemStack.parseOptional(null, tag);
        } catch (Exception e) {
            logger.warning("Failed to deserialize item: " + e.getMessage());
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }
    
    /**
     * Data class for inventory snapshots.
     */
    public static class InventoryData {
        public long version;
        public long timestamp;
        public List<net.minecraft.world.item.ItemStack> mainInventory = new ArrayList<>();
        public List<net.minecraft.world.item.ItemStack> armorContents = new ArrayList<>();
        public net.minecraft.world.item.ItemStack offhand;
        public List<net.minecraft.world.item.ItemStack> enderChest = new ArrayList<>();
        public net.minecraft.world.item.ItemStack cursor;
        public int xpLevel = -1;
        public int xpTotal = -1;
        public float xpExp = 0.0f;
    }
}
