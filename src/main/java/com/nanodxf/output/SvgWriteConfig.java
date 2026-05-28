package com.nanodxf.output;

import java.util.List;

/**
 * SVG 写出配置（Builder 模式）。
 *
 * <pre>{@code
 * SvgWriteConfig cfg = SvgWriteConfig.builder()
 *     .width(1920)
 *     .background("#1e1e1e")
 *     .strokeWidthPx(1.0)
 *     .build();
 * new SvgWriter(cfg).write(entities, Paths.get("output.svg"));
 * }</pre>
 */
public final class SvgWriteConfig {

    private final int    width;
    private final double paddingFraction;
    private final double strokeWidthPx;
    private final double pointRadiusPx;
    private final String background;
    private final List<String> layerOrder;

    private SvgWriteConfig(Builder b) {
        this.width           = b.width;
        this.paddingFraction = b.paddingFraction;
        this.strokeWidthPx   = b.strokeWidthPx;
        this.pointRadiusPx   = b.pointRadiusPx;
        this.background      = b.background;
        this.layerOrder      = b.layerOrder == null ? List.of() : List.copyOf(b.layerOrder);
    }

    /** 输出宽度（像素），高度按几何包围盒比例自动计算，默认 1200。 */
    public int getWidth() { return width; }

    /** 视图框外扩比例（0~1），默认 0.05（5%）。 */
    public double getPaddingFraction() { return paddingFraction; }

    /** 线宽（相对于 viewBox 坐标单位），默认 1.0。 */
    public double getStrokeWidthPx() { return strokeWidthPx; }

    /** POINT 实体圆半径（相对于 viewBox 坐标单位），默认 3.0。 */
    public double getPointRadiusPx() { return pointRadiusPx; }

    /** 背景色（CSS 颜色字符串，如 "#ffffff"）；null 表示透明背景。 */
    public String getBackground() { return background; }

    /** 图层渲染顺序（先渲染的在底层）；空列表时按图层名自然排序。 */
    public List<String> getLayerOrder() { return layerOrder; }

    public static SvgWriteConfig defaults() { return builder().build(); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int    width           = 1200;
        private double paddingFraction = 0.05;
        private double strokeWidthPx   = 1.0;
        private double pointRadiusPx   = 3.0;
        private String background      = null;
        private List<String> layerOrder = null;

        /** 输出宽度（像素），默认 1200。 */
        public Builder width(int w) {
            if (w <= 0) throw new IllegalArgumentException("width 必须 > 0");
            this.width = w; return this;
        }

        /** 视图框外扩比例（0~1），默认 0.05。 */
        public Builder paddingFraction(double f) {
            if (f < 0 || f > 1) throw new IllegalArgumentException("paddingFraction 范围 0~1");
            this.paddingFraction = f; return this;
        }

        /** 线宽（viewBox 单位），默认 1.0。 */
        public Builder strokeWidthPx(double w)  { this.strokeWidthPx = w; return this; }

        /** POINT 圆半径（viewBox 单位），默认 3.0。 */
        public Builder pointRadiusPx(double r)  { this.pointRadiusPx = r; return this; }

        /** 背景色（如 "#ffffff" 或 "white"）；null = 透明背景（默认）。 */
        public Builder background(String color)  { this.background = color; return this; }

        /** 图层渲染顺序。 */
        public Builder layerOrder(List<String> order) { this.layerOrder = order; return this; }

        public SvgWriteConfig build() { return new SvgWriteConfig(this); }
    }
}
