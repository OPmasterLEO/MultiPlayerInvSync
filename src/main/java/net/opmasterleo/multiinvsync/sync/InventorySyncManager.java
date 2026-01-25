package net.opmasterleo.multiinvsync.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;

public class InventorySyncManager {
    
    private final MultiInvSyncPlugin plugin;
    private final Map<UUID, Long> lastSyncTime = new ConcurrentHashMap<>();
    private final Set<UUID> bypassPlayers = ConcurrentHashMap.newKeySet();
    private final List<SyncBatch> pendingBatches = new CopyOnWriteArrayList<>();
    private final Set<UUID> syncingNow = ConcurrentHashMap.newKeySet();
    
    public InventorySyncManager(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
    }
    
    public void initialize() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            injectPlayer(player);
        }
        plugin.getLogger().info("Inventory sync manager initialized (NMS + Netty enabled)");
    }
    
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            uninjectPlayer(player);
        }
        pendingBatches.clear();
    }
    
    public void syncInventory(Player source) {
        UUID sourceId = source.getUniqueId();
        if (bypassPlayers.contains(sourceId)) {
            return;
        }
        if (!syncingNow.add(sourceId)) {
            return; // already syncing this player; avoid re-entrancy
        }
        
        long currentTime = System.currentTimeMillis();
        Long lastSync = lastSyncTime.get(sourceId);
        
        int delayTicks = plugin.getConfigManager().getSyncDelayTicks();
        if (lastSync != null && currentTime - lastSync < (delayTicks * 50L)) {
            syncingNow.remove(sourceId);
            return;
        }
        
        lastSyncTime.put(sourceId, currentTime);
        
        Collection<Player> targets = getTargetPlayers(source);
        if (targets.isEmpty()) {
            syncingNow.remove(sourceId);
            return;
        }
        
        // Run on main thread to safely access Bukkit/CraftBukkit objects
        plugin.getScheduler().runMain(() -> {
            try {
                syncWithNMS(source, targets);
            } finally {
                syncingNow.remove(sourceId);
            }
        });
    }
    
    private void syncWithNMS(Player source, Collection<Player> targets) {
        // Snapshot once per sync to minimize per-player overhead
        List<ItemStack> items = new ArrayList<>(45);
        PlayerInventory inv = source.getInventory();
        int srcLevel = 0;
        int srcTotalXp = 0;
        float srcExp = 0.0F;
        boolean syncXp = plugin.getConfigManager().isSyncExperience();
        if (syncXp) {
            srcLevel = source.getLevel();
            srcTotalXp = source.getTotalExperience();
            srcExp = source.getExp();
        }
        
        if (plugin.getConfigManager().isSyncMainInventory()) {
            for (int i = 0; i < 36; i++) {
                items.add(CraftItemStack.asNMSCopy(inv.getItem(i)));
            }
        }
        
        if (plugin.getConfigManager().isSyncArmor()) {
            items.add(CraftItemStack.asNMSCopy(inv.getHelmet()));
            items.add(CraftItemStack.asNMSCopy(inv.getChestplate()));
            items.add(CraftItemStack.asNMSCopy(inv.getLeggings()));
            items.add(CraftItemStack.asNMSCopy(inv.getBoots()));
        }
        
        if (plugin.getConfigManager().isSyncOffhand()) {
            items.add(CraftItemStack.asNMSCopy(inv.getItemInOffHand()));
        }
        
        List<ItemStack> enderItems = new ArrayList<>(27);
        if (plugin.getConfigManager().isSyncEnderChest()) {
            for (int i = 0; i < 27; i++) {
                enderItems.add(CraftItemStack.asNMSCopy(source.getEnderChest().getItem(i)));
            }
        }
        
        for (Player target : targets) {
            if (target.getUniqueId().equals(source.getUniqueId())) {
                continue;
            }
            
            ServerPlayer nmsTarget = ((CraftPlayer) target).getHandle();
            
            int slot = 0;
            PlayerInventory targetInv = target.getInventory();

            if (plugin.getConfigManager().isSyncMainInventory()) {
                for (int i = 0; i < 36 && slot < items.size(); i++) {
                    targetInv.setItem(i, CraftItemStack.asBukkitCopy(items.get(slot++)));
                }
            }

            if (plugin.getConfigManager().isSyncArmor() && slot + 3 < items.size()) {
                targetInv.setHelmet(CraftItemStack.asBukkitCopy(items.get(slot++)));
                targetInv.setChestplate(CraftItemStack.asBukkitCopy(items.get(slot++)));
                targetInv.setLeggings(CraftItemStack.asBukkitCopy(items.get(slot++)));
                targetInv.setBoots(CraftItemStack.asBukkitCopy(items.get(slot++)));
            }

            if (plugin.getConfigManager().isSyncOffhand() && slot < items.size()) {
                targetInv.setItemInOffHand(CraftItemStack.asBukkitCopy(items.get(slot)));
            }

            if (plugin.getConfigManager().isSyncEnderChest()) {
                for (int i = 0; i < 27 && i < enderItems.size(); i++) {
                    target.getEnderChest().setItem(i, CraftItemStack.asBukkitCopy(enderItems.get(i)));
                }
            }

            if (syncXp) {
                target.setTotalExperience(srcTotalXp);
                target.setLevel(srcLevel);
                target.setExp(srcExp);
            }

            sendInventoryUpdate(nmsTarget);
        }
        
        if (plugin.getConfigManager().isLogSyncEvents()) {
            plugin.getLogger().info("Synced inventory from " + source.getName() + 
                " to " + targets.size() + " players");
        }
    }
    
    private void sendInventoryUpdate(ServerPlayer player) {
        player.containerMenu.sendAllDataToRemote();
    }
    
    private Collection<Player> getTargetPlayers(Player source) {
        if (plugin.getConfigManager().isTeamsEnabled()) {
            return plugin.getTeamManager().getTeamMembers(source);
        } else {
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            players.removeIf(p -> bypassPlayers.contains(p.getUniqueId()));
            return players;
        }
    }
    
    public void clearAllInventories(Player diedPlayer, boolean clearDiedPlayer) {
        Collection<Player> targets = getTargetPlayers(diedPlayer);
        
        for (Player target : targets) {
            if (target.getUniqueId().equals(diedPlayer.getUniqueId())) {
                if (!clearDiedPlayer) {
                    continue;
                }
            }
            
            target.getInventory().clear();
            if (plugin.getConfigManager().isSyncEnderChest()) {
                target.getEnderChest().clear();
            }
        }
    }
    
    public void injectPlayer(Player player) {
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            Channel channel = nmsPlayer.connection.connection.channel;
            
            if (channel.pipeline().get("multiinvsync_handler") != null) {
                return;
            }
            
            channel.pipeline().addBefore("packet_handler", "multiinvsync_handler", 
                new InventoryPacketHandler(player));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to inject player: " + player.getName());
        }
    }
    
    public void uninjectPlayer(Player player) {
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            Channel channel = nmsPlayer.connection.connection.channel;
            
            if (channel.pipeline().get("multiinvsync_handler") != null) {
                channel.pipeline().remove("multiinvsync_handler");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to uninject player: " + player.getName());
        }
    }
    
    public void addBypassPlayer(UUID uuid) {
        bypassPlayers.add(uuid);
    }
    
    public void removeBypassPlayer(UUID uuid) {
        bypassPlayers.remove(uuid);
    }
    
    public boolean isBypassed(UUID uuid) {
        return bypassPlayers.contains(uuid);
    }
    
    private class InventoryPacketHandler extends ChannelDuplexHandler {
        private final Player player;
        
        public InventoryPacketHandler(Player player) {
            this.player = player;
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (isCreativeSetSlot(msg)) {
                // Creative slot changes finalize after server tick; sync slightly later
                plugin.getScheduler().runMainLater(() -> syncInventory(player), 2L);
            } else if (isInventoryMutationPacket(msg)) {
                plugin.getScheduler().runMainLater(() -> syncInventory(player), 1L);
            }
            super.channelRead(ctx, msg);
        }
        
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (isInventoryClientUpdate(msg)) {
                plugin.getScheduler().runMainLater(() -> syncInventory(player), 1L);
            }
            super.write(ctx, msg, promise);
        }
    }

    private boolean isInventoryMutationPacket(Object msg) {
        if (msg == null) {
            return false;
        }
        String name = msg.getClass().getSimpleName();
        // Cover modern and legacy names without hard-linking classes (avoids NoClassDefFoundError)
        return name.contains("ContainerClick")
            || name.contains("PickItem")
            || name.contains("WindowClick")
            || name.contains("SetSlot");
    }

    private boolean isCreativeSetSlot(Object msg) {
        if (msg == null) return false;
        String name = msg.getClass().getSimpleName();
        return name.contains("SetCreativeModeSlot");
    }

    private boolean isInventoryClientUpdate(Object msg) {
        if (msg == null) return false;
        String name = msg.getClass().getSimpleName();
        // Outbound inventory updates from server to client; use name matching to stay version-agnostic
        return name.contains("ClientboundContainerSetSlot")
            || name.contains("ClientboundContainerSetContent")
            || name.contains("SetSlot");
    }
    
    private static class SyncBatch {
        private final Player source;
        private final List<Player> targets;
        private final long timestamp;
        
        public SyncBatch(Player source, List<Player> targets) {
            this.source = source;
            this.targets = targets;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
