package com.android.androidperf;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayList;

public class MainApplication extends Application {
    private FXMLLoader fxmlLoader;

    @Override
    public void start(Stage stage) throws IOException {
        fxmlLoader = new FXMLLoader(MainApplication.class.getResource("main-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1024, 768);
        stage.setTitle("AndroidPerf");
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        AppController controller = fxmlLoader.getController();
        if (controller.selectedDevice != null) {
            controller.shutdown();
        }
    }

    public static void alert(String alertText, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setContentText(alertText);
        alert.show();
    }

    public static void main(String[] args) {
        launch();
    }
}