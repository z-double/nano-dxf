package com.nanodxf.layer;

/**
 * DXF 文字样式定义，对应 TABLES 段中的 STYLE 表项（{@code code 0 = STYLE}）。
 *
 * <p>文字样式决定 TEXT / MTEXT 实体的字体、字高和宽度比例。
 * 由 {@link com.nanodxf.section.TablesParser} 解析后存入
 * {@link com.nanodxf.model.DXFContext#textStyles}，供文字 handler 读取。
 */
public class TextStyle {

    /** 样式名（code 2），默认样式名为 {@code "Standard"}。 */
    private final String name;

    /** 字体文件名（code 3），如 {@code "simplex.shx"} 或 {@code "Arial.ttf"}，可为 null。 */
    private String fontFile;

    /**
     * 固定字高（code 40），单位与图纸一致。
     * 0 表示字高由每个文字实体单独指定（code 40 在 TEXT 实体中）。
     */
    private double height;

    /** 宽度系数（code 41），1.0 为标准宽度，&lt;1 压缩，&gt;1 拉伸。默认 1.0。 */
    private double widthFactor = 1.0;

    public TextStyle(String name) {
        this.name = name;
    }

    public String getName()                         { return name; }

    public String getFontFile()                     { return fontFile; }
    public void setFontFile(String fontFile)        { this.fontFile = fontFile; }

    public double getHeight()                       { return height; }
    public void setHeight(double height)            { this.height = height; }

    public double getWidthFactor()                  { return widthFactor; }
    public void setWidthFactor(double widthFactor)  { this.widthFactor = widthFactor; }
}
