package com.android.androidperf;

import javafx.application.Platform;

import java.util.Queue;
import java.util.concurrent.*;

public class BasePerfService extends Thread {

    protected ConcurrentLinkedQueue<Object> dataQueue = new ConcurrentLinkedQueue<>();
    protected Device device = null;
    protected int sampleIntervalMilli = 500;
    protected long dumpTimer = 0;
    protected Future<?> updateTask;
    protected Future<?> dumpTask;
    protected ScheduledExecutorService executorService = Executors.newScheduledThreadPool(3);

    void dump() {dumpTimer++;}
    void update() {}
    void begin() {
        updateTask = executorService.scheduleAtFixedRate(this::update, 0, sampleIntervalMilli, TimeUnit.MILLISECONDS);
        dumpTask = executorService.scheduleAtFixedRate(this::dump, 1000, 1000, TimeUnit.MILLISECONDS);
    }
    void end() {
        updateTask.cancel(true);
        dumpTask.cancel(true);
    }
    void shutdown() {executorService.shutdownNow();}
    void registerDevice(Device dev) {
        device = dev;
    }
}
