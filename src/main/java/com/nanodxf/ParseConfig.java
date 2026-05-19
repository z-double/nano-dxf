package com.nanodxf;

import com.nanodxf.model.ZStrategy;

/**
 * DXF 解析配置。通过 {@link Builder} 创建，{@code build()} 时校验参数合法性。
 *
 * <pre>{@code
 * ParseConfig config = ParseConfig.builder()
 *     .crs("EPSG:4545")       // 坐标参考系（如 CGCS2000 117°E 带）
 *     .arcTolerance(0.001)    // 弧线离散弦高误差（米）
 *     .build();
 * }</pre>
 */
public class ParseConfig {

    /** 弧线/样条离散化的最大弦高误差（与坐标单位相同），默认 0.001。 */
    private final double arcTolerance;

    /** 顶点去重距离阈值：两点距离小于此值视为重复，默认 1e-6。 */
    private final double proximityThreshold;

    /** GeoJSON 坐标序列化小数位数（0~15），默认 4（0.1mm 级精度）。 */
    private final int coordinateDecimalPlaces;

    /** Z 坐标输出策略，默认 {@link ZStrategy#KEEP_3D}。 */
    private final ZStrategy zStrategy;

    /**
     * 坐标参考系标识（如 {@code "EPSG:4545"}），写入 GeoJSON 顶层 {@code crs} 字段。
     * 为 null 时不输出 crs 字段。
     */
    private final String crs;

    /**
     * 是否根据 {@code $INSUNITS} 将坐标换算为米，默认 true。
     * 设为 false 时坐标原样输出（保留原始 CAD 单位）。
     */
    private final boolean applyUnitConversion;

    private ParseConfig(Builder builder) {
        this.arcTolerance = builder.arcTolerance;
        this.proximityThreshold = builder.proximityThreshold;
        this.coordinateDecimalPlaces = builder.coordinateDecimalPlaces;
        this.zStrategy = builder.zStrategy;
        this.crs = builder.crs;
        this.applyUnitConversion = builder.applyUnitConversion;
    }

    public double getArcTolerance() { return arcTolerance; }
    public double getProximityThreshold() { return proximityThreshold; }
    public int getCoordinateDecimalPlaces() { return coordinateDecimalPlaces; }
    public ZStrategy getZStrategy() { return zStrategy; }
    public String getCrs() { return crs; }
    public boolean isApplyUnitConversion() { return applyUnitConversion; }

    public static Builder builder() { return new Builder(); }
    public static ParseConfig defaults() { return builder().build(); }

    public static class Builder {
        private double arcTolerance = 0.001;
        private double proximityThreshold = 1e-6;
        private int coordinateDecimalPlaces = 4;
        private ZStrategy zStrategy = ZStrategy.KEEP_3D;
        private String crs = null;
        private boolean applyUnitConversion = true;

        /**
         * 弧线/样条离散弦高误差（坐标单位），必须 &gt; 0，默认 0.001。
         * 值越小采样点越密，几何越精确，但解析耗时略增。
         */
        public Builder arcTolerance(double v) {
            if (v <= 0) throw new IllegalArgumentException("arcTolerance 必须 > 0，当前值：" + v);
            this.arcTolerance = v; return this;
        }

        /**
         * 顶点去重距离阈值（坐标单位），不能为负，默认 1e-6。
         * 相邻点距离小于此值时合并为一点，消除重复顶点噪声。
         */
        public Builder proximityThreshold(double v) {
            if (v < 0) throw new IllegalArgumentException("proximityThreshold 不能为负");
            this.proximityThreshold = v; return this;
        }

        /**
         * GeoJSON 坐标小数位数（0~15），默认 4。
         * 建议：毫米级精度 3 位，测绘地形图 4 位，高精度工程 6 位。
         */
        public Builder coordinateDecimalPlaces(int v) {
            if (v < 0 || v > 15)
                throw new IllegalArgumentException("coordinateDecimalPlaces 范围 0~15");
            this.coordinateDecimalPlaces = v; return this;
        }

        /** Z 坐标输出策略，默认 {@link ZStrategy#KEEP_3D}（保留高程）。 */
        public Builder zStrategy(ZStrategy s) { this.zStrategy = s; return this; }

        /**
         * 坐标参考系标识，写入 GeoJSON {@code crs} 字段。
         * 例：{@code "EPSG:4545"}（CGCS2000 117°E 3 度带）。
         * 不传则输出中不含 crs 字段。
         */
        public Builder crs(String crs) { this.crs = crs; return this; }

        /**
         * 是否将坐标按 {@code $INSUNITS} 换算为米，默认 true。
         * 毫米单位（{@code $INSUNITS=4}）的 DXF 若不换算，坐标量级会差 1000 倍。
         * 设为 false 保留原始 CAD 坐标（适用于已确认为米的文件或不需要地理坐标的场景）。
         */
        public Builder applyUnitConversion(boolean v) { this.applyUnitConversion = v; return this; }

        /** 构建并返回不可变的 {@link ParseConfig}，同时校验所有参数合法性。 */
        public ParseConfig build() { return new ParseConfig(this); }
    }
}
