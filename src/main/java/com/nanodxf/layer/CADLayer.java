package com.nanodxf.layer;

import com.nanodxf.geometry.AciColorTable;

/**
 * DXF 图层定义，对应 TABLES 段中的 LAYER 表项。
 *
 * <p>图层是 DXF 中实体颜色、线型的默认来源。当实体颜色为 BYLAYER（ACI=256）时，
 * {@link com.nanodxf.section.EntitiesParser} 从此对象获取 {@link #colorRgb()} 作为实体颜色。
 *
 * <p>颜色维护规则：{@link #setColorNumber(int)} 会自动将 ACI 颜色号映射为 RGB，
 * 无需手动调用 {@link #setColorRgb(int[])}。构造时默认颜色 7（白色）也已映射。
 */
public class CADLayer {

    /** 图层名，大小写敏感，默认图层名为 {@code "0"}。 */
    private final String name;

    /**
     * ACI 颜色号（code 62）。
     * 特殊值：256=BYLAYER（继承图层），0=BYBLOCK（继承块）。
     * 默认 7（白色）。
     */
    private int colorNumber = 7;

    /** 线型名（code 6），默认 {@code "Continuous"}（实线）。 */
    private String lineTypeName = "Continuous";

    /**
     * 可见性。code 70 的 bit 0 为 1 时图层被冻结（不可见），为 0 时可见。
     * 默认 true（可见）。
     */
    private boolean visible = true;

    /**
     * ACI 颜色号对应的 RGB 分量 {@code [R, G, B]}，由 {@link #setColorNumber} 自动维护。
     * {@link com.nanodxf.section.EntitiesParser} 的 BYLAYER 分支读取此字段继承颜色。
     */
    private int[] colorRgb;

    public CADLayer(String name) {
        this.name = name;
        this.colorRgb = AciColorTable.toRgb(7); // 默认颜色 7（白色）
    }

    public String getName() { return name; }

    public int getColorNumber() { return colorNumber; }

    /**
     * 设置 ACI 颜色号，同时自动更新 {@link #colorRgb}。
     * TablesParser 在解析 LAYER 表时调用此方法。
     */
    public void setColorNumber(int colorNumber) {
        this.colorNumber = colorNumber;
        this.colorRgb = AciColorTable.toRgb(colorNumber);
    }

    public String getLineTypeName() { return lineTypeName; }
    public void setLineTypeName(String lineTypeName) { this.lineTypeName = lineTypeName; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    /** 返回 RGB 颜色数组 {@code [R, G, B]}，用于 BYLAYER 颜色继承；无法映射时为 null。 */
    public int[] colorRgb() { return colorRgb; }

    /** 直接设置 RGB（通常无需调用，{@link #setColorNumber} 会自动维护）。 */
    public void setColorRgb(int[] colorRgb) { this.colorRgb = colorRgb; }
}
