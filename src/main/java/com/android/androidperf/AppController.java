package com.android.androidperf;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
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
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppController implements Initializable {
    private static final Logger LOGGER = LogManager.getLogger(AppController.class);
    @FXML
    private ComboBox<String> deviceListBox;
    @FXML
    private ComboBox<String> packageListBox;
    @FXML
    private ComboBox<Layer> layerListBox;
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
    private MapChangeListener<? super Integer, ? super Layer> layerListener = null;

    public Device selectedDevice;
    private final HashMap<String, Device> deviceMap = new HashMap<>();
    private final Pattern layerPattern = Pattern.compile("Layer#(\\d*):(.*)");
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

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
        initAllLineCharts();

        // set layer list comboBox's event handler
        layerListBox.getSelectionModel().selectedItemProperty().addListener(
                (options, oldValue, newValue) -> handleLayerListBox());

        // UI update
        updateUIOnStateChanges();
        packageListBox.setDisable(true);
        layerListBox.setDisable(true);

        // activate auto refresh task
        executorService.scheduleAtFixedRate(this::refreshTask, 500, 500, TimeUnit.MILLISECONDS);
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

    private void initAllLineCharts() {
        initLineChart(lineChartFPS, "FPS", new String[]{"FPS"}, 60, 10, "FPS");
        initLineChart(lineChartCPU, "CPU", new String[]{"App", "Total"}, 100, 20, "%");
        initLineChart(lineChartNetwork, "Network", new String[]{"Recv", "Send"}, 1000, 100, "KB/s");
    }

    private void initLineChart(LineChart<Number, Number> lineChart, String chartName, String[] series, int yBound, int yTick, String yLabel) {
        ObservableList<XYChart.Series<Number, Number>> seriesList = FXCollections.observableArrayList();
        for (String s : series) {
            XYChart.Series<Number, Number> data = new XYChart.Series<>();
            data.setName(s);
            seriesList.add(data);
        }
        lineChart.setData(seriesList);

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

        double yMax = Double.MIN_VALUE;
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

            Optional<XYChart.Data<Number, Number>> max = series.stream().max(Comparator.comparingDouble(fc -> fc.getYValue().doubleValue()));
            if (max.isPresent()) {
                double y = max.get().getYValue().doubleValue();
                if (y > yMax)
                    yMax = y;
            }
            series.add(data);
        }

        NumberAxis yAxis = (NumberAxis) lineChart.getYAxis();
        int yBound = (int) yAxis.getUpperBound();
        if (yMax == Double.MIN_VALUE)
            yMax = yBound;
        int desiredBound = (int) (Math.ceil((yMax / 5.) + 1) * 5);
        if (yBound != desiredBound) {
            yAxis.setUpperBound(desiredBound);
            yAxis.setTickUnit(desiredBound / 5.);
        }
    }

    public void handleDeviceListBox() {
        if (selectedDevice != null)
            selectedDevice.endPerf();
        String deviceID = deviceListBox.getSelectionModel().getSelectedItem();
        selectedDevice = deviceMap.get(deviceID);

        propTable.getItems().clear();
        layerListBox.getItems().clear();

        // initialize the package list
        packageListBox.setItems(selectedDevice.getPackageList());
        packageListBox.setEditable(true);

        // bind the layer list to the data source
        ObservableMap<Integer, Layer> layers = selectedDevice.getLayers();
        if (layerListener != null)
            layers.removeListener(layerListener);
        layerListener = (MapChangeListener<Integer, Layer>) change -> {
            layerListBox.getItems().setAll(layers.values());
            Layer targetLayer = selectedDevice.getTargetLayer();
            if (targetLayer != null)
                layerListBox.setValue(targetLayer);
            updateUIOnStateChanges();
        };
        layers.addListener(layerListener);

        // initialize basic properties of the device
        ArrayList<DeviceProp> props = selectedDevice.getProps();
        ObservableList<DeviceProp> data = FXCollections.observableArrayList(props);
        propTable.getItems().addAll(data);

        // UI update
        updateUIOnStateChanges();
        packageListBox.setDisable(false);
    }

    private void refreshTask() {
        if (selectedDevice != null) {
            boolean isChanged = selectedDevice.checkCurrentPackage();
            if (!isChanged) {
                String packageName = selectedDevice.getTargetPackage();
                if (packageName != null && !packageName.isEmpty()) {
                    selectedDevice.updateLayerList();
                }
            }
        }
    }

    public void handlePackageListBox() {
        String packageName = packageListBox.getSelectionModel().getSelectedItem();
        if (packageName == null || packageName.length() == 0) {
            selectedDevice.endPerf();
            return;
        }
        selectedDevice.setTargetPackage(packageName);

        // UI update
        updateUIOnStateChanges();
        layerListBox.setDisable(false);
    }

    public void handleLayerListBox() {
        Layer layer = layerListBox.getSelectionModel().getSelectedItem();
        if (layer != null) {
            selectedDevice.setTargetLayer(layer.id);
        }
        updateUIOnStateChanges();
    }

    public void handlePerfBtn() {
        if (selectedDevice.getPerfState()) {
            selectedDevice.endPerf();
        } else {
            initAllLineCharts();
            selectedDevice.startPerf();
        }
    }

    public void handleUpdateBtn() {
        updateDeviceList();
        if (selectedDevice != null) {
            selectedDevice.updatePackageList();
        } else {
            propTable.getItems().clear();
        }
    }

    public void updateUIOnStateChanges() {
        if (selectedDevice == null || selectedDevice.getTargetPackage() == null
                || selectedDevice.getTargetLayer() == null) {
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
            perfBtn.setText("Start");
            return;
        }
        perfBtn.setDisable(false);
        if (selectedDevice.getPerfState()) {
            perfBtn.setText("End");
        } else {
            perfBtn.setText("Start");
        }
    }

    public void shutdown() {
        deviceMap.forEach((s, device) -> device.shutdown());
        executorService.shutdownNow();
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
