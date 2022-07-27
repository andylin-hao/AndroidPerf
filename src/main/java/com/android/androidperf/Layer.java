package com.android.androidperf;

public class Layer {
    String layerName;
    boolean hasBuffer;
    boolean isSurfaceView = false;
    Layer(String name, boolean buffer) {
        layerName = name;
        hasBuffer = buffer;
        if (layerName.startsWith("SurfaceView")) {
            isSurfaceView = true;
        }
    }
}
