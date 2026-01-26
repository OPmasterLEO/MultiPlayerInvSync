package net.opmasterleo.multiinvsync.scheduler;

import org.bukkit.entity.Entity;

public interface SchedulerAdapter {
    void runMain(Runnable task);
    void runMainLater(Runnable task, long delayTicks);
    void runAsync(Runnable task);
    
    /**
     * Schedules a task to execute on the thread owning the entity.
     * On Bukkit: Same as runMain (Sync).
     * On Folia: Uses the EntityScheduler (Region Thread).
     */
    void runAtEntity(Entity entity, Runnable task);
    
    /**
     * Schedules a task to execute on the thread owning the entity after a delay.
     */
    void runAtEntityLater(Entity entity, Runnable task, long delayTicks);
    
    void shutdown();
}
