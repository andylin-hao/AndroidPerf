package com.android.androidperf;

import javafx.application.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import se.vidstige.jadb.JadbConnection;
import se.vidstige.jadb.JadbDevice;
import se.vidstige.jadb.JadbException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private ArrayList<Layer> layers = new ArrayList<>();
    private ArrayList<String> packageList = new ArrayList<>();
    private String targetPackage;
    private int targetLayer = -1;

    private boolean hasStartedPerf = false;

    private static final Pattern cpuModelPattern = Pattern.compile("model name\\s*:\\s*(.*)");
    private static final Pattern cpuModelPatternOld = Pattern.compile("Hardware\\s+:\\s+(.*)");
    private static final Pattern cpuFreqPattern = Pattern.compile("cpu MHz\\s*:\\s*(.*)");
    private static final Pattern layerNamePattern = Pattern.compile("[*+] .*Layer.*\\((.*)\\)");
    private static final Pattern bufferStatsPattern = Pattern.compile("activeBuffer=\\[(.*)x(.*):.*,.*]");
    private static final Pattern bufferStatsPatternR = Pattern.compile(".*slot=(.*)");
    private static final Pattern glInfoPattern = Pattern.compile("^GLES: (.*), (.*), (.*)$");

    Device(JadbDevice device, AppController appController) {
        jadbDevice = device;
        controller = appController;
        deviceADBID = jadbDevice.getSerial();

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

        // acquire CPU info
        info = execCmd("cat /proc/cpuinfo");
        String[] cpuInfo = info.split("\n\n");
        ArrayList<String> frequencies = new ArrayList<>();
        if (cpuInfo.length != 0) {
            String oneCoreInfo = cpuInfo[0];
            Matcher matcher = cpuModelPattern.matcher(oneCoreInfo);
            if (matcher.find()) {
                cpuCores = cpuInfo.length;
                cpuModel = matcher.group(1);
            } else {
                // On certain old-version kernel, /proc/cpuinfo shows hardware info in extra lines like
                // CPU param	: 295 416 416 606 944 295 437 437 605 1068
                // Hardware	: Qualcomm Technologies, Inc MSM8996pro
                // This was found on Pixel XL running Android 7.1.2
                matcher = cpuModelPatternOld.matcher(cpuInfo[cpuInfo.length - 1]);
                cpuCores = cpuInfo.length - 1;
                if (matcher.find()) {
                    cpuModel = matcher.group(1);
                }
                else {
                    cpuModel = "Unknown";
                    LOGGER.warn("Cannot get CPU info");
                }
            }

            matcher = cpuFreqPattern.matcher(info);

            while (matcher.find()) {
                String freq = matcher.group(1);
                if (!frequencies.contains(freq))
                    frequencies.add(freq);
            }
        } else {
            cpuCores = 0;
            cpuModel = "Unknown";
            LOGGER.warn("Cannot get CPU info");
        }
        cpuFrequencies = frequencies;
        info = execCmd("getprop ro.product.cpu.abilist");
        if (!info.isEmpty())
            abiList = info;
        else {
            abiList = "Unknown";
            LOGGER.warn("Cannot get ABI info");
        }
        props.add(new DeviceProp("CPU Model", cpuModel));
        props.add(new DeviceProp("CPU Cores", String.valueOf(cpuCores)));
        props.add(new DeviceProp("CPU Frequencies", String.join(", ", cpuFrequencies)));
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
        Matcher matcher = glInfoPattern.matcher(info);
        if (matcher.find()) {
            glVendor = matcher.group(1);
            glRenderer = matcher.group(2);
            glVersion = matcher.group(3);
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

    static ArrayList<Device> getDeviceList(AppController controller) {
        try {
            List<JadbDevice> adbDevices = connection.getDevices();
            ArrayList<Device> devices = new ArrayList<>();
            for (JadbDevice adbDevice : adbDevices) {
                devices.add(new Device(adbDevice, controller));
            }
            return devices;
        } catch (IOException | JadbException e) {
            LOGGER.error("Cannot get device list");
            return new ArrayList<>();
        }
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
        Platform.runLater(controller::updatePromptText);
    }

    void endPerf() {
        if (hasStartedPerf) {
            for (var service : services) {
                service.end();
            }
            hasStartedPerf = false;
        }
        Platform.runLater(controller::updatePromptText);
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

    private void updatePackageList() {
        ArrayList<String> packages = new ArrayList<>();
        Pattern pattern = Pattern.compile(" {6}android\\.intent\\.action\\.MAIN:\n( {8}.*\n)*");
        Pattern pattern1 = Pattern.compile(" {8}\\S+ (\\S+)/.+");
        String packageInfo = execCmd("dumpsys package r activity");
        Matcher matcher = pattern.matcher(packageInfo);
        if (matcher.find()) {
            String packageIntentMain = packageInfo.substring(matcher.start(), matcher.end());
            matcher = pattern1.matcher(packageIntentMain);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (!packages.contains(name))
                    packages.add(name);
            }
        }
        packageList = packages;
    }

    private synchronized void updateLayerList() {
        ArrayList<Layer> updatedLayerList = new ArrayList<>();
        String updateLayerCmd = "dumpsys SurfaceFlinger | grep -E '(\\+|\\*).*Layer.*|buffer:.*slot|activeBuffer'";
        String layerInfo = execCmd(updateLayerCmd);
        Matcher nameMatcher = layerNamePattern.matcher(layerInfo);
        Matcher bufferMatcher;
        // Below Android 10, we get:
        // + Layer 0x7f162ba23000 (StatusBar#0)
        //      format= 1, activeBuffer=[1440x  84:1440,  1], queued-frames=0, mRefreshPending=0
        if (sdkVersion <= 29)
            bufferMatcher = bufferStatsPattern.matcher(layerInfo);
            // Else, we get
            // * Layer 0x7615a5469f98 (SurfaceView - com.android.chrome/com.google.android.apps.chrome.Main#0)
            //      buffer: buffer=0x7615a547b140 slot=2
        else
            bufferMatcher = bufferStatsPatternR.matcher(layerInfo);

        int idx = 0;
        targetLayer = -1;
        while (nameMatcher.find() && bufferMatcher.find()) {
            String layerName = nameMatcher.group(1);
            if (layerName.contains(targetPackage)) {
                Layer layer;
                if (sdkVersion <= 29) {
                    int w = Integer.parseInt(bufferMatcher.group(1).strip());
                    int h = Integer.parseInt(bufferMatcher.group(2).strip());
                    layer = new Layer(layerName, w != 0 && h != 0);
                } else {
                    long bufferSlot = Long.parseLong(bufferMatcher.group(1).strip());
                    layer = new Layer(layerName, bufferSlot != -1);
                }
                updatedLayerList.add(layer);
                if (layer.hasBuffer && layer.isSurfaceView)
                    targetLayer = idx;
                else if (layer.hasBuffer && targetLayer == -1)
                    targetLayer = idx;
                idx++;
            }
        }
        layers = updatedLayerList;
        if (layers.size() > 0) {
            Platform.runLater(controller::updateLayerListBox);
        }
    }

    void checkLayerChanges() {
        String layerStr = layerListToString();
        String listInfo = execCmd(String.format("dumpsys SurfaceFlinger --list | grep %s", targetPackage));
        if (layerStr.length() == 0 || !listInfo.contains(layerStr)) {
            updateLayerList();
        }
    }

    String layerListToString() {
        StringBuilder layerStr = new StringBuilder();
        for (var layer : layers) {
            layerStr.append(layer.layerName).append("\n");
        }
        return layerStr.toString().strip();
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

    public void setTargetLayer(int layer, String name) {
        if (layer >= layers.size() || !layers.get(layer).layerName.equals(name)) {
            updateLayerList();
        } else
            targetLayer = layer;
    }

    public int getTargetLayer() {
        if (targetLayer == -1 || layers.size() == 0) {
            updateLayerList();
            return targetLayer;
        }
        return targetLayer;
    }

    public ArrayList<Layer> getLayers() {
        if (layers.size() == 0)
            updateLayerList();
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

    public ArrayList<String> getPackageList() {
        return packageList;
    }

    public ArrayList<DeviceProp> getProps() {
        return props;
    }
}
