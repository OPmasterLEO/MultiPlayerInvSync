package net.opmasterleo.multiinvsync.velocity;

import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

/**
 * Velocity/BungeeCord plugin messaging integration.
 * 
 * WHY IS THIS CRITICAL?
 * Without a proxy coordinating player movement:
 * 1. Multiple servers can have the same player connected simultaneously
 * 2. Race conditions occur when saving/loading inventories
 * 3. Items can be duplicated if player switches servers rapidly
 * 4. Outdated inventory data can overwrite newer data
 * 5. No authoritative source to determine which server "owns" a player
 * 
 * Velocity provides:
 * - Single player session (player can only be on ONE server at a time)
 * - Server switch notifications (we know WHEN player moves)
 * - Server identity (we know WHERE player is moving from/to)
 * 
 * PLUGIN CHANNELS:
 * - velocity:main - Primary channel for server info and player server queries
 * - misinv:sync - Custom channel for cross-server inventory sync coordination
 * 
 * SAFETY PROTOCOL:
 * 1. Player joins Server A → Load from Redis, mark as "owned" by Server A
 * 2. Player switches to Server B → Server A saves to Redis, sends notification
 * 3. Server B receives notification → Loads from Redis, marks as "owned" by Server B
 * 4. Velocity ensures player disconnects from Server A before connecting to Server B
 */
public class VelocityIntegration implements PluginMessageListener {
    
    private final Plugin plugin;
    private final Logger logger;
    private final ServerSwitchHandler switchHandler;
    private String currentServerId;
    private volatile boolean velocityDetected = false;
    
    public VelocityIntegration(Plugin plugin, Logger logger, ServerSwitchHandler switchHandler) {
        this.plugin = plugin;
        this.logger = logger;
        this.switchHandler = switchHandler;
    }
    
    /**
     * Initialize plugin messaging channels.
     */
    public void initialize() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "velocity:main", this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "misinv:sync", this);
        
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "velocity:main");
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "misinv:sync");
        
        plugin.getServer().getScheduler().runTaskLater(plugin, this::detectVelocity, 20L);
        
        logger.info("Velocity integration ready");
    }
    
    /**
     * Detect if running behind Velocity proxy.
     */
    private void detectVelocity() {
        // Try to get a player to send the request
        Player player = plugin.getServer().getOnlinePlayers().stream().findFirst().orElse(null);
        if (player != null) {
            requestServerInfo(player);
        }
    }
    
    /**
     * Request server name from Velocity.
     */
    private void requestServerInfo(Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("GetServer");
        player.sendPluginMessage(plugin, "velocity:main", out.toByteArray());
    }
    
    /**
     * Notify Velocity that player is switching servers.
     * This sends inventory save notification to the target server.
     */
    public void notifyServerSwitch(Player player, String targetServerId, long inventoryVersion) {
        if (!velocityDetected) {
            logger.warning("Cannot notify server switch - Velocity not detected");
            logger.warning("Without Velocity, inventory duplication may occur!");
            return;
        }
        
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ServerSwitch");
            out.writeUTF(player.getUniqueId().toString());
            out.writeUTF(currentServerId != null ? currentServerId : "unknown");
            out.writeUTF(targetServerId);
            out.writeLong(inventoryVersion);
            out.writeLong(System.currentTimeMillis());
            
            player.sendPluginMessage(plugin, "misinv:sync", out.toByteArray());
            logger.fine("Notified server switch: " + player.getName() + " → " + targetServerId);
        } catch (Exception e) {
            logger.warning("Failed to notify server switch: " + e.getMessage());
        }
    }
    
    /**
     * Send connect notification (player just joined THIS server from another).
     */
    public void notifyPlayerConnected(Player player, String sourceServerId) {
        if (!velocityDetected) {
            return;
        }
        
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("PlayerConnected");
            out.writeUTF(player.getUniqueId().toString());
            out.writeUTF(sourceServerId);
            out.writeUTF(currentServerId != null ? currentServerId : "unknown");
            out.writeLong(System.currentTimeMillis());
            
            player.sendPluginMessage(plugin, "misinv:sync", out.toByteArray());
            logger.fine("Notified player connected: " + player.getName() + " from " + sourceServerId);
        } catch (Exception e) {
            logger.warning("Failed to notify player connected: " + e.getMessage());
        }
    }
    
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (channel.equals("velocity:main")) {
            handleVelocityMessage(player, message);
        } else if (channel.equals("misinv:sync")) {
            handleSyncMessage(player, message);
        }
    }
    
    private void handleVelocityMessage(Player player, byte[] message) {
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String subChannel = in.readUTF();
            
            if (subChannel.equals("GetServer")) {
                currentServerId = in.readUTF();
                velocityDetected = true;
                logger.info("Velocity proxy detected! Server: " + currentServerId);
            }
        } catch (Exception e) {
            logger.warning("Failed to handle Velocity message: " + e.getMessage());
        }
    }
    
    private void handleSyncMessage(Player player, byte[] message) {
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String messageType = in.readUTF();
            
            if (messageType.equals("ServerSwitch")) {
                UUID playerId = UUID.fromString(in.readUTF());
                String sourceServer = in.readUTF();
                String targetServer = in.readUTF();
                long version = in.readLong();
                long timestamp = in.readLong();
                
                // Only process if we're the target server
                if (currentServerId != null && currentServerId.equals(targetServer)) {
                    switchHandler.handleServerSwitch(playerId, sourceServer, targetServer, version);
                }
            } else if (messageType.equals("PlayerConnected")) {
                UUID playerId = UUID.fromString(in.readUTF());
                String sourceServer = in.readUTF();
                String targetServer = in.readUTF();
                long timestamp = in.readLong();
                
                logger.fine("Player connected notification: " + playerId + " from " + sourceServer);
            }
        } catch (Exception e) {
            logger.warning("Failed to handle sync message: " + e.getMessage());
        }
    }
    
    public String getCurrentServerId() {
        return currentServerId != null ? currentServerId : "unknown-" + System.currentTimeMillis();
    }
    
    public boolean isVelocityDetected() {
        return velocityDetected;
    }
    
    public void shutdown() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, "velocity:main");
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, "misinv:sync");
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, "velocity:main");
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, "misinv:sync");
        logger.info("Velocity integration stopped");
    }
    
    /**
     * Handler interface for server switch events.
     */
    public interface ServerSwitchHandler {
        void handleServerSwitch(UUID playerId, String sourceServer, String targetServer, long version);
    }
}
