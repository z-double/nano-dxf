package com.nanodxf.topology;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 拓扑检查配置（Builder 模式）。
 *
 * <pre>{@code
 * TopologyCheckConfig config = TopologyCheckConfig.builder()
 *     .rules(TopologyRule.DUPLICATE_ENTITY, TopologyRule.SELF_INTERSECTION,
 *            TopologyRule.CONTOUR_CROSSING)
 *     .contourLayers("等高线", "计曲线", "首曲线")
 *     .lineConnectLayers("道路中心线", "地类界")
 *     .snapTolerance(0.01)    // 1cm（坐标单位=米）
 *     .maxErrors(500)
 *     .build();
 * TopologyReport report = TopologyChecker.check(entities, config);
 * }</pre>
 */
public final class TopologyCheckConfig {

    private final Set<TopologyRule> rules;
    private final double            snapTolerance;
    private final int               maxErrors;
    private final Set<String>       contourLayers;
    private final Set<String>       lineConnectLayers;

    private TopologyCheckConfig(Builder b) {
        this.rules             = Collections.unmodifiableSet(EnumSet.copyOf(b.rules));
        this.snapTolerance     = b.snapTolerance;
        this.maxErrors         = b.maxErrors;
        this.contourLayers     = Collections.unmodifiableSet(b.contourLayers);
        this.lineConnectLayers = Collections.unmodifiableSet(b.lineConnectLayers);
    }

    /** 启用的检查规则集（不可变）。 */
    public Set<TopologyRule> getRules() { return rules; }

    /** 判定"重合/接触"的距离容差（坐标单位），默认 0.001。 */
    public double getSnapTolerance() { return snapTolerance; }

    /**
     * 错误上限，达到后停止检查，默认 1000。
     * 避免大文件产生数万条错误耗尽内存。
     */
    public int getMaxErrors() { return maxErrors; }

    /**
     * {@link TopologyRule#CONTOUR_CROSSING} 作用的图层名集合（大写）。
     * 空集合时对所有 LWPOLYLINE/POLYLINE 做交叉检查（谨慎使用，大文件耗时高）。
     */
    public Set<String> getContourLayers() { return contourLayers; }

    /**
     * {@link TopologyRule#DANGLING_ENDPOINT} 作用的图层名集合（大写）。
     * 空集合时对所有线实体做悬挂检测。
     */
    public Set<String> getLineConnectLayers() { return lineConnectLayers; }

    /** 使用所有规则的默认配置。 */
    public static TopologyCheckConfig defaults() { return builder().build(); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Set<TopologyRule> rules = EnumSet.allOf(TopologyRule.class);
        private double snapTolerance    = 0.001;
        private int    maxErrors        = 1000;
        private Set<String> contourLayers     = new java.util.HashSet<>();
        private Set<String> lineConnectLayers = new java.util.HashSet<>();

        /**
         * 指定要启用的规则（不传则默认全部 5 条）。
         */
        public Builder rules(TopologyRule... r) {
            this.rules = r.length == 0
                    ? EnumSet.allOf(TopologyRule.class)
                    : EnumSet.copyOf(Arrays.asList(r));
            return this;
        }

        /** 判定重合/接触的距离容差，默认 0.001。 */
        public Builder snapTolerance(double t) {
            if (t < 0) throw new IllegalArgumentException("snapTolerance 不能为负");
            this.snapTolerance = t; return this;
        }

        /** 错误总量上限，默认 1000。 */
        public Builder maxErrors(int max) {
            if (max <= 0) throw new IllegalArgumentException("maxErrors 必须 > 0");
            this.maxErrors = max; return this;
        }

        /** 等高线交叉检查作用的图层（大小写不敏感）。 */
        public Builder contourLayers(String... layers) {
            this.contourLayers = new java.util.HashSet<>();
            for (String l : layers) if (l != null) this.contourLayers.add(l.toUpperCase());
            return this;
        }

        /** 悬挂端点检查作用的图层（大小写不敏感）。 */
        public Builder lineConnectLayers(String... layers) {
            this.lineConnectLayers = new java.util.HashSet<>();
            for (String l : layers) if (l != null) this.lineConnectLayers.add(l.toUpperCase());
            return this;
        }

        public TopologyCheckConfig build() { return new TopologyCheckConfig(this); }
    }
}
