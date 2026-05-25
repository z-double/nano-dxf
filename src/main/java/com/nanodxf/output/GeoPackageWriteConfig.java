package com.nanodxf.output;

/**
 * GeoPackage 写出配置（Builder 模式）。
 *
 * <pre>{@code
 * GeoPackageWriteConfig cfg = GeoPackageWriteConfig.builder()
 *     .crs("EPSG:4490")
 *     .coordinateDecimalPlaces(6)
 *     .build();
 * new GeoPackageWriter(cfg).write(entities, Paths.get("output.gpkg"));
 * }</pre>
 */
public final class GeoPackageWriteConfig {

    private final String crs;
    private final int    coordinateDecimalPlaces;

    private GeoPackageWriteConfig(Builder b) {
        this.crs                    = b.crs;
        this.coordinateDecimalPlaces = b.coordinateDecimalPlaces;
    }

    /** 坐标参考系标识（如 "EPSG:4326"、"EPSG:4490"）；null 表示不记录 CRS。 */
    public String getCrs() { return crs; }

    /** 属性表中 ELEVATION 字段的小数位数，默认 4。 */
    public int getCoordinateDecimalPlaces() { return coordinateDecimalPlaces; }

    /** 使用默认设置（无 CRS，小数位 4）。 */
    public static GeoPackageWriteConfig defaults() {
        return builder().build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String crs;
        private int coordinateDecimalPlaces = 4;

        /** 坐标参考系，如 "EPSG:4326"、"EPSG:4490"。 */
        public Builder crs(String crs)                            { this.crs = crs; return this; }
        /** ELEVATION 属性字段小数位数（0–15）。 */
        public Builder coordinateDecimalPlaces(int places)        { this.coordinateDecimalPlaces = places; return this; }

        public GeoPackageWriteConfig build() {
            if (coordinateDecimalPlaces < 0 || coordinateDecimalPlaces > 15)
                throw new IllegalArgumentException("coordinateDecimalPlaces must be 0-15");
            return new GeoPackageWriteConfig(this);
        }
    }
}
