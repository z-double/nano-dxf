package com.nanodxf;

/**
 * DXF 图形单位常量（对应 {@code $INSUNITS} 头变量）。
 *
 * <p>{@code $INSUNITS} 是 DXF HEADER 段中声明图形坐标单位的标准变量（group code 70）。
 * 解析器通过它判断是否需要进行单位换算（如将毫米坐标转换为米）；
 * 写出器通过它告知 CAD 软件坐标的物理含义。
 *
 * <h3>解析侧用法</h3>
 * <pre>{@code
 * DrawingMetadata meta = result.getMetadata();
 * if (InsUnit.METERS == meta.getInsUnits()) {
 *     // 坐标已为米，无需换算
 * }
 * }</pre>
 *
 * <h3>写出侧扩展（当前版本 DXFWriter 固定写出 METERS）</h3>
 * <pre>{@code
 * // 未来版本示例：
 * DXFWriteConfig.builder()
 *     .insUnit(InsUnit.MILLIMETERS) // 声明坐标单位为毫米
 *     .build();
 * }</pre>
 *
 * <h3>换算关系（相对于 1 米）</h3>
 * <table border="1">
 *   <tr><th>常量</th><th>值</th><th>单位</th><th>1 米 = ?</th></tr>
 *   <tr><td>UNITLESS</td><td>0</td><td>无单位</td><td>—（不换算）</td></tr>
 *   <tr><td>MILLIMETERS</td><td>4</td><td>毫米</td><td>1000 mm</td></tr>
 *   <tr><td>CENTIMETERS</td><td>5</td><td>厘米</td><td>100 cm</td></tr>
 *   <tr><td>METERS</td><td>6</td><td>米</td><td>1 m（GIS 常用）</td></tr>
 *   <tr><td>KILOMETERS</td><td>7</td><td>千米</td><td>0.001 km</td></tr>
 *   <tr><td>INCHES</td><td>1</td><td>英寸</td><td>39.37 in</td></tr>
 *   <tr><td>FEET</td><td>2</td><td>英尺</td><td>3.281 ft</td></tr>
 * </table>
 */
public final class InsUnit {

    private InsUnit() {}

    // =========================================================================
    // 无单位
    // =========================================================================

    /**
     * 无单位（0）。坐标无物理含义，解析器不进行单位换算。
     * 常见于建筑图、机械图等非地理坐标系图形。
     */
    public static final int UNITLESS = 0;

    // =========================================================================
    // 公制单位（GIS / 测绘场景主流）
    // =========================================================================

    /**
     * 毫米（4）。工程测量、建筑施工常用单位。
     * 解析时坐标 ÷ 1000 可换算为米。
     */
    public static final int MILLIMETERS = 4;

    /**
     * 厘米（5）。较少使用，部分旧版测绘软件默认输出。
     * 解析时坐标 ÷ 100 可换算为米。
     */
    public static final int CENTIMETERS = 5;

    /**
     * 米（6）。GIS / 测绘场景最常用单位，也是本库写出的默认单位。
     * 对应国家大地坐标系（CGCS2000）的坐标单位。
     */
    public static final int METERS = 6;

    /**
     * 千米（7）。极少见于 DXF 文件，通常用于超大范围底图。
     * 解析时坐标 × 1000 可换算为米。
     */
    public static final int KILOMETERS = 7;

    /**
     * 分米（14）。偶见于部分国内测绘软件的历史数据。
     * 解析时坐标 ÷ 10 可换算为米。
     */
    public static final int DECIMETERS = 14;

    // =========================================================================
    // 英制单位（AutoCAD 默认，北美场景）
    // =========================================================================

    /**
     * 英寸（1）。AutoCAD 默认单位（英制），北美建筑图常见。
     * 解析时坐标 × 0.0254 可换算为米。
     */
    public static final int INCHES = 1;

    /**
     * 英尺（2）。北美工程、规划图常见单位。
     * 解析时坐标 × 0.3048 可换算为米。
     */
    public static final int FEET = 2;

    /**
     * 英里（3）。极少用于 DXF 图形。
     */
    public static final int MILES = 3;

    // =========================================================================
    // 辅助方法
    // =========================================================================

    /**
     * 将给定单位的坐标换算为米。
     *
     * <pre>{@code
     * double meters = InsUnit.toMeters(coordinate, meta.getInsUnits());
     * }</pre>
     *
     * @param value    原始坐标值
     * @param insUnits {@code $INSUNITS} 值（本类常量之一）
     * @return 换算为米的坐标值；单位未知（UNITLESS 或不在列表中）时原样返回
     */
    public static double toMeters(double value, int insUnits) {
        return switch (insUnits) {
            case MILLIMETERS -> value / 1000.0;
            case CENTIMETERS -> value / 100.0;
            case DECIMETERS  -> value / 10.0;
            case METERS      -> value;
            case KILOMETERS  -> value * 1000.0;
            case INCHES      -> value * 0.0254;
            case FEET        -> value * 0.3048;
            case MILES       -> value * 1609.344;
            default          -> value; // UNITLESS 或未知，不换算
        };
    }
}
