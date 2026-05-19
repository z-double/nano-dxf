package com.nanodxf.layer;

public class TextStyle {
    private final String name;
    private String fontFile;
    private double height;
    private double widthFactor = 1.0;

    public TextStyle(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public String getFontFile() { return fontFile; }
    public void setFontFile(String fontFile) { this.fontFile = fontFile; }
    public double getHeight() { return height; }
    public void setHeight(double height) { this.height = height; }
    public double getWidthFactor() { return widthFactor; }
    public void setWidthFactor(double widthFactor) { this.widthFactor = widthFactor; }
}
