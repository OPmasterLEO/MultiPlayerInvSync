package net.opmasterleo.multiinvsync.scheduler;

import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Entity;

public class BukkitSchedulerAdapter implements SchedulerAdapter {
    private final MultiInvSyncPlugin plugin;
    private final BukkitScheduler scheduler;
    private final List<BukkitTask> asyncTasks = new ArrayList<>();

    public BukkitSchedulerAdapter(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
        this.scheduler = Bukkit.getScheduler();
    }

    @Override
    public void runMain(Runnable task) {
        scheduler.runTask(plugin, task);
    }

    @Override
    public void runMainLater(Runnable task, long delayTicks) {
        scheduler.runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public void runAsync(Runnable task) {
        asyncTasks.add(scheduler.runTaskAsynchronously(plugin, task));
    }
    
    @Override
    public void runAtEntity(Entity entity, Runnable task) {
        scheduler.runTask(plugin, task);
    }

    @Override
    public void runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        scheduler.runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public void shutdown() {
        for (BukkitTask task : asyncTasks) {
            task.cancel();
        }
        asyncTasks.clear();
    }
}
