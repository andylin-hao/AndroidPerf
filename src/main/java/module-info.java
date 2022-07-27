module com.example.androidperf {
    requires javafx.controls;
    requires javafx.fxml;
    requires jproc;
    requires org.apache.commons.lang3;
    requires org.controlsfx.controls;
    requires org.apache.logging.log4j;
    requires org.apache.logging.log4j.core;

    opens com.android.androidperf to javafx.fxml;
    exports com.android.androidperf;
}