package com.android.androidperf;

import javafx.application.Platform;
import javafx.scene.chart.XYChart;

import java.util.ArrayList;

public class FPSPerfService extends BasePerfService {
    private long lastFrameTimestamp = 0;
    private Double totalTime = 0.;
    private int numFrames = 0;

    void clearLatencyData() {
        ArrayList<Layer> layers = device.getLayers();
        for (var layer : layers) {
            device.execCmd(String.format("dumpsys SurfaceFlinger --latency-clear '%s'", layer.layerName));
        }
    }

    ArrayList<Long> acquireLatencyData() {
        int selectedLayer = device.getTargetLayer();
        var layers = device.getLayers();
        Layer layer;
        if (selectedLayer != -1 && layers.size() != 0)
            layer = device.getLayers().get(selectedLayer);
        else
            return new ArrayList<>();
        String latencyData;

        if (layer == null)
            latencyData = device.execCmd("dumpsys SurfaceFlinger --latency");
        else
            latencyData = device.execCmd(String.format("dumpsys SurfaceFlinger --latency '%s'", layer.layerName));

        if (latencyData.isEmpty())
            return new ArrayList<>();

        // Data look like [desiredPresentTime] [actualPresentTime] [frameReadyTime]
        // All timestamps are in nanoseconds. We use actualPresentTime to calculate frame time
        latencyData = latencyData.replace("\r\n", "\n");
        String[] dataLines;
        int sdkVersion = device.getSdkVersion();
        if (sdkVersion == 24 || sdkVersion == 25) {
            dataLines = latencyData.split("\n\n");
            latencyData = dataLines[selectedLayer];
        }
        dataLines = latencyData.split("\n");
        var results = new ArrayList<Long>();

        for (int i = 1; i < dataLines.length; i++) {
            if (dataLines[i].length() == 0)
                break;
            String frameData = dataLines[i];
            String[] frameTimestamps = frameData.split("\\s");
            if (frameTimestamps.length != 3)
                continue;
            long actualPresentTime = Long.parseLong(frameTimestamps[1]);
            if (actualPresentTime == Long.MAX_VALUE)
                continue;
            results.add(actualPresentTime);
        }

        return results;
    }

    @Override
    void dump() {
        super.dump();
        while (!dataQueue.isEmpty()) {
            totalTime += (Double) dataQueue.poll();
            numFrames++;

            if (totalTime > 1000) {
                break;
            }
        }

        double fps = 0.;
        if (totalTime != 0)
            fps = numFrames / totalTime * 1000;
        System.out.printf("%f / %d = %f\n", totalTime, numFrames, fps);
        double finalFps = fps;
        Platform.runLater(() -> device.getController().addDataToChart("FPS", new XYChart.Data<>(dumpTimer, finalFps)));
        totalTime = 0.;
        numFrames = 0;
    }

    @Override
    void update() {
        var frameResults = acquireLatencyData();

        int i = 0;
        for (; i < frameResults.size(); i++) {
            if (frameResults.get(i) > lastFrameTimestamp)
                break;
        }
        if (i < frameResults.size()) {
            lastFrameTimestamp = frameResults.get(frameResults.size() - 1);
            for (; i < frameResults.size(); i++) {
                if (i == 0)
                    continue;
                long lastFrameTimestamp = frameResults.get(i - 1);
                if (lastFrameTimestamp == 0)
                    continue;
                Double frameTime = (Double.valueOf(frameResults.get(i)) - lastFrameTimestamp) / 1e6;
                if (frameTime > 1e6) {
                    System.out.println(frameTime);
                }
                dataQueue.add(frameTime);
            }
        }
    }

    @Override
    void begin() {
        clearLatencyData();
        super.begin();
    }
}
