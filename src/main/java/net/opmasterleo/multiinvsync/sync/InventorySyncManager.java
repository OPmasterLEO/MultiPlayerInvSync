package net.opmasterleo.multiinvsync.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;

public class InventorySyncManager {
    private static final String HANDLER_NAME = "multiinvsync_handler";
    private static final long PACKET_THROTTLE_NANOS = 2_000_000L;
    private static final byte PACKET_FLAG_NONE = 0;
    private static final byte PACKET_FLAG_INVENTORY_MUTATION = 1;
    private static final byte PACKET_FLAG_CREATIVE_SET_SLOT = 2;
    
    private final MultiInvSyncPlugin plugin;
    private final Map<UUID, Long> lastSyncTime = new ConcurrentHashMap<>();
    private final Set<UUID> bypassPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> syncingNow = ConcurrentHashMap.newKeySet();
    private final Set<UUID> processingSync = ConcurrentHashMap.newKeySet();
    private final Set<UUID> queuedSync = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> snapshotSignatures = new ConcurrentHashMap<>();
    private final Map<Class<?>, Byte> packetTypeCache = new ConcurrentHashMap<>();
    
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

        if (syncMainInventory && items != null) {
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
            boolean changed = false;
            
            if (snapshot.items != null) {
                int slot = 0;
                if (syncMainInventory) {
                    for (int i = 0; i < 36 && slot < snapshot.items.size(); i++) {
                        changed |= setItemIfChanged(targetInv, i, snapshot.items.get(slot++));
                    }
                }

                if (syncArmor && slot + 3 < snapshot.items.size()) {
                    changed |= setItemIfChanged(targetInv, 39, snapshot.items.get(slot++)); // Helmet
                    changed |= setItemIfChanged(targetInv, 38, snapshot.items.get(slot++)); // Chest
                    changed |= setItemIfChanged(targetInv, 37, snapshot.items.get(slot++)); // Legs
                    changed |= setItemIfChanged(targetInv, 36, snapshot.items.get(slot++)); // Boots
                }

                if (syncOffhand && slot < snapshot.items.size()) {
                    changed |= setItemIfChanged(targetInv, 40, snapshot.items.get(slot));
                }
            }
            
            if (syncCursor && snapshot.cursorItem != null) {
                org.bukkit.inventory.ItemStack currentCursor = target.getItemOnCursor();
                org.bukkit.inventory.ItemStack newCursor = CraftItemStack.asBukkitCopy(snapshot.cursorItem);
                if (!currentCursor.equals(newCursor)) {
                    target.setItemOnCursor(newCursor);
                    changed = true;
                }
            }

            if (syncEnderChest && snapshot.enderItems != null) {
                for (int i = 0; i < 27 && i < snapshot.enderItems.size(); i++) {
                    org.bukkit.inventory.ItemStack newItem = CraftItemStack.asBukkitCopy(snapshot.enderItems.get(i));
                    org.bukkit.inventory.ItemStack currentItem = target.getEnderChest().getItem(i);
                    if ((currentItem == null && !newItem.getType().isAir()) ||
                        (currentItem != null && !currentItem.equals(newItem))) {
                        target.getEnderChest().setItem(i, newItem);
                        changed = true;
                    }
                }
            }

            if (syncExperience && target.getTotalExperience() != snapshot.xpTotal) {
                target.setTotalExperience(snapshot.xpTotal);
                target.setLevel(snapshot.xpLevel);
                target.setExp(snapshot.xpExp);
                changed = true;
            }
            
            if (syncHealth && snapshot.health > 0) {
                double currentHealth = target.getHealth();
                if (currentHealth != snapshot.health) {
                    var maxHealthAttr = target.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                    double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
                    target.setHealth(Math.min(snapshot.health, maxHealth));
                    changed = true;
                }
            }
            
            if (syncHunger) {
                int currentFood = target.getFoodLevel();
                float currentSat = target.getSaturation();
                if (currentFood != snapshot.foodLevel || currentSat != snapshot.saturation) {
                    if (currentFood != snapshot.foodLevel) {
                        target.setFoodLevel(snapshot.foodLevel);
                        changed = true;
                    }
                    if (currentSat != snapshot.saturation) {
                        target.setSaturation(snapshot.saturation);
                        changed = true;
                    }
                }
            }
            
            if (syncPose && snapshot.pose != null && target.getPose() != snapshot.pose) {
                target.setPose(snapshot.pose, true);
                changed = true;
            }
            
            if (syncEffects) {
                changed |= syncPotionEffects(target, snapshot.effects);
            }

            if (changed) {
                sendInventoryUpdate(nmsTarget);
            }
        } finally {
            processingSync.remove(targetId);
        }
    }
    
    private boolean syncPotionEffects(Player target, Collection<PotionEffect> sourceEffects) {
        boolean changed = false;
        if (sourceEffects == null || sourceEffects.isEmpty()) {
            Collection<PotionEffect> currentEffects = target.getActivePotionEffects();
            if (!currentEffects.isEmpty()) {
                for (PotionEffect effect : currentEffects) {
                    target.removePotionEffect(effect.getType());
                }
                changed = true;
            }
            return changed;
        }
        
        Collection<PotionEffect> currentEffects = target.getActivePotionEffects();

        Map<org.bukkit.potion.PotionEffectType, PotionEffect> sourceByType = new HashMap<>(sourceEffects.size());
        for (PotionEffect effect : sourceEffects) {
            sourceByType.put(effect.getType(), effect);
        }

        for (PotionEffect currentEffect : currentEffects) {
            if (!sourceByType.containsKey(currentEffect.getType())) {
                target.removePotionEffect(currentEffect.getType());
                changed = true;
            }
        }

        Map<org.bukkit.potion.PotionEffectType, PotionEffect> currentByType = new HashMap<>(currentEffects.size());
        for (PotionEffect currentEffect : currentEffects) {
            currentByType.put(currentEffect.getType(), currentEffect);
        }

        for (PotionEffect sourceEffect : sourceEffects) {
            PotionEffect currentEffect = currentByType.get(sourceEffect.getType());
            boolean needsUpdate = currentEffect == null ||
                currentEffect.getAmplifier() != sourceEffect.getAmplifier() ||
                currentEffect.getDuration() != sourceEffect.getDuration() ||
                currentEffect.hasParticles() != sourceEffect.hasParticles() ||
                currentEffect.isAmbient() != sourceEffect.isAmbient() ||
                currentEffect.hasIcon() != sourceEffect.hasIcon();
            if (needsUpdate) {
                target.addPotionEffect(new PotionEffect(sourceEffect.getType(), 
                    sourceEffect.getDuration(), sourceEffect.getAmplifier(), 
                    sourceEffect.isAmbient(), sourceEffect.hasParticles(), sourceEffect.hasIcon()));
                changed = true;
            }
        }

        return changed;
    }
    
    private boolean setItemIfChanged(PlayerInventory inv, int slot, ItemStack nmsItem) {
        org.bukkit.inventory.ItemStack bukkitItem = CraftItemStack.asBukkitCopy(nmsItem);
        org.bukkit.inventory.ItemStack current = inv.getItem(slot);
        
        if (current == null && bukkitItem.getType().isAir()) return false;
        if (current != null && current.equals(bukkitItem)) return false;
        
        inv.setItem(slot, bukkitItem);
        return true;
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
            return stack.hashCode();
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
            
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                return;
            }
            
            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, 
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
            
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
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
    
    private class InventoryPacketHandler extends ChannelInboundHandlerAdapter {
        private final Player player;
        private long lastPacketTime;
        
        public InventoryPacketHandler(Player player) {
            this.player = player;
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            byte packetFlags = classifyPacket(msg);
            if ((packetFlags & PACKET_FLAG_CREATIVE_SET_SLOT) != 0) {
                requestSync(player, 3L, true);
            } else if ((packetFlags & PACKET_FLAG_INVENTORY_MUTATION) != 0) {
                long now = System.nanoTime();
                if (now - lastPacketTime >= PACKET_THROTTLE_NANOS) {
                    lastPacketTime = now;
                    requestSync(player, 2L, true);
                }
            }
            super.channelRead(ctx, msg);
        }
    }

    private byte classifyPacket(Object msg) {
        if (msg == null) {
            return PACKET_FLAG_NONE;
        }

        Class<?> packetClass = msg.getClass();
        Byte cached = packetTypeCache.get(packetClass);
        if (cached != null) {
            return cached;
        }

        byte flags = PACKET_FLAG_NONE;
        String name = msg.getClass().getSimpleName();
        if (name.contains("SetCreativeModeSlot") || name.contains("CreativeInventoryAction")) {
            flags |= PACKET_FLAG_CREATIVE_SET_SLOT;
        }

        if (name.contains("ContainerClick") // Std click
            || name.contains("WindowClick")
            || name.contains("PickItem") // Creative pick
            || name.contains("SetSlot") // Sometimes client sends this?
            || name.contains("PlayerAction") // Drop/Swap/Dig
            || name.contains("BlockDig") // Drop item
            || name.contains("SwapHand")
            || name.contains("CreativeInventoryAction")) {
            flags |= PACKET_FLAG_INVENTORY_MUTATION;
        }

        packetTypeCache.put(packetClass, flags);
        return flags;
    }
}
