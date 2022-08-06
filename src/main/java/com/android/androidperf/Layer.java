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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Layer layer = (Layer) o;

        if (hasBuffer != layer.hasBuffer) return false;
        if (id != layer.id) return false;
        return layerName.equals(layer.layerName);
    }

    @Override
    public int hashCode() {
        int result = layerName.hashCode();
        result = 31 * result + (hasBuffer ? 1 : 0);
        result = 31 * result + id;
        return result;
    }
}
