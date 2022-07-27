package com.android.androidperf;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Side;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.text.Text;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppController implements Initializable {
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
        ArrayList<Device> devices = Device.getDeviceList(this);
        for (var device : devices) {
            deviceListBox.getItems().add(device.getDeviceADBID());
            deviceMap.put(device.getDeviceADBID(), device);
        }

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
        initLineChart(lineChartFPS, "FPS", new String[]{"FPS"}, 60, 10, false);
        initLineChart(lineChartCPU, "CPU", new String[]{"App Usage", "Total Usage"}, 100, 20, true);
        initLineChart(lineChartNetwork, "Network", new String[]{"Receive", "Send"}, 1000, 100, true);

        // set layer list comboBox's event handler
        layerListBox.getSelectionModel().selectedItemProperty().addListener(
                (options, oldValue, newValue) -> handleLayerListBox());

        // UI update
        updatePromptText();
    }

    private void initLineChart(LineChart<Number, Number> lineChart, String chartName, String[] series, int yBound, int yTick, boolean legendVisible) {
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
        yAxis.setAutoRanging(false);

        lineChart.setLegendVisible(legendVisible);
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
            if (yBound < yVal || series.size() == 0) {
                yBound = (int) (Math.ceil((yVal / 5.)) * 5);
                if (yBound == 0) yBound = 5;
                yAxis.setUpperBound(yBound);
                yAxis.setTickUnit(yBound / 5.);
            }
            series.add(data);
        }
    }

    public void handleDeviceListBox() {
        if (selectedDevice != null)
            selectedDevice.endPerf();
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
        ArrayList<String> packages = selectedDevice.getPackageList();
        for (var packageName : packages) {
            packageListBox.getItems().add(packageName);
        }
        packageListBox.setEditable(true);

        // initialize basic properties of the device
        ArrayList<DeviceProp> props = selectedDevice.getProps();
        ObservableList<DeviceProp> data = FXCollections.observableArrayList(props);
        propTable.getItems().addAll(data);

        // UI update
        updatePromptText();
    }

    public void handlePackageListBox() {
        String packageName = packageListBox.getSelectionModel().getSelectedItem();
        selectedDevice.setTargetPackage(packageName);

        // initialize the layer list
        selectedDevice.checkLayerChanges();

        // UI update
        updateLayerListBox();
        updatePromptText();
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
        updatePromptText();
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
        updatePromptText();
    }

    public void handlePerfBtn() {
        if (selectedDevice.getPerfState()) {
            selectedDevice.endPerf();
        } else {
            selectedDevice.startPerf();
        }
    }

    public void updatePromptText() {
        if (selectedDevice == null || selectedDevice.getTargetPackage() == null
                || selectedDevice.getTargetLayer() < 0) {
            if (selectedDevice == null) {
                deviceListBox.setPromptText("Select connected devices");
                packageListBox.setPromptText("Select target package");
                layerListBox.setPromptText("Select target app layer");
            } else if (selectedDevice.getTargetPackage() == null) {
                packageListBox.setPromptText("Select target package");
                layerListBox.setPromptText("Select target app layer");
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
}
