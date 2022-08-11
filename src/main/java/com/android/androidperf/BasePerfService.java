package com.android.androidperf;

import java.util.concurrent.*;

public class BasePerfService extends Thread {

    protected ConcurrentLinkedQueue<Object> dataQueue = new ConcurrentLinkedQueue<>();
    protected Device device = null;
    protected long timer = 0;
    protected Future<?> updateTask = null;
    protected Future<?> dumpTask = null;
    protected ScheduledExecutorService executorService = Executors.newScheduledThreadPool(3);

    void dump() {}
    void update() {timer++;}
    void begin() {
        updateTask = executorService.scheduleAtFixedRate(this::update, 0, 1000, TimeUnit.MILLISECONDS);
        dumpTask = executorService.scheduleAtFixedRate(this::dump, 1000, 1000, TimeUnit.MILLISECONDS);
    }
    void end() {
        if (updateTask != null)
            updateTask.cancel(true);
        if (dumpTask != null)
            dumpTask.cancel(true);
        timer = 0;
        dataQueue.clear();
    }
    void shutdown() {executorService.shutdownNow();}
    void registerDevice(Device dev) {
        device = dev;
    }
}
