package com.nanodxf.output;

/**
 * Shapefile 写出配置（Builder 模式）。
 *
 * <pre>{@code
 * ShapefileWriteConfig cfg = ShapefileWriteConfig.builder()
 *     .crs("EPSG:4545")
 *     .encoding("GBK")
 *     .coordinateDecimalPlaces(4)
 *     .build();
 * new ShapefileWriter(cfg).write(entities, Paths.get("output.shp"));
 * }</pre>
 */
public final class ShapefileWriteConfig {

    private final String crs;
    private final String encoding;
    private final int    coordinateDecimalPlaces;

    private ShapefileWriteConfig(Builder b) {
        this.crs                     = b.crs;
        this.encoding                = b.encoding;
        this.coordinateDecimalPlaces = b.coordinateDecimalPlaces;
    }

    public String getCrs()                    { return crs; }
    public String getEncoding()               { return encoding; }
    public int    getCoordinateDecimalPlaces() { return coordinateDecimalPlaces; }

    /** 默认配置（无 CRS、GBK 编码、4 位坐标精度）。 */
    public static ShapefileWriteConfig defaults() {
        return builder().build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String crs                    = null;
        private String encoding               = "GBK";
        private int    coordinateDecimalPlaces = 4;

        /**
         * 坐标参考系标识（如 {@code "EPSG:4545"}），写入 .prj 文件的 WKT 头注释。
         * 传入 null 时不生成 .prj 文件。
         */
        public Builder crs(String crs) { this.crs = crs; return this; }

        /**
         * DBF 属性文件字符集（默认 GBK）。
         * 国内 GIS 工具（ArcGIS、QGIS、MapGIS 等）读取 DBF 时通常按 GBK 解码。
         */
        public Builder encoding(String encoding) { this.encoding = encoding; return this; }

        /** 坐标小数位数（0~15，默认 4）。 */
        public Builder coordinateDecimalPlaces(int places) {
            if (places < 0 || places > 15)
                throw new IllegalArgumentException("coordinateDecimalPlaces 范围 0~15");
            this.coordinateDecimalPlaces = places;
            return this;
        }

        public ShapefileWriteConfig build() { return new ShapefileWriteConfig(this); }
    }
}
