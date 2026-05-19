package com.nanodxf.geometry;

import java.util.Map;

/**
 * DXF 坐标单位换算器。
 *
 * <p>将 {@code $INSUNITS} 变量指定的图纸单位换算为米。
 * 换算系数来自 DXF 规范：
 *
 * <table>
 *   <tr><th>$INSUNITS</th><th>单位</th><th>换算系数</th></tr>
 *   <tr><td>0</td><td>无单位（原样输出）</td><td>1.0</td></tr>
 *   <tr><td>1</td><td>英寸</td><td>0.0254</td></tr>
 *   <tr><td>2</td><td>英尺</td><td>0.3048</td></tr>
 *   <tr><td>4</td><td>毫米（国内测绘常见）</td><td>0.001</td></tr>
 *   <tr><td>5</td><td>厘米</td><td>0.01</td></tr>
 *   <tr><td>6</td><td>米</td><td>1.0</td></tr>
 *   <tr><td>7</td><td>千米</td><td>1000.0</td></tr>
 *   <tr><td>13</td><td>海里</td><td>1852.0</td></tr>
 * </table>
 *
 * <p>注意：{@code $INSUNITS=0}（未指定）时应记录 WARNING，坐标原样输出，
 * 由调用方根据坐标量级判断实际单位。
 */
public class UnitConverter {

    private static final Map<Integer, Double> TO_METER = Map.of(
        0,  1.0,      // 无单位，原样输出
        1,  0.0254,   // 英寸
        2,  0.3048,   // 英尺
        4,  0.001,    // 毫米（国内测绘 CAD 最常见）
        5,  0.01,     // 厘米
        6,  1.0,      // 米
        7,  1000.0,   // 千米
        13, 1852.0    // 海里
    );

    /**
     * 将坐标值换算为米。
     *
     * @param value    原始坐标值
     * @param insunits DXF {@code $INSUNITS} 变量值
     * @return 换算后的米值；insunits 未知时原样返回（系数 1.0）
     */
    public double toMeter(double value, int insunits) {
        return value * TO_METER.getOrDefault(insunits, 1.0);
    }

    /**
     * 返回 {@code $INSUNITS} 对应的换算系数（单位→米）。
     * 系数为 1.0 时无需换算（已是米，或未知单位）。
     *
     * @param insunits DXF {@code $INSUNITS} 变量值
     * @return 换算系数，如毫米返回 0.001，英寸返回 0.0254
     */
    public double scaleFactor(int insunits) {
        return TO_METER.getOrDefault(insunits, 1.0);
    }
}
