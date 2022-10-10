package com.android.androidperf;

import javafx.application.Platform;
import javafx.scene.chart.XYChart;
import javafx.util.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class NetworkPerfService extends BasePerfService {
    private static final Logger LOGGER = LogManager.getLogger(NetworkPerfService.class);
    private double lastRxBytes = 0;
    private double lastTxBytes = 0;

    static class NetStatsData {
        public long mRxBytes = 0;
        public long mRxPackets = 0;
        public long mTxBytes = 0;
        public long mTxPackets = 0;

        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(32);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putLong(0, mRxBytes);
            buffer.putLong(8, mRxPackets);
            buffer.putLong(16, mTxBytes);
            buffer.putLong(24, mTxPackets);
            return buffer.array();
        }
    }

    static long convertToLong(byte[] bytes, int index)
    {
        long value = 0L;

        try {
            // Iterating through for loop
            for (int i = index + 8 - 1; i >= index ; i--) {
                // Shifting previous value 8 bits to right and
                // add it with next value
                value = (value << 8) + (bytes[i] & 255);
            }
        } catch (Exception e) {
            LOGGER.error(e);
        }

        return value;
    }

    private NetStatsData fromBytes(byte[] bytes) {
        long[] data = new long[4];
        for (int i = 0; i < 4; i++) {
            data[i] = convertToLong(bytes, i * 8);
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
        LOGGER.debug(String.format("rx %d %d, tx %d %d", netStatsData.mRxBytes, netStatsData.mRxPackets, netStatsData.mTxBytes, netStatsData.mTxPackets));
        return new Pair<>((double)netStatsData.mRxBytes/1024., (double)netStatsData.mTxBytes/1024.);
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
