package net.opmasterleo.multiinvsync.scheduler;

public interface SchedulerAdapter {
    void runMain(Runnable task);
    void runMainLater(Runnable task, long delayTicks);
    void runAsync(Runnable task);
    void shutdown();
}
