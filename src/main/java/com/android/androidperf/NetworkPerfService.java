package com.android.androidperf;

import javafx.application.Platform;
import javafx.scene.chart.XYChart;
import javafx.util.Pair;

public class NetworkPerfService extends BasePerfService {
    private double lastRxBytes = 0;
    private double lastTxBytes = 0;

    @SuppressWarnings("unchecked")
    @Override
    void dump() {
        super.dump();

        Pair<Double, Double> data;
        double rxBytes = 0.;
        double txBytes = 0.;

        while (!dataQueue.isEmpty()) {
            data = (Pair<Double, Double>) dataQueue.poll();
            if (lastRxBytes != 0 && lastTxBytes != 0) {
                rxBytes += data.getKey() - lastRxBytes;
                txBytes += data.getValue() - lastTxBytes;
            }
            lastRxBytes = data.getKey();
            lastTxBytes = data.getValue();
        }

        double finalRxBytes = rxBytes;
        double finalTxBytes = txBytes;
        Platform.runLater(() -> device.getController()
                .addDataToChart(
                        "Network",
                        new XYChart.Data<>(dumpTimer, finalRxBytes),
                        new XYChart.Data<>(dumpTimer, finalTxBytes))
        );
    }

    Pair<Double, Double> acquireNetworkData() {
        String info = device.execCmd("cat /proc/net/dev | grep -E 'wlan|radio'");
        String[] networkInfo = info.split("\n");
        double rxBytes = 0;
        double txBytes = 0;
        for (var interfaceInfo: networkInfo) {
            String[] data = interfaceInfo.strip().split("\\s+");
            if (data.length < 10)
                break;
            rxBytes += Long.parseLong(data[1]) / 1024.;
            txBytes += Long.parseLong(data[9]) / 1024.;
        }
        return new Pair<>(rxBytes, txBytes);
    }

    @Override
    void update() {
        dataQueue.add(acquireNetworkData());
    }
}
