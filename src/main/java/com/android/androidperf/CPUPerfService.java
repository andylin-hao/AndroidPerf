package com.android.androidperf;

import javafx.application.Platform;
import javafx.scene.chart.XYChart;
import javafx.util.Pair;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CPUPerfService extends BasePerfService {
    static private final Pattern totalCPUPattern = Pattern.compile(".* +([\\d.]+)");
    static private final Pattern totalCPUPatternOld = Pattern.compile("([\\d.]+)%");

    @SuppressWarnings("unchecked")
    @Override
    void dump() {
        super.dump();

        Pair<Double, Double> data;
        double procUsage = 0.;
        double totalUsage = 0.;

        int count = 0;
        while (!dataQueue.isEmpty()) {
            data = (Pair<Double, Double>) dataQueue.poll();
            procUsage += data.getKey();
            totalUsage += data.getValue();
            count++;
        }
        if (count == 0) {
            procUsage = 0;
            totalUsage = 0;
        } else {
            procUsage = procUsage / count;
            totalUsage = totalUsage / count;
        }
        double finalProcUsage = procUsage;
        double finalTotalUsage = totalUsage;

        Platform.runLater(() -> device.getController()
                .addDataToChart(
                        "CPU",
                        new XYChart.Data<>(dumpTimer, finalProcUsage / device.getCpuCores()),
                        new XYChart.Data<>(dumpTimer, finalTotalUsage / device.getCpuCores()))
        );
    }

    Pair<Double, Double> acquireCPUData() {
        String info = device.execCmd("top -o CMDLINE,%CPU -n 1 -q -b -k%CPU");
        String packageName = device.getTargetPackage();
        Matcher matcher;
        double procUsage = 0.;
        boolean oldTop = false;
        if (info.contains(packageName)) {
            Pattern procCPUPattern = Pattern.compile(String.format("%s.* +([\\d.]+)", packageName));
            matcher = procCPUPattern.matcher(info);
            while (matcher.find()) {
                procUsage += Double.parseDouble(matcher.group(1));
            }
        } else {
            oldTop = true;
            info = device.execCmd("top -n 1 -s cpu");
            Pattern procCPUPattern = Pattern.compile(String.format(".* +([\\d.]+)%% +.*%s.*", packageName));
            matcher = procCPUPattern.matcher(info);
            while (matcher.find()) {
                procUsage += Double.parseDouble(matcher.group(1));
            }
        }



        double totalUsage = 0.;
        if (!oldTop)
            matcher = totalCPUPattern.matcher(info);
        else
            matcher = totalCPUPatternOld.matcher(info);
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
