package com.nanodxf.stat;

import java.util.Locale;

/**
 * 单地物编码面积统计行，由 {@link AreaStats#compute} 产生（不可变）。
 *
 * <p>面积单位：{@link AreaStats#compute(java.util.List, com.nanodxf.model.DrawingMetadata)}
 * 传入元数据时为平方米，否则为坐标原始单位²。亩/公顷按平方米换算
 * （1 亩 = 2000/3 m²，1 公顷 = 10000 m²），仅在面积为平方米时有实际意义。
 */
public final class AreaStatRow {

    private final String featureCode;
    private final String featureType;
    private final int    count;
    private final double area;

    AreaStatRow(String featureCode, String featureType, int count, double area) {
        this.featureCode = featureCode;
        this.featureType = featureType;
        this.count       = count;
        this.area        = area;
    }

    /** 地物编码（无编码实体归入 {@code "(无编码)"}）。 */
    public String getFeatureCode() { return featureCode; }
    /** 地物名称（来自属性或编码注册表；未知为空串）。 */
    public String getFeatureType() { return featureType; }
    /** 图斑数量（贡献面积的实体数）。 */
    public int getCount()          { return count; }
    /** 总面积（平方米或坐标单位²，见类注释）。 */
    public double getArea()        { return area; }
    /** 总面积（亩，= 面积 × 0.0015）。 */
    public double getMu()          { return area * 0.0015; }
    /** 总面积（公顷，= 面积 / 10000）。 */
    public double getHectares()    { return area / 10000.0; }

    /** 格式化为固定宽度的表格行。 */
    public String toTableRow() {
        return String.format(Locale.ROOT, "%-12s %-20s %6d %16.4f %12.4f %12.4f",
                featureCode, featureType, count, area, getMu(), getHectares());
    }

    // -------------------------------------------------------------------------
    // 内部累加器（包私有，供 AreaStats 使用）
    // -------------------------------------------------------------------------

    static final class Accumulator {
        final String featureCode;
        String featureType = "";
        int    count = 0;
        double area  = 0;

        Accumulator(String featureCode) { this.featureCode = featureCode; }

        void add(double entityArea, String type) {
            count++;
            area += entityArea;
            if (featureType.isEmpty() && type != null && !type.isEmpty()) featureType = type;
        }

        AreaStatRow build(double areaFactor) {
            return new AreaStatRow(featureCode, featureType, count, area * areaFactor);
        }
    }
}