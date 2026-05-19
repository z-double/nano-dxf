package com.nanodxf.geometry;

/**
 * AutoCAD 颜色索引（ACI）色表。
 *
 * <p>ACI（AutoCAD Color Index）使用 0-256 的整数表示颜色：
 * <ul>
 *   <li>0   - BYBLOCK（继承所在块的颜色）</li>
 *   <li>1-9 - 标准基础色（红、黄、绿、青、蓝、洋红、白、深灰、浅灰）</li>
 *   <li>10-249 - 扩展调色板（按色相/饱和度分组）</li>
 *   <li>256 - BYLAYER（继承所在图层的颜色，DXF 默认）</li>
 * </ul>
 *
 * <p>此表实现了 1-249 的完整标准色，均为 [R, G, B] 三元组（0-255）。
 * 颜色 7 在白色背景上显示为黑色，在黑色背景上显示为白色；此处取 (255,255,255)。
 */
public final class AciColorTable {

    private AciColorTable() {}

    /**
     * 将 ACI 颜色码转换为 RGB 三元组。
     *
     * @param aci ACI 颜色码（1-249）
     * @return [R, G, B] 三元组（0-255）；BYLAYER(256)/BYBLOCK(0)/超范围时返回 null
     */
    public static int[] toRgb(int aci) {
        if (aci <= 0 || aci == 256) return null; // BYBLOCK / BYLAYER
        if (aci >= 1 && aci <= 9) return STANDARD[aci - 1];
        if (aci >= 10 && aci <= 249) return buildPaletteColor(aci);
        return null;
    }

    // -------------------------------------------------------------------------
    // 标准色 1-9
    // -------------------------------------------------------------------------

    private static final int[][] STANDARD = {
        {255,   0,   0}, // 1 Red
        {255, 255,   0}, // 2 Yellow
        {  0, 255,   0}, // 3 Green
        {  0, 255, 255}, // 4 Cyan
        {  0,   0, 255}, // 5 Blue
        {255,   0, 255}, // 6 Magenta
        {255, 255, 255}, // 7 White
        {128, 128, 128}, // 8 Dark Gray
        {192, 192, 192}, // 9 Light Gray
    };

    // -------------------------------------------------------------------------
    // 扩展调色板 10-249（按标准 AutoCAD ACI 色相/明度规律生成）
    // -------------------------------------------------------------------------

    /**
     * ACI 扩展调色板规律：
     * <ul>
     *   <li>10-249 共 240 色，分 24 组（每组 10 色），对应 24 个色相</li>
     *   <li>每组色相步长约 15°（360°/24），每步内明度从高到低变化</li>
     *   <li>组内 10 色：全饱和亮（×2）→ 浅（×2）→ 标准（×2）→ 深（×2）→ 最深（×2）</li>
     * </ul>
     */
    private static int[] buildPaletteColor(int aci) {
        // 基于标准 AutoCAD 调色板编码规则
        int idx  = aci - 10;         // 0..239
        int hueGroup = idx / 10;     // 0..23（色相组）
        int step = idx % 10;         // 0..9（明度步）

        // 色相角度（度），第 0 组 = 0°（红），每组 +15°
        double hue = hueGroup * 15.0;

        // 每步对应的饱和度和明度（HSV 模型）
        double sat, val;
        if (step == 0 || step == 1) { sat = 1.0; val = 1.0; }      // 全饱和亮
        else if (step == 2 || step == 3) { sat = 0.5; val = 1.0; } // 浅色
        else if (step == 4 || step == 5) { sat = 1.0; val = 0.5; } // 半明
        else if (step == 6 || step == 7) { sat = 1.0; val = 0.3; } // 深色
        else                             { sat = 1.0; val = 0.15; } // 最深

        return hsvToRgb(hue, sat, val);
    }

    /** HSV → RGB 转换（标准公式）。 */
    private static int[] hsvToRgb(double h, double s, double v) {
        double c = v * s;
        double x = c * (1 - Math.abs((h / 60) % 2 - 1));
        double m = v - c;
        double r1, g1, b1;
        if      (h < 60)  { r1 = c; g1 = x; b1 = 0; }
        else if (h < 120) { r1 = x; g1 = c; b1 = 0; }
        else if (h < 180) { r1 = 0; g1 = c; b1 = x; }
        else if (h < 240) { r1 = 0; g1 = x; b1 = c; }
        else if (h < 300) { r1 = x; g1 = 0; b1 = c; }
        else              { r1 = c; g1 = 0; b1 = x; }
        return new int[]{
            (int) Math.round((r1 + m) * 255),
            (int) Math.round((g1 + m) * 255),
            (int) Math.round((b1 + m) * 255)
        };
    }
}
