package com.nanodxf;

import com.nanodxf.model.ZStrategy;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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

    /**
     * 图层白名单（大小写不敏感）。非空时只解析列表内的图层，其余图层实体跳过。
     * 与 {@link #excludeLayers} 同时设置时，白名单优先（白名单内的图层不受黑名单影响）。
     * null 表示不限制。
     */
    private final Set<String> includeLayers;

    /**
     * 图层黑名单（大小写不敏感）。列表内的图层实体跳过。
     * 当图层同时出现在白名单中时，白名单优先（不跳过）。
     * null 表示不限制。
     */
    private final Set<String> excludeLayers;

    /**
     * 实体类型白名单（大小写不敏感，如 "LINE"/"LWPOLYLINE"）。
     * 非空时只解析列表内的类型，其余类型跳过（不计入 skippedEntityTypes）。
     * null 表示不限制。
     */
    private final Set<String> includeTypes;

    private ParseConfig(Builder builder) {
        this.arcTolerance = builder.arcTolerance;
        this.proximityThreshold = builder.proximityThreshold;
        this.coordinateDecimalPlaces = builder.coordinateDecimalPlaces;
        this.zStrategy = builder.zStrategy;
        this.crs = builder.crs;
        this.applyUnitConversion = builder.applyUnitConversion;
        this.includeLayers = builder.includeLayers == null ? null
                : Collections.unmodifiableSet(toUpperSet(builder.includeLayers));
        this.excludeLayers = builder.excludeLayers == null ? null
                : Collections.unmodifiableSet(toUpperSet(builder.excludeLayers));
        this.includeTypes = builder.includeTypes == null ? null
                : Collections.unmodifiableSet(toUpperSet(builder.includeTypes));
    }

    public double getArcTolerance() { return arcTolerance; }
    public double getProximityThreshold() { return proximityThreshold; }
    public int getCoordinateDecimalPlaces() { return coordinateDecimalPlaces; }
    public ZStrategy getZStrategy() { return zStrategy; }
    public String getCrs() { return crs; }
    public boolean isApplyUnitConversion() { return applyUnitConversion; }
    public Set<String> getIncludeLayers() { return includeLayers; }
    public Set<String> getExcludeLayers() { return excludeLayers; }
    public Set<String> getIncludeTypes()  { return includeTypes; }

    /**
     * 根据当前过滤配置判断指定图层 + 类型的实体是否应被解析。
     *
     * @param entityType 实体类型（原始大小写，内部转大写比较）
     * @param layer      图层名（原始大小写，内部转大写比较）
     * @return true 表示应解析；false 表示应跳过
     */
    public boolean accepts(String entityType, String layer) {
        // 类型白名单过滤
        if (includeTypes != null && !includeTypes.contains(entityType.toUpperCase())) return false;
        // 图层白名单：在白名单内 → 不受黑名单影响，直接接受
        String upperLayer = layer == null ? "0" : layer.toUpperCase();
        if (includeLayers != null) return includeLayers.contains(upperLayer);
        // 图层黑名单
        if (excludeLayers != null && excludeLayers.contains(upperLayer)) return false;
        return true;
    }

    private static Set<String> toUpperSet(Set<String> src) {
        Set<String> result = new HashSet<>();
        for (String s : src) if (s != null) result.add(s.toUpperCase());
        return result;
    }

    public static Builder builder() { return new Builder(); }
    public static ParseConfig defaults() { return builder().build(); }

    public static class Builder {
        private double arcTolerance = 0.001;
        private double proximityThreshold = 1e-6;
        private int coordinateDecimalPlaces = 4;
        private ZStrategy zStrategy = ZStrategy.KEEP_3D;
        private String crs = null;
        private boolean applyUnitConversion = true;
        private Set<String> includeLayers = null;
        private Set<String> excludeLayers = null;
        private Set<String> includeTypes  = null;

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

        /**
         * 图层白名单（大小写不敏感）。只解析指定图层的实体，其余跳过。
         * 与 {@link #excludeLayers} 同时设置时，白名单优先。
         */
        public Builder includeLayers(String... layers) {
            this.includeLayers = new HashSet<>(Set.of(layers)); return this;
        }

        /**
         * 图层黑名单（大小写不敏感）。跳过指定图层的实体。
         * 白名单内的图层不受黑名单影响。
         */
        public Builder excludeLayers(String... layers) {
            this.excludeLayers = new HashSet<>(Set.of(layers)); return this;
        }

        /**
         * 实体类型白名单（大小写不敏感，如 "LINE"/"LWPOLYLINE"）。
         * 只解析指定类型，其余跳过（不计入 skippedEntityTypes）。
         */
        public Builder includeTypes(String... types) {
            this.includeTypes = new HashSet<>(Set.of(types)); return this;
        }

        /** 构建并返回不可变的 {@link ParseConfig}，同时校验所有参数合法性。 */
        public ParseConfig build() { return new ParseConfig(this); }
    }
}
