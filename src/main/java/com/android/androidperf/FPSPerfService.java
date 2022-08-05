package com.android.androidperf;

import javafx.application.Platform;
import javafx.scene.chart.XYChart;

import java.util.ArrayList;

public class FPSPerfService extends BasePerfService {
    private long lastFrameTimestamp = 0;
    private Double totalTime = 0.;
    private int numFrames = 0;
    Layer targetLayer = null;
    boolean targetShouldChange = true;

    void clearLatencyData() {
        device.getLayers().forEach(layer -> device.execCmd(String.format("dumpsys SurfaceFlinger --latency-clear '%s'", layer.layerName)));
    }

    ArrayList<Long> acquireLatencyData(Layer layer) {
        if (layer == null)
            return new ArrayList<>();
        String latencyData;

        latencyData = device.execCmd(String.format("dumpsys SurfaceFlinger --latency '%s'", layer.layerName));

        if (latencyData.isEmpty())
            return new ArrayList<>();

        // Data look like [desiredPresentTime] [actualPresentTime] [frameReadyTime]
        // All timestamps are in nanoseconds. We use actualPresentTime to calculate frame time
        latencyData = latencyData.replace("\r\n", "\n");
        String[] dataLines = latencyData.split("\n\n");
        if (dataLines.length > 1) {
            if (layer.id >= dataLines.length)
                return new ArrayList<>();
            latencyData = dataLines[layer.id];
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
            try {
                long actualPresentTime = Long.parseLong(frameTimestamps[1]);
                if (actualPresentTime == Long.MAX_VALUE)
                    continue;
                results.add(actualPresentTime);
            } catch (Exception ignored) {
            }
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
        if (fps < 1.)
            targetShouldChange = true;
        double finalFps = fps;
        Platform.runLater(() -> device.getController().addDataToChart("FPS", new XYChart.Data<>(dumpTimer, finalFps)));
        totalTime = 0.;
        numFrames = 0;
    }

    boolean isLayerActive(ArrayList<Long> frameResults) {
        if (frameResults.isEmpty())
            return false;
        if (frameResults.stream().noneMatch(i -> i > 0))
            return false;
        int i = 0;
        for (; i < frameResults.size(); i++) {
            if (frameResults.get(i) > lastFrameTimestamp)
                break;
        }
        double totalTime = 0;
        double frameCount = 0;
        if (i < frameResults.size()) {
            for (; i < frameResults.size(); i++) {
                if (i == 0)
                    continue;
                long lastFrameTimestamp = frameResults.get(i - 1);
                if (lastFrameTimestamp == 0)
                    continue;
                totalTime += (Double.valueOf(frameResults.get(i)) - lastFrameTimestamp) / 1e6;
                frameCount++;
            }
        } else return false;
        double fps = 0.;
        if (totalTime != 0)
            fps = frameCount / totalTime * 1000;
        return fps >= 1;
    }

    void updateTargetLayer() {
        var layers = new ArrayList<>(device.getLayers());
        for (var layer : layers) {
            var frameResults = acquireLatencyData(layer);
            if (isLayerActive(frameResults)) {
                if (layer.isSurfaceView) {
                    targetLayer = layer;
                    targetShouldChange = false;
                    break;
                } else {
                    targetLayer = layer;
                    targetShouldChange = true;
                }
            }

        }
    }

    @Override
    void update() {
        var frameResults = acquireLatencyData(targetLayer);
        try {
            if (targetLayer == null || !isLayerActive(frameResults) || targetShouldChange) {
                updateTargetLayer();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

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
