package com.android.androidperf;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import se.vidstige.jadb.JadbConnection;
import se.vidstige.jadb.JadbDevice;
import se.vidstige.jadb.JadbDevice.ForwardType;
import se.vidstige.jadb.JadbException;
import se.vidstige.jadb.RemoteFile;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
    private String lastLayerInfo = "";
    private String targetPackage;
    private int packageUid;

    private boolean hasStartedPerf = false;
    private int localPort = -1;

    private static final String SERVER_PATH_BASE = "/data/local/tmp";
    private static final String SERVER_EXECUTABLE = "AndroidPerfServer";
    private static final String MSG_END = "PERF_MSG_END\n";
    private static final String UNIX_SOCKET = "androidperf";
    private static final Pattern cpuModelPattern = Pattern.compile("model name\\s*:\\s*(.*)");
    private static final Pattern cpuCorePattern = Pattern.compile("cpu\\d+");
    private static final Pattern cpuFreqPattern = Pattern.compile("cpu MHz\\s*:\\s*(.*)");
    private static final Pattern layerNamePattern = Pattern.compile("[*+] .*Layer.*\\(.*\\).*");
    private static final Pattern bufferSizePattern = Pattern.compile("activeBuffer=\\[([ \\d]+)x([ \\d]+):.*");
    private static final Pattern bufferPosPattern = Pattern.compile("pos=\\(\\s*(-?\\d+),\\s*(-?\\d+)\\),");
    private static final Pattern bufferZOrderPattern = Pattern.compile("z=\\s*(-?\\d+),");
    private static final Pattern bufferParentPattern = Pattern.compile("parent=(\\S+)");
    private static final Pattern bufferZRelativeOrderPattern = Pattern.compile("zOrderRelativeOf=(\\S+)");

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
        props.add(new DeviceProp("Memory", String.format("%.1f", memSize) + " GB"));

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

    /**
     * Add a profiling service to the device
     *
     * @param serviceClass the class of the service
     */
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

    /**
     * Start all profiling services
     */
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

    /**
     * End all profiling services
     */
    void endPerf() {
        if (hasStartedPerf) {
            for (var service : services) {
                service.end();
            }
            hasStartedPerf = false;
        }
        Platform.runLater(controller::updateUIOnStateChanges);
    }

    /**
     * Completely shutdown all profiling threads, used upon app close
     */
    void shutdown() {
        endPerf();
        for (var service : services) {
            service.shutdown();
        }
    }

    /**
     * Get the profiling state
     *
     * @return true when profiling is running
     */
    boolean getPerfState() {
        return hasStartedPerf;
    }

    /**
     * Update the list of all installed packages
     */
    public void updatePackageList() {
        ArrayList<String> packages = new ArrayList<>();
        String mainIntent = "      android.intent.action.MAIN:";
        String intent = "      android.intent.action.";
        Pattern namePattern = Pattern.compile(" {8}\\S+ (\\S+)/.+");

        // show all packages with the MAIN intent, which indicates that the app can be opened from the launcher
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

    /**
     * Periodically update the currently running package, and moves it to the front of the package list in the UI
     */
    public void checkCurrentPackage() {
        String info = execCmd("dumpsys window | grep mCurrentFocus");
        String[] activityInfo = info.split(" ");
        String focusedWindow = activityInfo[activityInfo.length - 1];
        focusedWindow = focusedWindow.replace("}", "");
        String[] packageInfo = focusedWindow.split("/");
        String packageName = packageInfo[0];
        if (packageInfo.length == 2 && !packageList.get(0).equals(packageName)) {
            Platform.runLater(() -> {
                controller.movePackageToFront(packageName);
            });
        }
    }

    /**
     * Update the layer info of the currently selected package
     *
     * @return true if the layers have changed
     */
    public synchronized boolean updateLayerList() {
        ArrayList<Layer> updatedLayerList = new ArrayList<>();
        String layerListInfo = sendMSG("list");
        if (layerListInfo == null)
            layerListInfo = execCmd("dumpsys SurfaceFlinger --list");
        if (layerListInfo.equals(lastLayerInfo))
            return false;

        String[] layerListFull = layerListInfo.split("\n");
        LinkedBlockingDeque<String> layerList = Arrays.stream(layerListFull)
                .filter(str -> str.contains(targetPackage)).collect(Collectors.toCollection(LinkedBlockingDeque::new));
        if (layerList.isEmpty()) {
            boolean isChanged = !layers.isEmpty();
            layers.clear();
            return isChanged;
        }

        String info = execCmd("dumpsys SurfaceFlinger | grep -E '(\\+|\\*).*Layer.*|buffer:.*slot|activeBuffer|parent|z=|pos=|parent=|zOrderRelativeOf='");

        Pattern pattern;
        Matcher matcher;
        Matcher bufferMatcher;
        HashMap<String, Integer> idMap = new HashMap<>();

        while (!layerList.isEmpty()) {
            String layerName = layerList.poll();
            pattern = Pattern.compile(String.format("[*+] .*Layer.*\\((%s.*)\\).*", Pattern.quote(layerName)));
            matcher = pattern.matcher(info);
            while (matcher.find()) {
                int end = matcher.end();
                int start = matcher.start();
                String bufferInfo = info.substring(end);
                Matcher layerMatcher = layerNamePattern.matcher(bufferInfo);
                if (layerMatcher.find())
                    bufferInfo = bufferInfo.substring(0, layerMatcher.start());
                Layer layer;
                layerList.addAll(findChildrenLayers(layerName, info, layerListFull).stream().filter(i -> !layerList.contains(i)).collect(Collectors.toList()));

                // * Layer 0x7615a5469f98 (SurfaceView - com.android.chrome/com.google.android.apps.chrome.Main#0)
                //      buffer: buffer=0x7615a547b140 slot=2
                bufferMatcher = bufferStatsPatternR.matcher(bufferInfo);
                if (bufferMatcher.find()) {
                    continue;
                }

                // + Layer 0x7f162ba23000 (StatusBar#0)
                //      format= 1, activeBuffer=[1440x  84:1440,  1], queued-frames=0, mRefreshPending=0
                bufferMatcher = bufferSizePattern.matcher(bufferInfo);
                if (bufferMatcher.find()) {
                    int w = Integer.parseInt(bufferMatcher.group(1).strip());
                    int h = Integer.parseInt(bufferMatcher.group(2).strip());
                    Matcher zMatcher = bufferZOrderPattern.matcher(bufferInfo);
                    Matcher posMatcher = bufferPosPattern.matcher(bufferInfo);
                    int x = -1;
                    int y = -1;
                    int z = -1;
                    try {
                        if (posMatcher.find()) {
                            x = Integer.parseInt(posMatcher.group(1).strip());
                            y = Integer.parseInt(posMatcher.group(1).strip());
                        }
                        if (zMatcher.find())
                            z = Integer.parseInt(zMatcher.group(1).strip());
                    } catch (NumberFormatException e) {
                        LOGGER.error(bufferInfo + e);
                    }

                    int id = idMap.getOrDefault(layerName, 0);
                    layer = new Layer(layerName, targetPackage, true, id, w, h, x, y, z);
                    idMap.put(layerName, id + 1);

                    info = info.replace(info.substring(start, end + bufferInfo.length()), "");
                    updatedLayerList.add(layer);
                    break;
                }
            }
        }

        // check whether a layer is overlapped by other layers and thus invisible to users
        updatedLayerList.sort(Comparator.comparingInt(o -> o.z));
        for (int i = 0; i < updatedLayerList.size() - 1; i++) {
            Layer layerToCheck = updatedLayerList.get(i);
            if (!layerToCheck.isVisible)
                continue;
            for (int j = i + 1; j < updatedLayerList.size(); j++) {
                Layer layer = updatedLayerList.get(j);
                if (!layer.isVisible)
                    continue;
                if (layerToCheck.isCoveredBy(layer))
                    layerToCheck.isVisible = false;
            }
        }

        lastLayerInfo = layerListInfo;
        if (layers.equals(updatedLayerList)) {
            return false;
        } else {
            layers.clear();
            layers.addAll(updatedLayerList);
            return true;
        }
    }

    /**
     * Reconstruct the layer dependency from the layer info
     * and extract all the children layers for a parent layer,
     * so that we can extract overlay layers
     *
     * @param parent        the parent layer
     * @param info          the layer info
     * @param layerListFull the full layer list
     * @return the children layer
     */
    private ArrayList<String> findChildrenLayers(String parent, String info, String[] layerListFull) {
        Pattern parentPattern = Pattern.compile(String.format("parent=(%s)", Pattern.quote(parent)));
        Matcher matcher = parentPattern.matcher(info);
        ArrayList<String> children = new ArrayList<>();
        while (matcher.find()) {
            String layerInfo = info.substring(0, matcher.start());
            Matcher layerMatcher;
            layerMatcher = layerNamePattern.matcher(layerInfo);
            List<String> matches = layerMatcher.results().map((r) -> layerInfo.substring(r.start(), r.end())).collect(Collectors.toList());
            String name = matches.get(matches.size() - 1);
            if (name != null) {
                Pattern pattern;
                Matcher nameMatcher;
                for (var layerName : layerListFull) {
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

    /**
     * Execute ADB command
     *
     * @param cmd  command
     * @param args command arguments
     * @return execution results
     */
    public String execCmd(String cmd, String... args) {
        try (InputStream stream = jadbDevice.execute(cmd, args)) {
            return new String(stream.readAllBytes()).strip();
        } catch (IOException | JadbException e) {
            LOGGER.warn(String.format("Error executing adb cmd: %s", cmd + String.join(" ", args)));
            return "Error executing adb cmd";
        }
    }

    /**
     * Setup port forwarding to unix domain socket
     *
     * @return true if success
     */
    private boolean setupForward() {
        // forward port to unix abstract socket
        localPort = findFreePort();
        if (localPort < 0) {
            LOGGER.error("Failed to find available ports");
            return false;
        }
        try {
            jadbDevice.clearForward();
            jadbDevice.forward(ForwardType.TCP, String.valueOf(localPort), ForwardType.LOCAL, UNIX_SOCKET);
        } catch (IOException | JadbException e) {
            LOGGER.error("Failed to forward local port", e);
            return false;
        }
        return true;
    }

    /**
     * Push the server executable to device, grant permissions and start the server
     */
    public boolean startServer() {
        if (!setupForward())
            return false;
        if (!isServerRunning()) {

            // create tmp directory in device
            if (execCmd("mkdir " + SERVER_PATH_BASE).contains("Error")) {
                LOGGER.error("Failed to create directory");
                return false;
            }

            // push proper executable to tmp
            try {
                String abi = "armeabi-v7a";
                abi = abiList.contains("x86_64") ? "x86_64" :
                        (abiList.contains("x86") ? "x86" :
                                (abiList.contains("arm64-v8a") ? "arm64-v8a" : abi));
                jadbDevice.push(new File(String.format("android/%s/%s", abi, SERVER_EXECUTABLE)), new RemoteFile(String.format("%s/%s", SERVER_PATH_BASE, SERVER_EXECUTABLE)));
            } catch (IOException | JadbException e) {
                LOGGER.error("Failed to push server to device", e);
                return false;
            }

            // grant permissions
            String reply = execCmd(String.format("chmod 777 %s/%s", SERVER_PATH_BASE, SERVER_EXECUTABLE));
            if (reply.contains("Error")) {
                LOGGER.error("Failed to chmod");
                return false;
            }

            long start = System.currentTimeMillis();
            while (!isServerRunning()) {
                // start the server
                reply = execCmd(String.format("%s/%s&echo 'SUCCESS'", SERVER_PATH_BASE, SERVER_EXECUTABLE));
                if (!reply.contains("SUCCESS"))
                    return false;
                long timeout = System.currentTimeMillis() - start;
                if (timeout > 10000) {
                    return false;
                }
            }

            // PING server to test aliveness
            reply = sendMSG("PING");
            if (reply == null || !reply.contains("OKAY")) {
                LOGGER.error("Failed to PING server");
                return false;
            }
        }
        return true;
    }

    private void restartServer() {
        String processInfo = execCmd("pidof " + SERVER_EXECUTABLE);
        if (processInfo != null && !processInfo.isEmpty()) {
            execCmd("kill -9 " + processInfo);
        }
        startServer();
    }

    private boolean isServerRunning() {
        String processInfo = execCmd("pidof " + SERVER_EXECUTABLE);
        if (processInfo == null || processInfo.isEmpty()) {
            processInfo = execCmd("ps -A | grep " + SERVER_EXECUTABLE);
            return !processInfo.isEmpty();
        } else
            return true;
    }

    /**
     * Find an available port on the PC
     *
     * @return port number
     */
    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            int port = socket.getLocalPort();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return port;
        } catch (IOException ignored) {
        }
        return -1;
    }

    /**
     * Send data to the server and acquire reply
     *
     * @param data data to be sent
     * @return reply message
     */
    public String sendMSG(String data) {
        try {
            if (localPort < 0 && !setupForward())
                return null;
            Socket localSocket = new Socket(InetAddress.getLoopbackAddress(), localPort);
            DataOutputStream outputStream = new DataOutputStream(localSocket.getOutputStream());
            outputStream.write(data.getBytes());
            outputStream.flush();

            DataInputStream inputStream = new DataInputStream(localSocket.getInputStream());
            byte[] buffer = new byte[1024];
            int len;
            StringBuilder reply = new StringBuilder();
            int msgEnd;
            while (true) {
                len = inputStream.read(buffer);
                if (len > 0) {
                    reply.append(new String(buffer, 0, len));
                }
                msgEnd = reply.indexOf(MSG_END);
                if (msgEnd != -1) {
                    localSocket.close();
                    String replyStr = reply.substring(0, msgEnd);
                    if (replyStr == null || replyStr.isEmpty())
                        restartServer();
                    return replyStr;
                }
                if (len == -1) {
                    restartServer();
                    localSocket.close();
                    return null;
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to send data to server, restarting...", e);
            restartServer();
            return null;
        }
    }

    /**
     * Set the package to be profiled
     *
     * @param packageName the package's name
     */
    public void setTargetPackage(String packageName) {
        endPerf();
        targetPackage = packageName;
        String uidInfo = execCmd(String.format("dumpsys package %s| grep userId", packageName));
        uidInfo = uidInfo.split("\\n")[0].strip();
        if (uidInfo.contains("userId=")) {
            try {
                packageUid = Integer.parseInt(uidInfo.split("=")[1]);
            } catch (NumberFormatException e) {
                LOGGER.error(e);
            }
        }
        updateLayerList();
    }

    public String getTargetPackage() {
        return targetPackage;
    }

    public int getPackageUid() { return  packageUid; }

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
