package com.nanodxf.output;

/**
 * Shapefile 写出配置（Builder 模式）。
 *
 * <pre>{@code
 * ShapefileWriteConfig cfg = ShapefileWriteConfig.builder()
 *     .crs("EPSG:4490")
 *     .encoding("GBK")
 *     .coordinateDecimalPlaces(4)
 *     .dimension(ShapefileWriteConfig.ShapeDimension.AUTO)  // 默认，有 Z 自动写 3D
 *     .build();
 * new ShapefileWriter(cfg).write(entities, Paths.get("output.shp"));
 * }</pre>
 */
public final class ShapefileWriteConfig {

    /**
     * Shapefile 几何维度控制。
     *
     * <ul>
     *   <li>{@link #AUTO} — 自动检测：实体中任意坐标的 Z 值非 NaN 时写出 PointZ / PolylineZ /
     *       PolygonZ（Shape Type 11 / 13 / 15），否则退回 2D（默认）。</li>
     *   <li>{@link #XY}   — 强制 2D，忽略所有 Z 值。</li>
     *   <li>{@link #XYZ}  — 强制 3D；若坐标 Z 为 NaN，写出为 0.0。</li>
     * </ul>
     */
    public enum ShapeDimension { AUTO, XY, XYZ }

    private final String         crs;
    private final String         encoding;
    private final int            coordinateDecimalPlaces;
    private final ShapeDimension dimension;

    private ShapefileWriteConfig(Builder b) {
        this.crs                     = b.crs;
        this.encoding                = b.encoding;
        this.coordinateDecimalPlaces = b.coordinateDecimalPlaces;
        this.dimension               = b.dimension;
    }

    public String         getCrs()                    { return crs; }
    public String         getEncoding()               { return encoding; }
    public int            getCoordinateDecimalPlaces() { return coordinateDecimalPlaces; }
    public ShapeDimension getDimension()              { return dimension; }

    /** 默认配置（无 CRS、GBK 编码、4 位坐标精度、AUTO 维度）。 */
    public static ShapefileWriteConfig defaults() {
        return builder().build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String         crs                    = null;
        private String         encoding               = "GBK";
        private int            coordinateDecimalPlaces = 4;
        private ShapeDimension dimension               = ShapeDimension.AUTO;

        /**
         * 坐标参考系标识（如 {@code "EPSG:4490"}），写入 .prj 文件。
         * 内置支持 EPSG:4326、EPSG:4490 以及 CGCS2000 高斯投影带（EPSG:4534–4554）等；
         * 也可直接传入完整 WKT 字符串。传入 null 时不生成 .prj 文件。
         */
        public Builder crs(String crs) { this.crs = crs; return this; }

        /**
         * DBF 属性文件字符集（默认 GBK）。
         * 国内 GIS 工具（ArcGIS、QGIS、MapGIS 等）读取 DBF 时通常按 GBK 解码。
         */
        public Builder encoding(String encoding) { this.encoding = encoding; return this; }

        /** ELEVATION 字段小数位数（0~15，默认 4），同时影响字段宽度。 */
        public Builder coordinateDecimalPlaces(int places) {
            if (places < 0 || places > 15)
                throw new IllegalArgumentException("coordinateDecimalPlaces 范围 0~15");
            this.coordinateDecimalPlaces = places;
            return this;
        }

        /**
         * 几何维度模式（默认 {@link ShapeDimension#AUTO}）。
         * AUTO = 有 Z 数据时自动写 3D；XY = 强制 2D；XYZ = 强制 3D。
         */
        public Builder dimension(ShapeDimension dimension) {
            this.dimension = dimension;
            return this;
        }

        public ShapefileWriteConfig build() { return new ShapefileWriteConfig(this); }
    }
}
