package com.nanodxf.output;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 内置坐标参考系（CRS）注册表，集中管理 EPSG 代号到 WKT 的映射。
 *
 * <p>提供两套 WKT 方言，分别供不同输出格式使用：
 * <ul>
 *   <li>{@link #esriWkt(int)} — ESRI WKT，用于 Shapefile 的 {@code .prj} 文件</li>
 *   <li>{@link #ogcWkt(int)}  — OGC WKT，用于 GeoPackage 的 {@code gpkg_spatial_ref_sys}</li>
 * </ul>
 *
 * <p>内置范围：
 * <ul>
 *   <li>EPSG:4326 — WGS 84</li>
 *   <li>EPSG:4490 — CGCS2000 地理坐标系</li>
 *   <li>EPSG:4534–4554 — CGCS2000 3° 高斯克吕格投影（中央经线 75°E 起，每带 +3°）</li>
 *   <li>EPSG:32644–32654 — WGS 84 / UTM（中国覆盖范围）</li>
 *   <li>EPSG:3857 — WGS 84 / Pseudo-Mercator（仅 OGC）</li>
 * </ul>
 */
public final class CrsRegistry {

    private CrsRegistry() {}

    // WGS 84 地理坐标系
    private static final String ESRI_WGS84 =
            "GEOGCS[\"GCS_WGS_1984\"," +
            "DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]]," +
            "PRIMEM[\"Greenwich\",0.0]," +
            "UNIT[\"Degree\",0.0174532925199433]]";

    // CGCS2000 地理坐标系
    private static final String ESRI_CGCS2000 =
            "GEOGCS[\"GCS_China_Geodetic_Coordinate_System_2000\"," +
            "DATUM[\"D_China_2000\",SPHEROID[\"CGCS2000\",6378137.0,298.257222101]]," +
            "PRIMEM[\"Greenwich\",0.0]," +
            "UNIT[\"Degree\",0.0174532925199433]]";

    private static final String OGC_WGS84 =
            "GEOGCS[\"WGS 84\"," +
            "DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]]," +
            "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]";

    private static final String OGC_CGCS2000 =
            "GEOGCS[\"China Geodetic Coordinate System 2000\"," +
            "DATUM[\"China_2000\",SPHEROID[\"CGCS2000\",6378137,298.257222101]]," +
            "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]";

    private static final String OGC_WEB_MERCATOR =
            "PROJCS[\"WGS 84 / Pseudo-Mercator\",GEOGCS[\"WGS 84\"," +
            "DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]]," +
            "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]," +
            "PROJECTION[\"Mercator_1SP\"],PARAMETER[\"central_meridian\",0]," +
            "PARAMETER[\"scale_factor\",1],PARAMETER[\"false_easting\",0]," +
            "PARAMETER[\"false_northing\",0],UNIT[\"metre\",1]]";

    private static final Map<Integer, String> ESRI_WKT;
    private static final Map<Integer, String> OGC_WKT;

    static {
        Map<Integer, String> esri = new LinkedHashMap<>();
        Map<Integer, String> ogc  = new LinkedHashMap<>();

        esri.put(4326, ESRI_WGS84);
        esri.put(4490, ESRI_CGCS2000);
        ogc.put(4326, OGC_WGS84);
        ogc.put(4490, OGC_CGCS2000);
        ogc.put(3857, OGC_WEB_MERCATOR);

        // CGCS2000 3° 高斯克吕格：EPSG:4534 = CM 75°，每增加 1 → CM + 3°
        for (int epsg = 4534; epsg <= 4554; epsg++) {
            int cm = 75 + 3 * (epsg - 4534);
            esri.put(epsg,
                "PROJCS[\"CGCS2000 / 3-degree Gauss-Kruger CM " + cm + "E\"," +
                ESRI_CGCS2000 + "," +
                "PROJECTION[\"Gauss_Kruger\"]," +
                "PARAMETER[\"False_Easting\",500000.0]," +
                "PARAMETER[\"False_Northing\",0.0]," +
                "PARAMETER[\"Central_Meridian\"," + cm + ".0]," +
                "PARAMETER[\"Scale_Factor\",1.0]," +
                "PARAMETER[\"Latitude_Of_Origin\",0.0]," +
                "UNIT[\"Meter\",1.0]]");
            ogc.put(epsg,
                "PROJCS[\"CGCS2000 / 3-degree Gauss-Kruger CM " + cm + "E\"," +
                OGC_CGCS2000 + "," +
                "PROJECTION[\"Transverse_Mercator\"]," +
                "PARAMETER[\"latitude_of_origin\",0]," +
                "PARAMETER[\"central_meridian\"," + cm + "]," +
                "PARAMETER[\"scale_factor\",1]," +
                "PARAMETER[\"false_easting\",500000]," +
                "PARAMETER[\"false_northing\",0]," +
                "UNIT[\"metre\",1]]");
        }

        // WGS84 / UTM：EPSG:326xx = WGS84/UTM zone xxN，CM = xx×6 - 183
        for (int zone = 44; zone <= 54; zone++) {
            int epsg = 32600 + zone;
            int cm = zone * 6 - 183;
            esri.put(epsg,
                "PROJCS[\"WGS_1984_UTM_Zone_" + zone + "N\"," +
                ESRI_WGS84 + "," +
                "PROJECTION[\"Transverse_Mercator\"]," +
                "PARAMETER[\"False_Easting\",500000.0]," +
                "PARAMETER[\"False_Northing\",0.0]," +
                "PARAMETER[\"Central_Meridian\"," + cm + ".0]," +
                "PARAMETER[\"Scale_Factor\",0.9996]," +
                "PARAMETER[\"Latitude_Of_Origin\",0.0]," +
                "UNIT[\"Meter\",1.0]]");
            ogc.put(epsg,
                "PROJCS[\"WGS 84 / UTM zone " + zone + "N\"," +
                OGC_WGS84 + "," +
                "PROJECTION[\"Transverse_Mercator\"]," +
                "PARAMETER[\"latitude_of_origin\",0]," +
                "PARAMETER[\"central_meridian\"," + cm + "]," +
                "PARAMETER[\"scale_factor\",0.9996]," +
                "PARAMETER[\"false_easting\",500000]," +
                "PARAMETER[\"false_northing\",0]," +
                "UNIT[\"metre\",1]]");
        }

        ESRI_WKT = Collections.unmodifiableMap(esri);
        OGC_WKT  = Collections.unmodifiableMap(ogc);
    }

    /** 返回指定 EPSG 的 ESRI WKT（Shapefile {@code .prj}），未知返回 null。 */
    public static String esriWkt(int epsg) { return ESRI_WKT.get(epsg); }

    /** 返回指定 EPSG 的 OGC WKT（GeoPackage），未知返回 null。 */
    public static String ogcWkt(int epsg) { return OGC_WKT.get(epsg); }

    /**
     * 从 CRS 字符串解析 EPSG 代号（如 {@code "EPSG:4490"}）。
     *
     * @return EPSG 代号；无法解析返回 0
     */
    public static int parseEpsg(String crs) {
        if (crs == null || crs.isBlank()) return 0;
        String upper = crs.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("EPSG:")) {
            try { return Integer.parseInt(upper.substring(5).trim()); }
            catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    /**
     * 将 CRS 字符串解析为 Shapefile {@code .prj} 内容。
     *
     * <p>规则：
     * <ol>
     *   <li>以 WKT 关键字开头 → 原样返回（用户自行提供 WKT）</li>
     *   <li>已知 EPSG 代号 → 返回对应 ESRI WKT</li>
     *   <li>其他 → 返回 null，由调用方降级处理</li>
     * </ol>
     */
    public static String esriWktFor(String crs) {
        if (crs == null || crs.isBlank()) return null;
        String trimmed = crs.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("GEOGCS[") || upper.startsWith("PROJCS[") || upper.startsWith("COMPD_CS[")) {
            return trimmed;
        }
        return esriWkt(parseEpsg(trimmed));
    }
}
