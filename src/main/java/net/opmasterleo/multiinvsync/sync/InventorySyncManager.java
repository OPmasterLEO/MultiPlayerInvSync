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
import net.minecraft.nbt.CompoundTag;
import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;

public class InventorySyncManager {
    
    private final MultiInvSyncPlugin plugin;
    private final Map<UUID, Long> lastSyncTime = new ConcurrentHashMap<>();
    private final Set<UUID> bypassPlayers = ConcurrentHashMap.newKeySet();
    private final List<SyncBatch> pendingBatches = new CopyOnWriteArrayList<>();
    private final Set<UUID> syncingNow = ConcurrentHashMap.newKeySet();
    private final Set<UUID> processingSync = ConcurrentHashMap.newKeySet();
    private final Set<UUID> queuedSync = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> snapshotSignatures = new ConcurrentHashMap<>();
    
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
    
    public void requestSync(Player source, long delayTicks, boolean persist) {
        UUID id = source.getUniqueId();
        if (!queuedSync.add(id)) {
            return;
        }
        long delay = Math.max(1L, delayTicks);
        plugin.getScheduler().runMainLater(() -> {
            queuedSync.remove(id);
            if (!source.isOnline()) {
                return;
            }
            syncInventory(source);
            if (persist && plugin.getCrossServerSyncManager() != null && plugin.getCrossServerSyncManager().isEnabled()) {
                plugin.getCrossServerSyncManager().saveInventoryToRedis(source, true);
            }
        }, delayTicks);
    }

    public void syncInventory(Player source) {
        UUID sourceId = source.getUniqueId();
        if (bypassPlayers.contains(sourceId)) {
            return;
        }

        if (processingSync.contains(sourceId)) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        Long lastSync = lastSyncTime.get(sourceId);
        
        int delayTicks = plugin.getConfigManager().getSyncDelayTicks();
        if (lastSync != null && currentTime - lastSync < (delayTicks * 50L)) {
            return;
        }
        
        if (!syncingNow.add(sourceId)) {
            return; // already syncing this player; avoid re-entrancy
        }
        lastSyncTime.put(sourceId, currentTime);
        
        // Schedule on Source Region/Thread to capture state
        plugin.getScheduler().runAtEntity(source, () -> {
            try {
                if (!source.isOnline()) return;
                
                InventorySnapshot snapshot = captureSnapshot(source);
                long signature = snapshot.computeSignature();
                Long lastSig = snapshotSignatures.get(sourceId);
                if (lastSig != null && lastSig == signature) {
                    return;
                }
                snapshotSignatures.put(sourceId, signature);
                Collection<Player> targets = getTargetPlayers(source);
                
                if (targets.isEmpty() || (targets.size() == 1 && targets.contains(source))) return;
                
                // Distribute to targets
                for (Player target : targets) {
                    if (target.getUniqueId().equals(sourceId)) continue;
                    plugin.getScheduler().runAtEntity(target, () -> applySnapshot(target, snapshot));
                }
                
                if (plugin.getConfigManager().isLogSyncEvents()) {
                    plugin.getLogger().info("Synced inventory from " + source.getName() + 
                        " to " + (targets.size() - 1) + " players");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Sync error: " + e.getMessage());
            } finally {
                syncingNow.remove(sourceId);
            }
        });
    }

    private InventorySnapshot captureSnapshot(Player source) {
        List<ItemStack> items = new ArrayList<>(45);
        PlayerInventory inv = source.getInventory();
        int srcLevel = 0;
        int srcTotalXp = 0;
        float srcExp = 0.0F;

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

        ItemStack cursorItem = null;
        if (plugin.getConfigManager().isSyncCursor()) {
            cursorItem = CraftItemStack.asNMSCopy(source.getItemOnCursor());
        }

        List<ItemStack> enderItems = new ArrayList<>(27);
        if (plugin.getConfigManager().isSyncEnderChest()) {
            for (int i = 0; i < 27; i++) {
                enderItems.add(CraftItemStack.asNMSCopy(source.getEnderChest().getItem(i)));
            }
        }
        
        if (plugin.getConfigManager().isSyncExperience()) {
            srcLevel = source.getLevel();
            srcTotalXp = source.getTotalExperience();
            srcExp = source.getExp();
        }
        
        return new InventorySnapshot(items, enderItems, cursorItem, srcLevel, srcTotalXp, srcExp);
    }
    
    private void applySnapshot(Player target, InventorySnapshot snapshot) {
        if (!target.isOnline()) return;

        processingSync.add(target.getUniqueId());
        try {
            ServerPlayer nmsTarget = ((CraftPlayer) target).getHandle();
            PlayerInventory targetInv = target.getInventory();
            List<ItemStack> items = snapshot.items;
            int slot = 0;

            if (plugin.getConfigManager().isSyncMainInventory()) {
                for (int i = 0; i < 36 && slot < items.size(); i++) {
                    setItemIfChanged(targetInv, i, items.get(slot++));
                }
            }

            if (plugin.getConfigManager().isSyncArmor() && slot + 3 < items.size()) {
                setItemIfChanged(targetInv, 39, items.get(slot++)); // Helmet
                setItemIfChanged(targetInv, 38, items.get(slot++)); // Chest
                setItemIfChanged(targetInv, 37, items.get(slot++)); // Legs
                setItemIfChanged(targetInv, 36, items.get(slot++)); // Boots
            }

            if (plugin.getConfigManager().isSyncOffhand() && slot < items.size()) {
                setItemIfChanged(targetInv, 40, items.get(slot));
            }
            
            if (plugin.getConfigManager().isSyncCursor() && snapshot.cursorItem != null) {
                target.setItemOnCursor(CraftItemStack.asBukkitCopy(snapshot.cursorItem));
            }

            if (plugin.getConfigManager().isSyncEnderChest()) {
                List<ItemStack> enderItems = snapshot.enderItems;
                for (int i = 0; i < 27 && i < enderItems.size(); i++) {
                    // Ender chest doesn't use standard slot indices in same way for event triggers, but setItem is safe
                    target.getEnderChest().setItem(i, CraftItemStack.asBukkitCopy(enderItems.get(i)));
                }
            }

            if (plugin.getConfigManager().isSyncExperience()) {
                if (target.getTotalExperience() != snapshot.xpTotal) {
                    target.setTotalExperience(snapshot.xpTotal);
                    target.setLevel(snapshot.xpLevel);
                    target.setExp(snapshot.xpExp);
                }
            }

            // Always refresh container to ensure client view is correct
            sendInventoryUpdate(nmsTarget);
        } finally {
            processingSync.remove(target.getUniqueId());
        }
    }
    
    private void setItemIfChanged(PlayerInventory inv, int slot, ItemStack nmsItem) {
        org.bukkit.inventory.ItemStack bukkitItem = CraftItemStack.asBukkitCopy(nmsItem);
        org.bukkit.inventory.ItemStack current = inv.getItem(slot);
        
        if (current == null && bukkitItem.getType().isAir()) return;
        if (current != null && current.equals(bukkitItem)) return;
        
        inv.setItem(slot, bukkitItem);
    }
    
    private static class InventorySnapshot {
        final List<ItemStack> items;
        final List<ItemStack> enderItems;
        final ItemStack cursorItem;
        final int xpLevel;
        final int xpTotal;
        final float xpExp;
        
        InventorySnapshot(List<ItemStack> items, List<ItemStack> enderItems, ItemStack cursorItem, int xpLevel, int xpTotal, float xpExp) {
            this.items = items;
            this.enderItems = enderItems;
            this.cursorItem = cursorItem;
            this.xpLevel = xpLevel;
            this.xpTotal = xpTotal;
            this.xpExp = xpExp;
        }

        long computeSignature() {
            long h = 1125899906842597L;
            for (ItemStack stack : items) {
                h = 31 * h + fastHash(stack);
            }
            for (ItemStack stack : enderItems) {
                h = 31 * h + fastHash(stack);
            }
            h = 31 * h + fastHash(cursorItem);
            h = 31 * h + xpLevel;
            h = 31 * h + xpTotal;
            h = 31 * h + Float.floatToIntBits(xpExp);
            return h;
        }

        private long fastHash(ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return 0L;
            }
            long h = stack.getItem().hashCode();
            h = 31 * h + stack.getCount();
            try {
                CompoundTag tag = new CompoundTag();
                stack.save(null, tag);
                h = 31 * h + tag.hashCode();
            } catch (Exception ignored) {
            }
            return h;
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
        private long lastPacketTime = 0;
        
        public InventoryPacketHandler(Player player) {
            this.player = player;
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (isCreativeSetSlot(msg)) {
                plugin.getScheduler().runAtEntityLater(player, () -> requestSync(player, 3L, true), 0L);
            } else if (isInventoryMutationPacket(msg)) {
                long now = System.nanoTime();
                if (now - lastPacketTime > 500_000) {
                    lastPacketTime = now;
                    plugin.getScheduler().runAtEntityLater(player, () -> requestSync(player, 2L, true), 0L);
                }
            }
            super.channelRead(ctx, msg);
        }
        
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            // IGNORE Server to Client updates which may be caused by our own sync
            // Rely only on Client to Server (Input) packets to trigger syncs
            // or Bukkit Events for external plugins.
            // This prevents the infinite feedback loop (dup-echo).
            super.write(ctx, msg, promise);
        }
    }

    private boolean isInventoryMutationPacket(Object msg) {
        if (msg == null) {
            return false;
        }
        String name = msg.getClass().getSimpleName();
        // Cover modern and legacy names without hard-linking classes (avoids NoClassDefFoundError)
        return name.contains("ContainerClick") // Std click
            || name.contains("WindowClick")
            || name.contains("PickItem") // Creative pick
            || name.contains("SetSlot") // Sometimes client sends this?
            || name.contains("PlayerAction") // Drop/Swap/Dig
            || name.contains("BlockDig") // Drop item
            || name.contains("SwapHand")
            || name.contains("CreativeInventoryAction");
    }

    private boolean isCreativeSetSlot(Object msg) {
        if (msg == null) return false;
        String name = msg.getClass().getSimpleName();
        return name.contains("SetCreativeModeSlot") || name.contains("CreativeInventoryAction");
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
