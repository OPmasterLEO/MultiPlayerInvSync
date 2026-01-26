package net.opmasterleo.multiinvsync.redis;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public class RedisPubSubManager {
    
    private final RedisConnectionManager redis;
    private final Logger logger;
    private final Gson gson;
    private final ExecutorService executor;
    private final String serverId;
    
    private Jedis subscriberConnection;
    private MessageSubscriber subscriber;
    private volatile boolean running = false;
    
    public RedisPubSubManager(RedisConnectionManager redis, Logger logger, String serverId) {
        this.redis = redis;
        this.logger = logger;
        this.gson = new Gson();
        this.serverId = serverId;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Redis-PubSub-Subscriber");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Start listening for messages on all relevant channels.
     */
    public void start(Consumer<RedisMessage> messageHandler) {
        if (running) {
            logger.warning("Pub/Sub already running");
            return;
        }
        
        running = true;
        subscriber = new MessageSubscriber(messageHandler);
        
        executor.submit(() -> {
            try {
                subscriberConnection = redis.getResource();
                logger.info("Subscribing to Redis channels...");
                
                subscriberConnection.subscribe(subscriber, 
                    "mis:u:" + serverId,
                    "mis:g"
                );
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Redis Pub/Sub connection failed", e);
                running = false;
            }
        });
        
        logger.info("Redis Pub/Sub started for server: " + serverId);
    }
    
    /**
     * Publish inventory update to target server.
     */
    public void publishInventoryUpdate(UUID playerId, String targetServerId, long version) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "INVENTORY_UPDATE");
        message.addProperty("playerId", playerId.toString());
        message.addProperty("sourceServer", serverId);
        message.addProperty("targetServer", targetServerId);
        message.addProperty("version", version);
        message.addProperty("timestamp", System.currentTimeMillis());
        
        String channel = "misinv:updates:" + targetServerId;
        publish(channel, message.toString());
    }
    
    /**
     * Publish server switch notification (player moving from source to target server).
     */
    public void publishServerSwitch(UUID playerId, String sourceServerId, String targetServerId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "SERVER_SWITCH");
        message.addProperty("playerId", playerId.toString());
        message.addProperty("sourceServer", sourceServerId);
        message.addProperty("targetServer", targetServerId);
        message.addProperty("timestamp", System.currentTimeMillis());
        
        // Notify target server
        String channel = "mis:u:" + targetServerId;
        publish(channel, message.toString());
    }
    
    /**
     * Broadcast player death to all servers (for shared death feature).
     */
    public void broadcastPlayerDeath(UUID playerId, String teamId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "PLAYER_DEATH");
        message.addProperty("playerId", playerId.toString());
        message.addProperty("sourceServer", serverId);
        message.addProperty("timestamp", System.currentTimeMillis());
        
        if (teamId != null) {
            message.addProperty("teamId", teamId);
            publish("mis:t:" + teamId, message.toString());
        } else {
            publish("mis:g", message.toString());
        }
    }
    
    /**
     * Broadcast economy update.
     */
    public void broadcastEconomyUpdate(UUID playerId, double balance, String teamId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "ECONOMY_UPDATE");
        message.addProperty("playerId", playerId.toString());
        message.addProperty("sourceServer", serverId);
        message.addProperty("balance", balance);
        message.addProperty("timestamp", System.currentTimeMillis());
        
        if (teamId != null) {
            message.addProperty("teamId", teamId);
            publish("mis:t:" + teamId, message.toString());
        } else {
            publish("mis:g", message.toString());
        }
    }
    
    /**
     * Subscribe to team-specific channel (for team-based sync mode).
     */
    public void subscribeToTeam(String teamId) {
        if (!running || subscriber == null) {
            logger.warning("Cannot subscribe - messaging not active");
            return;
        }
        
        executor.submit(() -> {
            try {
                subscriber.subscribe("mis:t:" + teamId);
                logger.info("Subscribed to team: " + teamId);
            } catch (Exception e) {
                logger.warning("Could not subscribe to team: " + teamId);
            }
        });
    }
    
    /**
     * Unsubscribe from team channel.
     */
    public void unsubscribeFromTeam(String teamId) {
        if (!running || subscriber == null) {
            return;
        }
        
        executor.submit(() -> {
            try {
                subscriber.unsubscribe("mis:t:" + teamId);
                logger.info("Unsubscribed from team: " + teamId);
            } catch (Exception e) {
                logger.warning("Could not unsubscribe from team: " + teamId);
            }
        });
    }
    
    /**
     * Shutdown Pub/Sub and close connections.
     */
    public void shutdown() {
        running = false;
        
        if (subscriber != null && subscriber.isSubscribed()) {
            subscriber.unsubscribe();
        }
        
        if (subscriberConnection != null && subscriberConnection.isConnected()) {
            subscriberConnection.close();
        }
        
        executor.shutdown();
        logger.info("Redis messaging stopped");
    }
    
    /**
     * Publish message to channel.
     */
    private void publish(String channel, String message) {
        redis.executeVoid(jedis -> {
            jedis.publish(channel, message);
            logger.fine("Published to " + channel + ": " + message);
            return null;
        });
    }
    
    /**
     * Internal subscriber that handles incoming messages.
     */
    private class MessageSubscriber extends JedisPubSub {
        private final Consumer<RedisMessage> handler;
        
        public MessageSubscriber(Consumer<RedisMessage> handler) {
            this.handler = handler;
        }
        
        @Override
        public void onMessage(String channel, String message) {
            try {
                JsonObject json = gson.fromJson(message, JsonObject.class);
                RedisMessage redisMessage = new RedisMessage();
                redisMessage.channel = channel;
                redisMessage.type = MessageType.valueOf(json.get("type").getAsString());
                redisMessage.playerId = UUID.fromString(json.get("playerId").getAsString());
                redisMessage.sourceServer = json.get("sourceServer").getAsString();
                redisMessage.timestamp = json.get("timestamp").getAsLong();
                
                if (json.has("targetServer")) {
                    redisMessage.targetServer = json.get("targetServer").getAsString();
                }
                if (json.has("version")) {
                    redisMessage.version = json.get("version").getAsLong();
                }
                if (json.has("teamId")) {
                    redisMessage.teamId = json.get("teamId").getAsString();
                }
                if (json.has("balance")) {
                    redisMessage.balance = json.get("balance").getAsDouble();
                }
                
                // Don't process messages from our own server (echo prevention)
                if (redisMessage.sourceServer.equals(serverId)) {
                    return;
                }
                
                handler.accept(redisMessage);
            } catch (Exception e) {
                logger.warning("Failed to process message from " + channel + ": " + e.getMessage());
            }
        }
        
        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            logger.info("Subscribed to channel: " + channel + " (total: " + subscribedChannels + ")");
        }
        
        @Override
        public void onUnsubscribe(String channel, int subscribedChannels) {
            logger.info("Unsubscribed from channel: " + channel + " (remaining: " + subscribedChannels + ")");
        }
    }
    
    /**
     * Message types for Pub/Sub communication.
     */
    public enum MessageType {
        INVENTORY_UPDATE,
        SERVER_SWITCH,
        PLAYER_DEATH,
        ECONOMY_UPDATE
    }
    
    /**
     * Parsed Redis message.
     */
    public static class RedisMessage {
        public String channel;
        public MessageType type;
        public UUID playerId;
        public String sourceServer;
        public String targetServer;
        public long version;
        public long timestamp;
        public String teamId;
        public double balance;
    }
}
