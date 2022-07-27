package com.android.androidperf;

import javafx.application.Platform;
import javafx.scene.chart.XYChart;
import javafx.util.Pair;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CPUPerfService extends BasePerfService {
    static private final Pattern totalCPUPattern = Pattern.compile(".* +(\\d+.\\d+)");

    @SuppressWarnings("unchecked")
    @Override
    void dump() {
        super.dump();

        Pair<Double, Double> data = null;
        double procUsage = 0.;
        double totalUsage = 0.;

        int count = 0;
        while (!dataQueue.isEmpty()) {
            data = (Pair<Double, Double>) dataQueue.poll();
            procUsage += data.getKey();
            totalUsage += data.getValue();
            count++;
        }
        double finalProcUsage = procUsage / count;
        double finalTotalUsage = totalUsage / count;

        Platform.runLater(() -> device.getController()
                .addDataToChart(
                        "CPU",
                        new XYChart.Data<>(dumpTimer, finalProcUsage / device.getCpuCores()),
                        new XYChart.Data<>(dumpTimer, finalTotalUsage / device.getCpuCores()))
        );
    }

    Pair<Double, Double> acquireCPUData() {
        String info = device.execCmd("top -o CMDLINE,%CPU -n 1 -q -b -k%CPU");
        Pattern procCPUPattern = Pattern.compile(String.format("%s.* +(\\d+.\\d+)", device.getTargetPackage()));
        Matcher matcher = procCPUPattern.matcher(info);
        double procUsage = 0.;
        while (matcher.find()) {
            procUsage += Double.parseDouble(matcher.group(1));
        }

        double totalUsage = 0.;
        matcher = totalCPUPattern.matcher(info);
        while (matcher.find()) {
            double usage = Double.parseDouble(matcher.group(1));
            if (usage == 0)
                break;
            totalUsage += usage;
        }

        return new Pair<>(procUsage, totalUsage);
    }

    @Override
    void update() {
        dataQueue.add(acquireCPUData());
    }
}
