package com.android.androidperf;

import javafx.application.Platform;
import javafx.scene.chart.XYChart;
import javafx.util.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;

public class NetworkPerfService extends BasePerfService {
    private static final Logger LOGGER = LogManager.getLogger(NetworkPerfService.class);
    private double lastRxBytes = 0;
    private double lastTxBytes = 0;

    static class NetStatsData {
        public long mRxBytes = 0;
        public long mRxPackets = 0;
        public long mTxBytes = 0;
        public long mTxPackets = 0;
    }

    private NetStatsData fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        long[] data = new long[4];
        try {
            for (int i = 0; i < 4; i++) {
                buffer.put(bytes, i * 8, 8);
                data[i] = buffer.getLong();
            }
        } catch (Exception ignored) {

        }
        NetStatsData netStatsData = new NetStatsData();
        netStatsData.mRxBytes = data[0];
        netStatsData.mRxPackets = data[1];
        netStatsData.mTxBytes = data[2];
        netStatsData.mTxPackets = data[3];
        return netStatsData;
    }

    Pair<Double, Double> acquireNetworkData() {
        byte[] byteData = device.sendMSG(String.format("network %d", device.getTargetPackageUid()));
        NetStatsData netStatsData = fromBytes(byteData);
        return new Pair<>((double)netStatsData.mRxBytes, (double)netStatsData.mTxBytes);
    }

    @Override
    void update() {
        Pair<Double, Double> data = acquireNetworkData();
        double rxBytes = 0.;
        double txBytes = 0.;

        if (lastRxBytes != 0 && lastTxBytes != 0) {
            rxBytes += data.getKey() - lastRxBytes;
            txBytes += data.getValue() - lastTxBytes;
        }
        lastRxBytes = data.getKey();
        lastTxBytes = data.getValue();

        double finalRxBytes = rxBytes;
        double finalTxBytes = txBytes;
        Platform.runLater(() -> device.getController()
                .addDataToChart(
                        "Network",
                        new XYChart.Data<>(timer, finalRxBytes),
                        new XYChart.Data<>(timer, finalTxBytes))
        );

        super.update();
    }
}
