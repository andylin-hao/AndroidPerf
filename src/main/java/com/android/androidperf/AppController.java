package com.android.androidperf;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Side;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import se.vidstige.jadb.JadbDevice;
import se.vidstige.jadb.JadbException;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppController implements Initializable {
    private static final Logger LOGGER = LogManager.getLogger(AppController.class);
    @FXML
    private ComboBox<String> deviceListBox;
    @FXML
    private ComboBox<String> packageListBox;
    @FXML
    private ComboBox<String> layerListBox;
    @FXML
    private TableView<DeviceProp> propTable;
    @FXML
    private Button perfBtn;
    @FXML
    private Button updateBtn;
    @FXML
    private LineChart<Number, Number> lineChartFPS;
    @FXML
    private LineChart<Number, Number> lineChartCPU;
    @FXML
    private LineChart<Number, Number> lineChartNetwork;
    private final HashMap<String, LineChart<Number, Number>> lineChartMap = new HashMap<>();

    public Device selectedDevice;
    private final HashMap<String, Device> deviceMap = new HashMap<>();
    private final Pattern layerPattern = Pattern.compile("Layer#(\\d*):(.*)");

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        // initialize the device list
        updateDeviceList();

        // autocompletion for package list
        new AutoCompleteComboBoxListener<>(packageListBox);

        // initialize property table
        TableColumn<DeviceProp, String> nameCol = new TableColumn<>("Property");
        TableColumn<DeviceProp, String> valCol = new TableColumn<>("Value");
        nameCol.setCellValueFactory(cellData -> cellData.getValue().getPropName());
        valCol.setCellValueFactory(cellData -> cellData.getValue().getPropVal());
        valCol.setCellFactory(col -> {
            TableCell<DeviceProp, String> cell = new TableCell<>();
            Text text = new Text();
            cell.setGraphic(text);
            text.wrappingWidthProperty().bind(cell.widthProperty());
            text.textProperty().bind(cell.itemProperty());
            return cell;
        });
        propTable.getColumns().add(nameCol);
        propTable.getColumns().add(valCol);
        nameCol.prefWidthProperty().bind(propTable.widthProperty().multiply(0.38));
        valCol.prefWidthProperty().bind(propTable.widthProperty().multiply(0.62));

        // initialize line charts
        initLineChart(lineChartFPS, "FPS", new String[]{"FPS"}, 60, 10, "FPS");
        initLineChart(lineChartCPU, "CPU", new String[]{"App", "Total"}, 100, 20, "%");
        initLineChart(lineChartNetwork, "Network", new String[]{"Recv", "Send"}, 1000, 100, "KB/s");

        // set layer list comboBox's event handler
        layerListBox.getSelectionModel().selectedItemProperty().addListener(
                (options, oldValue, newValue) -> handleLayerListBox());

        // UI update
        updateUIOnStateChanges();
        packageListBox.setDisable(true);
        layerListBox.setDisable(true);
    }

    private void updateDeviceList() {
        try {
            List<JadbDevice> adbDevices = Device.connection.getDevices();
            ArrayList<String> ids = new ArrayList<>();
            ArrayList<String> obsoletes = new ArrayList<>();
            for (JadbDevice adbDevice : adbDevices) {
                ids.add(adbDevice.getSerial());
                if (deviceMap.get(adbDevice.getSerial()) == null) {
                    Device device = new Device(adbDevice, this);
                    deviceListBox.getItems().add(device.getDeviceADBID());
                    deviceMap.put(device.getDeviceADBID(), device);
                }
            }
            deviceListBox.getItems().forEach(str -> {
                if (!ids.contains(str)) obsoletes.add(str);
            });
            obsoletes.forEach(str -> {
                deviceListBox.getItems().remove(str);
                Device device = deviceMap.get(str);
                if (device != null) {
                    device.shutdown();
                    deviceMap.remove(str);
                }
            });
        } catch (IOException | JadbException e) {
            LOGGER.error("Cannot get device list");
        }
    }

    private void initLineChart(LineChart<Number, Number> lineChart, String chartName, String[] series, int yBound, int yTick, String yLabel) {
        for (String s : series) {
            XYChart.Series<Number, Number> data = new XYChart.Series<>();
            data.setName(s);
            lineChart.getData().add(data);
        }

        lineChart.setAnimated(true);
        lineChart.setTitle(chartName);

        NumberAxis xAxis = (NumberAxis) lineChart.getXAxis();
        xAxis.setLowerBound(0);
        xAxis.setUpperBound(60);
        xAxis.setTickUnit(4);
        xAxis.setAutoRanging(false);

        NumberAxis yAxis = (NumberAxis) lineChart.getYAxis();
        yAxis.setLowerBound(0);
        yAxis.setUpperBound(yBound);
        yAxis.setTickUnit(yTick);
        yAxis.setLabel(yLabel);
        yAxis.setAutoRanging(false);

        lineChart.setLegendSide(Side.RIGHT);

        lineChartMap.put(chartName, lineChart);
    }

    @SafeVarargs
    public final void addDataToChart(String chartName, XYChart.Data<Number, Number>... dataArrays) {
        LineChart<Number, Number> lineChart = lineChartMap.get(chartName);

        for (int i = 0; i < dataArrays.length; i++) {
            var data = dataArrays[i];
            var series = lineChart.getData().get(i).getData();
            int xVal = data.getXValue().intValue();
            NumberAxis xAxis = (NumberAxis) lineChart.getXAxis();
            double xBound = xAxis.getUpperBound();
            if (xVal > xBound) {
                xBound = xVal + 15;
                xAxis.setUpperBound(xBound);
                xAxis.setTickUnit(xAxis.getTickUnit() + 1);
            }

            int yVal = data.getYValue().intValue();
            NumberAxis yAxis = (NumberAxis) lineChart.getYAxis();
            double yBound = yAxis.getUpperBound();
            double desiredBound = (int) (Math.ceil((yVal / 5.) + 1) * 5);
            if (yBound < desiredBound || series.size() == 0) {
                yBound = desiredBound;
                yAxis.setUpperBound(yBound);
                yAxis.setTickUnit(yBound / 5.);
            }
            series.add(data);
        }
    }

    public void handleDeviceListBox() {
        if (selectedDevice != null)
            selectedDevice.shutdown();
        String deviceID = deviceListBox.getSelectionModel().getSelectedItem();
        selectedDevice = deviceMap.get(deviceID);

        // register perf services
        selectedDevice.registerService(FPSPerfService.class);
        selectedDevice.registerService(CPUPerfService.class);
        selectedDevice.registerService(NetworkPerfService.class);

        propTable.getItems().clear();
        packageListBox.getItems().clear();
        layerListBox.getItems().clear();

        // initialize the package list
        packageListBox.getItems().clear();
        packageListBox.getItems().addAll(selectedDevice.getPackageList());
        packageListBox.setEditable(true);

        // initialize basic properties of the device
        ArrayList<DeviceProp> props = selectedDevice.getProps();
        ObservableList<DeviceProp> data = FXCollections.observableArrayList(props);
        propTable.getItems().addAll(data);

        // UI update
        updateUIOnStateChanges();
        packageListBox.setDisable(false);
    }

    public void handlePackageListBox() {
        String packageName = packageListBox.getSelectionModel().getSelectedItem();
        if (packageName == null || packageName.length() == 0)
            return;
        selectedDevice.setTargetPackage(packageName);

        // initialize the layer list
        selectedDevice.checkLayerChanges();

        // UI update
        updateUIOnStateChanges();
        layerListBox.setDisable(false);
    }

    public void updateLayerListBox() {
        ArrayList<Layer> layers = selectedDevice.getLayers();
        ArrayList<String> layerItems = new ArrayList<>();
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            if (layer.hasBuffer)
                layerItems.add(String.format("Layer#%d:%s", i, layer.layerName));
        }
        layerListBox.getItems().setAll(layerItems);

        int targetLayer = selectedDevice.getTargetLayer();
        if (targetLayer != -1) {
            layerListBox.setValue(String.format("Layer#%d:%s", targetLayer, layers.get(targetLayer).layerName));
        }
        updateUIOnStateChanges();
    }

    public void handleLayerListBox() {
        String layerName = layerListBox.getSelectionModel().getSelectedItem();
        if (layerName != null) {
            Matcher matcher = layerPattern.matcher(layerName);
            if (matcher.find()) {
                int idx = Integer.parseInt(matcher.group(1));
                selectedDevice.setTargetLayer(idx, matcher.group(2));
            }
        }
        updateUIOnStateChanges();
    }

    public void handlePerfBtn() {
        if (selectedDevice.getPerfState()) {
            selectedDevice.endPerf();
        } else {
            selectedDevice.startPerf();
        }
    }

    public void handleUpdateBtn() {
        updateDeviceList();
        if (selectedDevice != null) {
            selectedDevice.updatePackageList();
            String selected = packageListBox.getSelectionModel().getSelectedItem();
            packageListBox.getItems().setAll(selectedDevice.getPackageList());
            if (packageListBox.getItems().contains(selected)) {
                packageListBox.setValue(selected);
            } else {
                // target app may be uninstalled
                selectedDevice.endPerf();
            }
        }
    }

    public void updateUIOnStateChanges() {
        if (selectedDevice == null || selectedDevice.getTargetPackage() == null
                || selectedDevice.getTargetLayer() < 0) {
            if (selectedDevice == null) {
                deviceListBox.setPromptText("Select connected devices");
                packageListBox.setPromptText("Select target package");
                layerListBox.setPromptText("Select target app layer");
                lineChartMap.forEach((k, v)-> v.getData().forEach(series->series.getData().clear()));
            } else if (selectedDevice.getTargetPackage() == null) {
                packageListBox.setPromptText("Select target package");
                layerListBox.setPromptText("Select target app layer");
                lineChartMap.forEach((k, v)-> v.getData().forEach(series->series.getData().clear()));
            } else {
                layerListBox.setPromptText("Select target app layer");
            }
            perfBtn.setDisable(true);
            perfBtn.setText("Start Perf");
            return;
        }
        perfBtn.setDisable(false);
        if (selectedDevice.getPerfState()) {
            perfBtn.setText("End Perf");
        } else {
            perfBtn.setText("Start Perf");
        }
    }

    public void shutdown() {
        deviceMap.forEach((s, device) -> device.shutdown());
    }

    static class AutoCompleteComboBoxListener<T> implements EventHandler<KeyEvent> {
        private final ComboBox<T> comboBox;
        private final ObservableList<T> data;
        private boolean moveCaretToPos = false;
        private int caretPos;

        public AutoCompleteComboBoxListener(final ComboBox<T> comboBox) {
            this.comboBox = comboBox;
            data = comboBox.getItems();

            this.comboBox.setEditable(true);
            this.comboBox.setOnKeyPressed(t -> comboBox.hide());
            this.comboBox.setOnKeyReleased(AutoCompleteComboBoxListener.this);
        }

        @Override
        public void handle(KeyEvent event) {
            if(event.getCode() == KeyCode.UP) {
                caretPos = -1;
                moveCaret(comboBox.getEditor().getText().length());
                return;
            } else if(event.getCode() == KeyCode.DOWN) {
                if(!comboBox.isShowing()) {
                    comboBox.show();
                }
                caretPos = -1;
                moveCaret(comboBox.getEditor().getText().length());
                return;
            } else if(event.getCode() == KeyCode.BACK_SPACE) {
                moveCaretToPos = true;
                caretPos = comboBox.getEditor().getCaretPosition();
            } else if(event.getCode() == KeyCode.DELETE) {
                moveCaretToPos = true;
                caretPos = comboBox.getEditor().getCaretPosition();
            }

            if (event.getCode() == KeyCode.RIGHT || event.getCode() == KeyCode.LEFT
                    || event.isControlDown() || event.getCode() == KeyCode.HOME
                    || event.getCode() == KeyCode.END || event.getCode() == KeyCode.TAB) {
                return;
            }

            ObservableList<T> list = FXCollections.observableArrayList();
            for (T datum : data) {
                if (datum.toString().toLowerCase().contains(
                        AutoCompleteComboBoxListener.this.comboBox
                                .getEditor().getText().toLowerCase())) {
                    list.add(datum);
                }
            }
            String t = comboBox.getEditor().getText();

            comboBox.setItems(list);
            comboBox.getEditor().setText(t);
            if(!moveCaretToPos) {
                caretPos = -1;
            }
            moveCaret(t.length());
            if(!list.isEmpty()) {
                comboBox.show();
            }
        }

        private void moveCaret(int textLength) {
            if(caretPos == -1) {
                comboBox.getEditor().positionCaret(textLength);
            } else {
                comboBox.getEditor().positionCaret(caretPos);
            }
            moveCaretToPos = false;
        }

    }
}
