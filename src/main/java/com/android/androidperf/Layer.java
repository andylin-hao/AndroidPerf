package com.android.androidperf;

public class Layer {
    String layerName;
    String packageName;
    boolean isVisible;
    boolean isSurfaceView = false;
    int id;
    int w, h, x, y, z;

    Layer(String name, String packageName, boolean buffer, int id, int w, int h, int x, int y, int z) {
        layerName = name;
        isVisible = buffer;
        this.packageName = packageName;
        this.id = id;
        if (layerName.startsWith("SurfaceView")) {
            isSurfaceView = true;
        }
        this.w = w;
        this.h = h;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean isCoveredBy(Layer layer) {
        return z <= layer.z && packageName.contains("incallui") &&
                x >= layer.x && y >= layer.y &&
                x + w <= layer.x + layer.w && y + h <= layer.y + layer.h;
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

        if (isVisible != layer.isVisible) return false;
        if (id != layer.id) return false;
        return layerName.equals(layer.layerName);
    }

    @Override
    public int hashCode() {
        int result = layerName.hashCode();
        result = 31 * result + (isVisible ? 1 : 0);
        result = 31 * result + id;
        return result;
    }
}
