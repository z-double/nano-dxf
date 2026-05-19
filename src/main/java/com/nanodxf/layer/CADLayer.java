package com.nanodxf.layer;

import com.nanodxf.geometry.AciColorTable;

public class CADLayer {
    private final String name;
    private int colorNumber = 7;
    private String lineTypeName = "Continuous";
    private boolean visible = true;
    private int[] colorRgb;

    public CADLayer(String name) {
        this.name = name;
        this.colorRgb = AciColorTable.toRgb(7); // default color 7 = white
    }

    public String getName() { return name; }

    public int getColorNumber() { return colorNumber; }
    public void setColorNumber(int colorNumber) {
        this.colorNumber = colorNumber;
        this.colorRgb = AciColorTable.toRgb(colorNumber);
    }

    public String getLineTypeName() { return lineTypeName; }
    public void setLineTypeName(String lineTypeName) { this.lineTypeName = lineTypeName; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public int[] colorRgb() { return colorRgb; }
    public void setColorRgb(int[] colorRgb) { this.colorRgb = colorRgb; }
}
