package com.android.androidperf;

import javafx.application.Platform;
import javafx.scene.chart.XYChart;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class FPSPerfService extends BasePerfService {
    private static final Logger LOGGER = LogManager.getLogger(FPSPerfService.class);
    private long lastFrameTimestamp = 0;
    Layer targetLayer = null;
    boolean targetShouldChange = true;

    Future<?> updateLayerTask = null;

    void clearLatencyData() {
        var layers = new ArrayList<>(device.getLayers());
        layers.forEach(layer -> device.execCmd(String.format("dumpsys SurfaceFlinger --latency-clear '%s'", layer.layerName)));
    }

    ArrayList<Long> acquireLatencyData(Layer layer) {
        if (layer == null)
            return new ArrayList<>();
        String latencyData;

        latencyData = device.sendMSG(String.format("latency %s", layer.layerName));
        if (latencyData == null)
            latencyData = device.execCmd(String.format("dumpsys SurfaceFlinger --latency '%s'", layer.layerName));

        if (latencyData.isEmpty())
            return new ArrayList<>();

        // Data look like [desiredPresentTime] [actualPresentTime] [frameReadyTime]
        // All timestamps are in nanoseconds. We use actualPresentTime to calculate frame time
        latencyData = latencyData.replace("\r\n", "\n");
        long padding = 0;
        int paddingIndex = latencyData.indexOf("PADDING");
        if (paddingIndex >= 0) {
            String paddingStr = latencyData.substring(paddingIndex);
            String[] paddingInfo = paddingStr.split("\\s");
            if (paddingInfo.length >= 2) {
                try {
                    padding = Long.parseLong(paddingInfo[1]);
                } catch (NumberFormatException ignored) {}
            }
        }
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
            try {
                long actualPresentTime = Long.parseLong(frameTimestamps[1]);
                if (actualPresentTime == Long.MAX_VALUE)
                    continue;
                results.add(actualPresentTime);
            } catch (Exception ignored) {
            }
        }
        if (!results.isEmpty()) {
            long last = results.get(results.size() - 1);
            if (last != 0)
                results.add(padding);
        }

        return results;
    }

    /**
     * Check if the layer is active, i.e., if it's producing frames
     * @param frameResults the layer's frame latency data
     * @return true if it's active
     */
    boolean isLayerActive(ArrayList<Long> frameResults) {
        // frame result is empty
        if (frameResults.isEmpty())
            return false;
        // frame result contains only 0
        if (frameResults.stream().noneMatch(i -> i > 0))
            return false;

        // frame result doesn't have any new data
        int i = 0;
        for (; i < frameResults.size(); i++) {
            if (frameResults.get(i) > lastFrameTimestamp)
                break;
        }
        double totalTime = 0;
        double frameCount = 0;
        long preceding = lastFrameTimestamp;
        if (i < frameResults.size()) {
            if (i != frameResults.size() - 1) {
                for (; i < frameResults.size(); i++) {
                    if (preceding == 0) {
                        preceding = frameResults.get(i);
                        continue;
                    }
                    totalTime += (Double.valueOf(frameResults.get(i)) - preceding) / 1e6;
                    preceding = frameResults.get(i);
                    frameCount++;
                }
            }
        } else return false;
        double fps = 0.;
        if (frameCount < 5)
            return false;
        if (totalTime != 0)
            fps = frameCount / totalTime * 1000;
        return fps >= 1;
    }

    void updateTargetLayer() {
        var layers = new ArrayList<>(device.getLayers());
        for (var layer : layers) {
            LOGGER.debug(layer);
            if (!layer.isVisible)
                continue;
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

    private void updateLayers() {
        String packageName = device.getTargetPackage();
        if (packageName != null && !packageName.isEmpty()) {
            if (device.updateLayerList())
                targetShouldChange = true;
        }
    }

    @Override
    void update() {
        // get the latency data of the target layer
        var frameResults = acquireLatencyData(targetLayer);
        try {
            // if there is no target layer, or the target layer is no longer active,
            // or the target should change as hinted by others, we update the target layer
            if (targetLayer == null || !isLayerActive(frameResults) || targetShouldChange) {
                LOGGER.debug("Target-Old: " + targetLayer);
                updateTargetLayer();
                LOGGER.debug("Target: " + targetLayer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        int i = 0;
        for (; i < frameResults.size(); i++) {
            if (frameResults.get(i) > lastFrameTimestamp)
                break;
        }
        ArrayList<Double> results = new ArrayList<>();
        long preceding = lastFrameTimestamp;
        if (i < frameResults.size()) {
            lastFrameTimestamp = frameResults.get(frameResults.size() - 1);
            if (i != frameResults.size() - 1) {
                for (; i < frameResults.size(); i++) {
                    if (preceding == 0) {
                        preceding = frameResults.get(i);
                        continue;
                    }
                    Double frameTime = (Double.valueOf(frameResults.get(i)) - preceding) / 1e6;
                    preceding = frameResults.get(i);
                    results.add(frameTime);
                }
            }
        }

        double totalTime = results.stream().mapToDouble(a -> a).sum();
        double fps = 0;
        if (totalTime != 0)
            fps = results.size() / totalTime * 1000;
        if (fps < 1.)
            targetShouldChange = true;
        double finalFps = fps;
        LOGGER.debug(String.format("%d / %f = %f", results.size(), totalTime / 1000, fps));
        LOGGER.debug("-------------------");
        Platform.runLater(() -> device.getController().addDataToChart("FPS", new XYChart.Data<>(timer, finalFps)));
        super.update();
    }

    @Override
    void end() {
        if (updateLayerTask != null)
            updateLayerTask.cancel(true);
        super.end();
    }

    @Override
    void begin() {
        clearLatencyData();
        super.begin();
        updateLayerTask = executorService.scheduleAtFixedRate(this::updateLayers, 500, 500, TimeUnit.MILLISECONDS);
    }
}
