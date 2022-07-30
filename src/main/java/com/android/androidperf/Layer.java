package com.android.androidperf;

public class Layer {
    String layerName;
    boolean hasBuffer;
    boolean isSurfaceView = false;
    int id;
    Layer(String name, boolean buffer, int id) {
        layerName = name;
        hasBuffer = buffer;
        this.id = id;
        if (layerName.startsWith("SurfaceView")) {
            isSurfaceView = true;
        }
    }

    @Override
    public String toString() {
        return String.format("Layer#%d:%s", id, layerName);
    }
}
