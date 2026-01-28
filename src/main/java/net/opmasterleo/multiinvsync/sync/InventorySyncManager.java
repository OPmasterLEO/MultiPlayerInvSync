package net.opmasterleo.multiinvsync.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;

public class InventorySyncManager {
    
    private final MultiInvSyncPlugin plugin;
    private final Map<UUID, Long> lastSyncTime = new ConcurrentHashMap<>();
    private final Set<UUID> bypassPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> syncingNow = ConcurrentHashMap.newKeySet();
    private final Set<UUID> processingSync = ConcurrentHashMap.newKeySet();
    private final Set<UUID> queuedSync = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> snapshotSignatures = new ConcurrentHashMap<>();
    
    private volatile boolean syncMainInventory;
    private volatile boolean syncArmor;
    private volatile boolean syncOffhand;
    private volatile boolean syncEnderChest;
    private volatile boolean syncCursor;
    private volatile boolean syncExperience;
    private volatile boolean syncHealth;
    private volatile boolean syncHunger;
    private volatile boolean syncPose;
    private volatile boolean syncEffects;
    private volatile int syncDelayTicks;
    private volatile boolean logSyncEvents;
    
    public InventorySyncManager(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
        refreshConfigCache();
    }
    
    public final void refreshConfigCache() {
        syncMainInventory = plugin.getConfigManager().isSyncMainInventory();
        syncArmor = plugin.getConfigManager().isSyncArmor();
        syncOffhand = plugin.getConfigManager().isSyncOffhand();
        syncEnderChest = plugin.getConfigManager().isSyncEnderChest();
        syncCursor = plugin.getConfigManager().isSyncCursor();
        syncExperience = plugin.getConfigManager().isSyncExperience();
        syncHealth = plugin.getConfigManager().isSyncHealth();
        syncHunger = plugin.getConfigManager().isSyncHunger();
        syncPose = plugin.getConfigManager().isSyncPose();
        syncEffects = plugin.getConfigManager().isSyncEffects();
        syncDelayTicks = plugin.getConfigManager().getSyncDelayTicks();
        logSyncEvents = plugin.getConfigManager().isLogSyncEvents();
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
    }
    
    public void requestSync(Player source, long delayTicks, boolean persist) {
        UUID id = source.getUniqueId();
        if (!queuedSync.add(id)) {
            return;
        }
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
        
        if (lastSync != null && currentTime - lastSync < (syncDelayTicks * 50L)) {
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
                
                if (logSyncEvents) {
                    int targetCount = targets.size() - 1;
                    plugin.getLogger().info(String.format("Synced inventory from %s to %d players", 
                        source.getName(), targetCount));
                }
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Sync error", e);
            } finally {
                syncingNow.remove(sourceId);
            }
        });
    }

    private InventorySnapshot captureSnapshot(Player source) {
        int itemCapacity = (syncMainInventory ? 36 : 0) + (syncArmor ? 4 : 0) + (syncOffhand ? 1 : 0);
        List<ItemStack> items = itemCapacity > 0 ? new ArrayList<>(itemCapacity) : null;
        PlayerInventory inv = source.getInventory();
        int srcLevel = 0;
        int srcTotalXp = 0;
        float srcExp = 0.0F;
        double health = 0.0;
        int foodLevel = 0;
        float saturation = 0.0F;
        Pose pose = null;
        Collection<PotionEffect> effects = null;

        if (syncMainInventory) {
            for (int i = 0; i < 36; i++) {
                items.add(CraftItemStack.asNMSCopy(inv.getItem(i)));
            }
        }
        
        if (syncArmor && items != null) {
            items.add(CraftItemStack.asNMSCopy(inv.getHelmet()));
            items.add(CraftItemStack.asNMSCopy(inv.getChestplate()));
            items.add(CraftItemStack.asNMSCopy(inv.getLeggings()));
            items.add(CraftItemStack.asNMSCopy(inv.getBoots()));
        }
        
        if (syncOffhand && items != null) {
            items.add(CraftItemStack.asNMSCopy(inv.getItemInOffHand()));
        }

        ItemStack cursorItem = null;
        if (syncCursor) {
            cursorItem = CraftItemStack.asNMSCopy(source.getItemOnCursor());
        }

        List<ItemStack> enderItems = null;
        if (syncEnderChest) {
            enderItems = new ArrayList<>(27);
            for (int i = 0; i < 27; i++) {
                enderItems.add(CraftItemStack.asNMSCopy(source.getEnderChest().getItem(i)));
            }
        }
        
        if (syncExperience) {
            srcLevel = source.getLevel();
            srcTotalXp = source.getTotalExperience();
            srcExp = source.getExp();
        }
        
        if (syncHealth) {
            health = source.getHealth();
        }
        
        if (syncHunger) {
            foodLevel = source.getFoodLevel();
            saturation = source.getSaturation();
        }
        
        if (syncPose) {
            pose = source.getPose();
        }
        
        if (syncEffects) {
            Collection<PotionEffect> activeEffects = source.getActivePotionEffects();
            if (!activeEffects.isEmpty()) {
                effects = new ArrayList<>(activeEffects);
            }
        }
        
        return new InventorySnapshot(items, enderItems, cursorItem, srcLevel, srcTotalXp, srcExp, 
                                      health, foodLevel, saturation, pose, effects);
    }
    
    private void applySnapshot(Player target, InventorySnapshot snapshot) {
        if (!target.isOnline()) return;

        UUID targetId = target.getUniqueId();
        if (!processingSync.add(targetId)) {
            return;
        }
        
        try {
            ServerPlayer nmsTarget = ((CraftPlayer) target).getHandle();
            PlayerInventory targetInv = target.getInventory();
            
            if (snapshot.items != null) {
                int slot = 0;
                if (syncMainInventory) {
                    for (int i = 0; i < 36 && slot < snapshot.items.size(); i++) {
                        setItemIfChanged(targetInv, i, snapshot.items.get(slot++));
                    }
                }

                if (syncArmor && slot + 3 < snapshot.items.size()) {
                    setItemIfChanged(targetInv, 39, snapshot.items.get(slot++)); // Helmet
                    setItemIfChanged(targetInv, 38, snapshot.items.get(slot++)); // Chest
                    setItemIfChanged(targetInv, 37, snapshot.items.get(slot++)); // Legs
                    setItemIfChanged(targetInv, 36, snapshot.items.get(slot++)); // Boots
                }

                if (syncOffhand && slot < snapshot.items.size()) {
                    setItemIfChanged(targetInv, 40, snapshot.items.get(slot));
                }
            }
            
            if (syncCursor && snapshot.cursorItem != null) {
                target.setItemOnCursor(CraftItemStack.asBukkitCopy(snapshot.cursorItem));
            }

            if (syncEnderChest && snapshot.enderItems != null) {
                for (int i = 0; i < 27 && i < snapshot.enderItems.size(); i++) {
                    target.getEnderChest().setItem(i, CraftItemStack.asBukkitCopy(snapshot.enderItems.get(i)));
                }
            }

            if (syncExperience && target.getTotalExperience() != snapshot.xpTotal) {
                target.setTotalExperience(snapshot.xpTotal);
                target.setLevel(snapshot.xpLevel);
                target.setExp(snapshot.xpExp);
            }
            
            if (syncHealth && snapshot.health > 0) {
                double currentHealth = target.getHealth();
                if (currentHealth != snapshot.health) {
                    var maxHealthAttr = target.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                    double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
                    target.setHealth(Math.min(snapshot.health, maxHealth));
                }
            }
            
            if (syncHunger) {
                int currentFood = target.getFoodLevel();
                float currentSat = target.getSaturation();
                if (currentFood != snapshot.foodLevel || currentSat != snapshot.saturation) {
                    if (currentFood != snapshot.foodLevel) {
                        target.setFoodLevel(snapshot.foodLevel);
                    }
                    if (currentSat != snapshot.saturation) {
                        target.setSaturation(snapshot.saturation);
                    }
                }
            }
            
            if (syncPose && snapshot.pose != null && target.getPose() != snapshot.pose) {
                target.setPose(snapshot.pose, true);
            }
            
            if (syncEffects) {
                syncPotionEffects(target, snapshot.effects);
            }

            // Always refresh container to ensure client view is correct
            sendInventoryUpdate(nmsTarget);
        } finally {
            processingSync.remove(targetId);
        }
    }
    
    private void syncPotionEffects(Player target, Collection<PotionEffect> sourceEffects) {
        if (sourceEffects == null || sourceEffects.isEmpty()) {
            Collection<PotionEffect> currentEffects = target.getActivePotionEffects();
            if (!currentEffects.isEmpty()) {
                for (PotionEffect effect : currentEffects) {
                    target.removePotionEffect(effect.getType());
                }
            }
            return;
        }
        
        Collection<PotionEffect> currentEffects = target.getActivePotionEffects();
        
        // Build a set of effect types from source for quick lookup
        Set<org.bukkit.potion.PotionEffectType> sourceTypes = ConcurrentHashMap.newKeySet();
        for (PotionEffect effect : sourceEffects) {
            sourceTypes.add(effect.getType());
        }
        
        // Remove effects that don't exist in source
        for (PotionEffect currentEffect : currentEffects) {
            if (!sourceTypes.contains(currentEffect.getType())) {
                target.removePotionEffect(currentEffect.getType());
            }
        }
        
        // Add or update effects from source
        for (PotionEffect sourceEffect : sourceEffects) {
            boolean needsUpdate = true;
            for (PotionEffect currentEffect : currentEffects) {
                if (currentEffect.getType().equals(sourceEffect.getType())) {
                    // Check if effect matches exactly
                    if (currentEffect.getAmplifier() == sourceEffect.getAmplifier() &&
                        currentEffect.getDuration() == sourceEffect.getDuration() &&
                        currentEffect.hasParticles() == sourceEffect.hasParticles() &&
                        currentEffect.isAmbient() == sourceEffect.isAmbient()) {
                        needsUpdate = false;
                    }
                    break;
                }
            }
            if (needsUpdate) {
                target.addPotionEffect(new PotionEffect(sourceEffect.getType(), 
                    sourceEffect.getDuration(), sourceEffect.getAmplifier(), 
                    sourceEffect.isAmbient(), sourceEffect.hasParticles(), sourceEffect.hasIcon()));
            }
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
        final double health;
        final int foodLevel;
        final float saturation;
        final Pose pose;
        final Collection<PotionEffect> effects;
        
        InventorySnapshot(List<ItemStack> items, List<ItemStack> enderItems, ItemStack cursorItem, 
                         int xpLevel, int xpTotal, float xpExp, double health, int foodLevel, 
                         float saturation, Pose pose, Collection<PotionEffect> effects) {
            this.items = items;
            this.enderItems = enderItems;
            this.cursorItem = cursorItem;
            this.xpLevel = xpLevel;
            this.xpTotal = xpTotal;
            this.xpExp = xpExp;
            this.health = health;
            this.foodLevel = foodLevel;
            this.saturation = saturation;
            this.pose = pose;
            this.effects = effects;
        }

        long computeSignature() {
            long h = 1125899906842597L;
            if (items != null) {
                for (ItemStack stack : items) {
                    h = 31 * h + fastHash(stack);
                }
            }
            if (enderItems != null) {
                for (ItemStack stack : enderItems) {
                    h = 31 * h + fastHash(stack);
                }
            }
            h = 31 * h + fastHash(cursorItem);
            h = 31 * h + xpLevel;
            h = 31 * h + xpTotal;
            h = 31 * h + Float.floatToIntBits(xpExp);
            h = 31 * h + Double.hashCode(health);
            h = 31 * h + foodLevel;
            h = 31 * h + Float.floatToIntBits(saturation);
            h = 31 * h + (pose != null ? pose.ordinal() : 0);
            if (effects != null && !effects.isEmpty()) {
                for (PotionEffect effect : effects) {
                    h = 31 * h + effect.getType().getKey().hashCode();
                    h = 31 * h + effect.getAmplifier();
                    h = 31 * h + effect.getDuration();
                }
            }
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
        UUID diedUUID = diedPlayer.getUniqueId();
        
        for (Player target : targets) {
            if (target.getUniqueId().equals(diedUUID) && !clearDiedPlayer) {
                continue;
            }
            
            target.getInventory().clear();
            if (syncEnderChest) {
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
            plugin.getLogger().log(java.util.logging.Level.WARNING, 
                String.format("Failed to inject player: %s", player.getName()), e);
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
            plugin.getLogger().log(java.util.logging.Level.WARNING, 
                String.format("Failed to uninject player: %s", player.getName()), e);
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
}
