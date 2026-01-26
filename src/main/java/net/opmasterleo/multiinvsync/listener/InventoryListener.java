package net.opmasterleo.multiinvsync.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;

public class InventoryListener implements Listener {
    
    private final MultiInvSyncPlugin plugin;
    
    public InventoryListener(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
    }

    private void triggerSync(Player player) {
        // Immediate main-thread sync for Bukkit event sources (inventory clicks, item pickup, etc.)
        plugin.getScheduler().runMain(() -> plugin.getSyncManager().syncInventory(player));
    }
    
    // Removed onInventoryClick, onInventoryDrag, onPlayerDropItem, onPlayerSwapHandItems 
    // as they are now handled by NMS Packet Listeners for optimization.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof Player player) {
            triggerSync(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        if (event.getInventory().getHolder() instanceof Player player) {
            triggerSync(player);
        }
    }

    // Removed onPlayerDropItem - handled by NMS

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            triggerSync(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAttemptPickupItem(PlayerAttemptPickupItemEvent event) {
        triggerSync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        triggerSync(event.getPlayer());
    }

    // Removed onPlayerSwapHandItems - handled by NMS

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        triggerSync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemBreak(PlayerItemBreakEvent event) {
        triggerSync(event.getPlayer());
    }
}
