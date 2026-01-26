package net.opmasterleo.multiinvsync.scheduler;

import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class FoliaSchedulerAdapter implements SchedulerAdapter {
    private final MultiInvSyncPlugin plugin;
    private final Object globalScheduler;
    private final Object asyncScheduler;
    private final Method globalRun;
    private final Method globalRunDelayed;
    private final Method asyncRunNow;
    private final Method entityGetScheduler;
    private final Method entityRun;
    private final Method entityRunDelayed;
    private final List<Object> tasks = new ArrayList<>();

    public FoliaSchedulerAdapter(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Method getGlobal = bukkitClass.getMethod("getGlobalRegionScheduler");
            Method getAsync = bukkitClass.getMethod("getAsyncScheduler");

            this.globalScheduler = getGlobal.invoke(null);
            this.asyncScheduler = getAsync.invoke(null);

            Class<?> globalSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class<?> asyncSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            Class<?> entitySchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");

            this.globalRun = globalSchedulerClass.getMethod("run", Plugin.class, Consumer.class);
            this.globalRunDelayed = globalSchedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
            this.asyncRunNow = asyncSchedulerClass.getMethod("runNow", Plugin.class, Consumer.class);
            
            this.entityGetScheduler = org.bukkit.entity.Entity.class.getMethod("getScheduler");
            this.entityRun = entitySchedulerClass.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
            this.entityRunDelayed = entitySchedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
        } catch (Exception e) {
            throw new IllegalStateException("Folia scheduler unavailable", e);
        }
    }

    @Override
    public void runMain(Runnable task) {
        Consumer<Object> consumer = scheduledTask -> task.run();
        Object handle = invoke(globalRun, globalScheduler, plugin, consumer);
        if (handle != null) {
            tasks.add(handle);
        }
    }

    @Override
    public void runMainLater(Runnable task, long delayTicks) {
        Consumer<Object> consumer = scheduledTask -> task.run();
        Object handle = invoke(globalRunDelayed, globalScheduler, plugin, consumer, delayTicks);
        if (handle != null) {
            tasks.add(handle);
        }
    }

    @Override
    public void runAsync(Runnable task) {
        Consumer<Object> consumer = scheduledTask -> task.run();
        invoke(asyncRunNow, asyncScheduler, plugin, consumer);
    }
    
    @Override
    public void runAtEntity(org.bukkit.entity.Entity entity, Runnable task) {
        try {
            Object scheduler = entityGetScheduler.invoke(entity);
            Consumer<Object> consumer = scheduledTask -> task.run();
            // run(Plugin, Consumer, Runnable retired)
            invoke(entityRun, scheduler, plugin, consumer, null);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to schedule task on entity " + entity.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void runAtEntityLater(org.bukkit.entity.Entity entity, Runnable task, long delayTicks) {
        try {
            Object scheduler = entityGetScheduler.invoke(entity);
            Consumer<Object> consumer = scheduledTask -> task.run();
            // runDelayed(Plugin, Consumer, Runnable retired, long delay)
            invoke(entityRunDelayed, scheduler, plugin, consumer, null, delayTicks);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to schedule delayed task on entity " + entity.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        for (Object handle : tasks) {
            try {
                Method cancel = handle.getClass().getMethod("cancel");
                cancel.invoke(handle);
            } catch (Exception ignored) {
                // best effort; Folia handles completed tasks automatically
            }
        }
        tasks.clear();
    }

    private Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (Exception e) {
            plugin.getLogger().warning("Folia scheduler invoke failed: " + e.getMessage());
            return null;
        }
    }
}
