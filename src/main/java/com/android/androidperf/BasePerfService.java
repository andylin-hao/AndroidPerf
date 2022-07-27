package com.android.androidperf;

import javafx.application.Platform;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class BasePerfService extends Thread {

    protected ConcurrentLinkedQueue<Object> dataQueue = new ConcurrentLinkedQueue<>();
    protected Device device = null;
    protected int sampleIntervalMilli = 500;
    protected long dumpTimer = 0;
    protected ScheduledExecutorService executorService = Executors.newScheduledThreadPool(3);

    void dump() {dumpTimer++;}
    void update() {}
    void begin() {}
    void end() {}
    void shutdown() {executorService.shutdownNow();}
    void registerDevice(Device dev) {
        device = dev;
    }
}
