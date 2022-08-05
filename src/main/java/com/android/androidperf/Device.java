package com.android.androidperf;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import se.vidstige.jadb.JadbConnection;
import se.vidstige.jadb.JadbDevice;
import se.vidstige.jadb.JadbException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Device {
    private static final Logger LOGGER = LogManager.getLogger(Device.class);
    static JadbConnection connection = new JadbConnection();
    private final JadbDevice jadbDevice;
    private final AppController controller;

    private final String deviceADBID;
    private final String deviceName;
    private final int sdkVersion;
    private final String androidVersion;
    private final String abiList;
    private final String glVendor;
    private final String glRenderer;
    private final String glVersion;
    private final int cpuCores;
    private final String cpuModel;
    private final ArrayList<String> cpuFrequencies;
    private final double memSize;
    private final double storageSize;
    private final ArrayList<DeviceProp> props = new ArrayList<>();

    private final ArrayList<BasePerfService> services = new ArrayList<>();
    private final ArrayList<Layer> layers = new ArrayList<>();
    private final ObservableList<String> packageList = FXCollections.observableArrayList();
    private String targetPackage;

    private boolean hasStartedPerf = false;

    private static final Pattern cpuModelPattern = Pattern.compile("model name\\s*:\\s*(.*)");
    private static final Pattern cpuCorePattern = Pattern.compile("cpu\\d+");
    private static final Pattern cpuFreqPattern = Pattern.compile("cpu MHz\\s*:\\s*(.*)");
    private static final Pattern layerNamePattern = Pattern.compile("[*+] .*Layer \\(.*\\).*");
    private static final Pattern layerNamePatternN = Pattern.compile("[*+] .*Layer.*\\(.*\\).*");
    private static final Pattern bufferStatsPattern = Pattern.compile("activeBuffer=\\[([ \\d]+)x([ \\d]+):.*");
    private static final Pattern bufferStatsPatternR = Pattern.compile(".*slot=(\\S*)");
    Device(JadbDevice device, AppController appController) {
        jadbDevice = device;
        controller = appController;
        deviceADBID = jadbDevice.getSerial();

        // register perf services
        registerService(FPSPerfService.class);
        registerService(CPUPerfService.class);
        registerService(NetworkPerfService.class);

        // acquire device name
        String info = execCmd("getprop ro.product.model");
        if (!info.isEmpty())
            deviceName = info;
        else
            deviceName = deviceADBID;
        props.add(new DeviceProp("Name", deviceName));

        // acquire SDK/Android versions
        String versionStr = execCmd("getprop ro.build.version.sdk");
        int version;
        try {
            version = Integer.parseInt(versionStr);
        } catch (NumberFormatException e) {
            version = 0;
            LOGGER.error(String.format("Cannot get SDK version, getprop is %s", versionStr));
        }
        sdkVersion = version;
        props.add(new DeviceProp("SDK Version", String.valueOf(sdkVersion)));

        androidVersion = execCmd("getprop ro.build.version.release");
        props.add(new DeviceProp("Android Version", String.valueOf(androidVersion)));

        // acquire CPU model info
        info = execCmd("cat /proc/cpuinfo");
        String oneCoreInfo = info.split("\n\n")[0];
        Matcher matcher = cpuModelPattern.matcher(oneCoreInfo);
        if (matcher.find()) {
            cpuModel = matcher.group(1);
        } else {
            String boardName = execCmd("getprop ro.board.platform");
            if (boardName.length() != 0)
                cpuModel = boardName;
            else {
                cpuModel = "Unknown";
                LOGGER.warn("Cannot get CPU model info");
            }
        }
        props.add(new DeviceProp("CPU Model", cpuModel));

        // acquire CPU core info
        info = execCmd("ls /sys/devices/system/cpu");
        matcher = cpuCorePattern.matcher(info);
        int num = 0;
        while (matcher.find()) {
            num++;
        }
        cpuCores = num;
        props.add(new DeviceProp("CPU Cores", String.valueOf(cpuCores)));

        // acquire CPU frequency info
        ArrayList<String> frequencies = new ArrayList<>();
        for (int i = 0; i < cpuCores; i++) {
            String minFreqInfo = execCmd(String.format("cat /sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_min_freq", i));
            String maxFreqInfo = execCmd(String.format("cat /sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i));
            try {
                String freq = String.format("%d MHz-%d MHz", Integer.parseInt(minFreqInfo) / 1000, Integer.parseInt(maxFreqInfo) / 1000);
                if (!frequencies.contains(freq))
                    frequencies.add(freq);
            } catch (NumberFormatException ignored) {
            }
        }
        if (frequencies.size() == 0) {
            info = execCmd("cat /proc/cpuinfo");
            matcher = cpuFreqPattern.matcher(info);

            while (matcher.find()) {
                String freq = matcher.group(1) + " MHz";
                if (!frequencies.contains(freq))
                    frequencies.add(freq);
            }
        }

        cpuFrequencies = frequencies;
        props.add(new DeviceProp("CPU Frequencies", String.join(", ", cpuFrequencies)));

        // acquire CPU ABI info
        info = execCmd("getprop ro.product.cpu.abilist");
        if (!info.isEmpty())
            abiList = info;
        else {
            abiList = "Unknown";
            LOGGER.warn("Cannot get ABI info");
        }
        props.add(new DeviceProp("ABI List", abiList));

        // acquire memory info
        info = execCmd("cat /proc/meminfo | grep MemTotal");
        String[] memInfo = info.split(" +");
        if (memInfo.length == 3) {
            memSize = Integer.parseInt(memInfo[1]) / 1024. / 1024.;
        } else {
            memSize = 0;
            LOGGER.warn("Cannot get memory info");
        }
        props.add(new DeviceProp("Memory", Math.round(memSize) + " GB"));

        // acquire storage info
        info = execCmd("df | grep /storage/emulated");
        String[] storageInfo = info.split(" +");
        if (storageInfo.length == 6) {
            storageSize = Double.parseDouble(storageInfo[1]) / 1024. / 1024.;
        } else {
            storageSize = 0;
            LOGGER.warn("Cannot get storage info");
        }
        props.add(new DeviceProp("Storage", Math.round(storageSize) + " GB"));

        // acquire GPU info
        info = execCmd("dumpsys SurfaceFlinger | grep OpenGL");
        String[] glInfo = info.split(", ");
        if (glInfo.length >= 3 && glInfo[0].contains("GLES")) {
            glVendor = glInfo[0].replace("GLES: ", "");
            glRenderer = glInfo[1];
            glVersion = String.join(", ", Arrays.copyOfRange(glInfo, 2, glInfo.length));
        } else {
            glVendor = "Unknown";
            glRenderer = "Unknown";
            glVersion = "Unknown";
            LOGGER.warn("Cannot get GPU info");
        }
        props.add(new DeviceProp("GL Vendor", glVendor));
        props.add(new DeviceProp("GL Renderer", glRenderer));
        props.add(new DeviceProp("GL Version", glVersion));

        updatePackageList();
    }

    void registerService(Class<?> serviceClass) {
        BasePerfService service;
        try {
            service = (BasePerfService) serviceClass.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                 NoSuchMethodException e) {
            LOGGER.error("Cannot register service", e);
            return;
        }
        service.registerDevice(this);
        services.add(service);
    }

    void startPerf() {
        if (hasStartedPerf) {
            endPerf();
        }

        for (var service : services) {
            service.begin();
        }
        hasStartedPerf = true;
        Platform.runLater(controller::updateUIOnStateChanges);
    }

    void endPerf() {
        if (hasStartedPerf) {
            for (var service : services) {
                service.end();
            }
            hasStartedPerf = false;
        }
        Platform.runLater(controller::updateUIOnStateChanges);
    }

    void shutdown() {
        endPerf();
        for (var service : services) {
            service.shutdown();
        }
    }

    boolean getPerfState() {
        return hasStartedPerf;
    }

    public void updatePackageList() {
        ArrayList<String> packages = new ArrayList<>();
        String mainIntent = "      android.intent.action.MAIN:";
        String intent = "      android.intent.action.";
        Pattern namePattern = Pattern.compile(" {8}\\S+ (\\S+)/.+");

        String packageInfo = execCmd("dumpsys package r activity");
        String processInfo = execCmd("ps -A");

        int start = packageInfo.indexOf(mainIntent);
        if (start != -1) {
            int end = packageInfo.indexOf(intent, start + mainIntent.length());
            if (end != -1) {
                String packageIntentMain = packageInfo.substring(start + mainIntent.length() + 1, end).strip();
                Matcher matcher = namePattern.matcher(packageIntentMain);
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if (!packages.contains(name)) {
                        if (processInfo.contains(name))
                            packages.add(0, name);
                        else
                            packages.add(name);
                    }
                }
            }
        }
        Platform.runLater(() -> packageList.setAll(packages));
    }

    public boolean checkCurrentPackage() {
        String info = execCmd("dumpsys window | grep mCurrentFocus");
        String[] activityInfo = info.split(" ");
        String focusedWindow = activityInfo[activityInfo.length - 1];
        focusedWindow = focusedWindow.replace("}", "");
        String[] packageInfo = focusedWindow.split("/");
        String packageName = packageInfo[0];
        if (packageInfo.length == 2 && !packageList.get(0).equals(packageName)) {
            Platform.runLater(() -> {
                int index = packageList.indexOf(packageName);
                if (index != -1) {
                    Collections.swap(packageList, index, 0);
                }
            });
            return true;
        }
        return false;
    }

    public synchronized void updateLayerList() {
        ArrayList<Layer> updatedLayerList = new ArrayList<>();
        String layerListInfo = execCmd("dumpsys SurfaceFlinger --list");
        String[] layerListFull = layerListInfo.split("\n");
        LinkedBlockingDeque<String> layerList = Arrays.stream(layerListFull)
                .filter(str -> str.contains(targetPackage)).collect(Collectors.toCollection(LinkedBlockingDeque::new));
        if (layerList.isEmpty()) {
            layers.clear();
            return;
        }

        String info = execCmd("dumpsys SurfaceFlinger | grep -E '(\\+|\\*).*Layer.*|buffer:.*slot|activeBuffer|parent'");

        Pattern pattern;
        Matcher matcher;
        Matcher bufferMatcher;
        HashMap<String, Integer> idMap = new HashMap<>();

        while (!layerList.isEmpty()) {
            String layerName = layerList.poll();
            pattern = Pattern.compile(String.format("[*+] .*Layer.*\\((%s.*)\\).*", Pattern.quote(layerName)));
            matcher = pattern.matcher(info);
            if (matcher.find()) {
                int end = matcher.end();
                int start = matcher.start();
                String bufferInfo = info.substring(end);
                if (sdkVersion == 24 || sdkVersion == 25)
                    matcher = layerNamePatternN.matcher(bufferInfo);
                else
                    matcher = layerNamePattern.matcher(bufferInfo);
                if (matcher.find())
                    bufferInfo = bufferInfo.substring(0, matcher.start());
                Layer layer = null;
                layerList.addAll(findChildrenLayers(layerName, info, layerListFull).stream().filter(i -> !layerList.contains(i)).collect(Collectors.toList()));

                // * Layer 0x7615a5469f98 (SurfaceView - com.android.chrome/com.google.android.apps.chrome.Main#0)
                //      buffer: buffer=0x7615a547b140 slot=2
                bufferMatcher = bufferStatsPatternR.matcher(bufferInfo);
                if (bufferMatcher.find()) {
                    long bufferSlot = Long.parseLong(bufferMatcher.group(1).strip());
                    int id = idMap.getOrDefault(layerName, 0);
                    layer = new Layer(layerName, bufferSlot != -1, id);
                    idMap.put(layerName, id + 1);
                    info = info.replace(info.substring(start, end + bufferInfo.length()), "");
                } else {
                    // + Layer 0x7f162ba23000 (StatusBar#0)
                    //      format= 1, activeBuffer=[1440x  84:1440,  1], queued-frames=0, mRefreshPending=0
                    bufferMatcher = bufferStatsPattern.matcher(bufferInfo);
                    if (bufferMatcher.find()) {
                        int w = Integer.parseInt(bufferMatcher.group(1).strip());
                        int h = Integer.parseInt(bufferMatcher.group(2).strip());
                        int id = idMap.getOrDefault(layerName, 0);
                        layer = new Layer(layerName, w != 0 && h != 0, id);
                        idMap.put(layerName, id + 1);
                        info = info.replace(info.substring(start, end + bufferInfo.length()), "");
                    }
                }
                if (layer != null) {
                    if (layer.hasBuffer) {
                        updatedLayerList.add(layer);
                    }
                }
            } else
                LOGGER.error(String.format("Cannot find %s in dumpsys info: %s", layerName, info));
        }

        layers.clear();
        layers.addAll(updatedLayerList);
    }

    private ArrayList<String> findChildrenLayers(String parent, String info, String[] layerListFull) {
        Pattern parentPattern = Pattern.compile(String.format("parent=(%s)", Pattern.quote(parent)));
        Matcher matcher = parentPattern.matcher(info);
        ArrayList<String> children = new ArrayList<>();
        while (matcher.find()) {
            String layerInfo = info.substring(0, matcher.start());
            Matcher layerMatcher;
            if (sdkVersion == 24 || sdkVersion == 25)
                layerMatcher = layerNamePatternN.matcher(layerInfo);
            else
                layerMatcher = layerNamePattern.matcher(layerInfo);
            List<String> matches = layerMatcher.results().map((r)-> layerInfo.substring(r.start(), r.end())).collect(Collectors.toList());
            String name = matches.get(matches.size() - 1);
            if (name != null) {
                Pattern pattern;
                Matcher nameMatcher;
                for (var layerName: layerListFull) {
                    pattern = Pattern.compile(String.format("[*+] .*Layer.*\\((%s.*)\\).*", Pattern.quote(layerName)));
                    nameMatcher = pattern.matcher(name);
                    if (nameMatcher.find()) {
                        children.add(layerName);
                        break;
                    }
                }
            }
        }
        return children;
    }

    String execCmd(String cmd, String... args) {
        try (InputStream stream = jadbDevice.execute(cmd, args)) {
            return new String(stream.readAllBytes()).strip();
        } catch (IOException | JadbException e) {
            LOGGER.warn(String.format("Error executing adb cmd: %s", cmd + String.join(" ", args)));
            return "Error executing adb cmd";
        }
    }

    void setTargetPackage(String packageName) {
        endPerf();
        targetPackage = packageName;
        updateLayerList();
    }

    public String getTargetPackage() {
        return targetPackage;
    }

    public ArrayList<Layer> getLayers() {
        return layers;
    }

    public AppController getController() {
        return controller;
    }

    public int getSdkVersion() {
        return sdkVersion;
    }

    public String getDeviceADBID() {
        return deviceADBID;
    }

    public String getAndroidVersion() {
        return androidVersion;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getAbiList() {
        return abiList;
    }

    public String getGlVendor() {
        return glVendor;
    }

    public String getGlRenderer() {
        return glRenderer;
    }

    public String getGlVersion() {
        return glVersion;
    }

    public int getCpuCores() {
        return cpuCores;
    }

    public String getCpuModel() {
        return cpuModel;
    }

    public double getMemSize() {
        return memSize;
    }

    public double getStorageSize() {
        return storageSize;
    }

    public ArrayList<String> getCpuFrequencies() {
        return cpuFrequencies;
    }

    public ObservableList<String> getPackageList() {
        return packageList;
    }

    public ArrayList<DeviceProp> getProps() {
        return props;
    }
}
