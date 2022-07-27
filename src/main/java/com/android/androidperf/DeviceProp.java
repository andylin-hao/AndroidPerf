package com.android.androidperf;

import javafx.beans.property.SimpleStringProperty;

public class DeviceProp {
    private final SimpleStringProperty propName;
    private final SimpleStringProperty propVal;

    DeviceProp(String name, String val) {
        propName = new SimpleStringProperty(name);
        propVal = new SimpleStringProperty(val);
    }

    public SimpleStringProperty getPropName() {
        return propName;
    }

    public SimpleStringProperty getPropVal() {
        return propVal;
    }
}
