package com.nanodxf.crs;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.output.CrsRegistry;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.GeometryTransformer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 坐标投影换算：高斯克吕格 / UTM 平面坐标 ↔ 经纬度（纯 Java，无第三方投影库）。
 *
 * <p>算法采用 Snyder《Map Projections — A Working Manual》的横轴墨卡托级数展开，
 * 中央经线 ±3.5° 范围内精度优于毫米级，满足测绘 CAD 数据转换需求。
 *
 * <p><b>坐标约定</b>：与 JTS / DXF 几何一致，{@code easting} 为东坐标（X 轴），
 * {@code northing} 为北坐标（Y 轴）。测量习惯的 (x=北, y=东) 需自行交换后传入。
 *
 * <p><b>支持的 CRS</b>（EPSG 代号）：
 * <ul>
 *   <li>4326 / 4490 — WGS84 / CGCS2000 地理坐标系（经纬度，原样返回）</li>
 *   <li>4534–4554 — CGCS2000 3° 带高斯克吕格（中央经线 75°E–135°E，Y 不含带号，FE=500000）</li>
 *   <li>4513–4533 — CGCS2000 3° 带高斯克吕格（带号 25–45，Y 含带号前缀）</li>
 *   <li>4491–4512 — CGCS2000 6° 带高斯克吕格（带号 13–23，Y 含带号前缀）</li>
 *   <li>32601–32660 / 32701–32760 — WGS84 UTM 北 / 南半球</li>
 *   <li>3857 — WGS84 Pseudo-Mercator（Web 地图）</li>
 * </ul>
 *
 * <p>CGCS2000 与 WGS84 椭球差异在厘米级，本类不做基准转换（七参数），
 * 两者互转视为同一基准。需要毫米级基准转换时请使用专业投影软件。
 *
 * <pre>{@code
 * // CAD 投影坐标（EPSG:4545，中央经线 108°E）→ 经纬度
 * double[] lonLat = CrsTransform.toGeographic(500000, 3320000, "EPSG:4545");
 *
 * // 带号前缀 Y（如 36500000）自动识别 3°/6° 带
 * double[] lonLat2 = CrsTransform.toGeographicAuto(36500000, 3320000);
 *
 * // 整份图纸实体批量转 4326 后写 GeoJSON
 * List<CADEntity> wgs84 = CrsTransform.transformEntities(
 *         result.getEntities(), "EPSG:4545", "EPSG:4326");
 * }</pre>
 */
public final class CrsTransform {

    private CrsTransform() {}

    private static final double A = 6378137.0;
    private static final double F_CGCS2000 = 1.0 / 298.257222101;
    private static final double F_WGS84    = 1.0 / 298.257223563;
    private static final double MERC_RADIUS = 6378137.0;
    private static final double MERC_MAX_LAT = 85.05112878;

    /** 横轴墨卡托投影参数（椭球 + 中央经线弧度 + 假东/假北 + 比例因子）。 */
    private record TM(double a, double f, double cmRad, double fe, double fn, double k0) {}

    // -------------------------------------------------------------------------
    // EPSG 驱动的换算
    // -------------------------------------------------------------------------

    /**
     * 平面坐标 → 经纬度。
     *
     * @param easting  东坐标（米）；地理坐标系 CRS 时视为经度
     * @param northing 北坐标（米）；地理坐标系 CRS 时视为纬度
     * @param crs      源 CRS（如 {@code "EPSG:4545"}）
     * @return {@code [经度, 纬度]}（度）
     * @throws IllegalArgumentException CRS 不支持时
     */
    public static double[] toGeographic(double easting, double northing, String crs) {
        int epsg = CrsRegistry.parseEpsg(crs);
        if (epsg == 4326 || epsg == 4490) return new double[]{easting, northing};
        if (epsg == 3857) return webMercatorInverse(easting, northing);
        return tmInverse(resolveTM(epsg, crs), easting, northing);
    }

    /**
     * 经纬度 → 平面坐标。
     *
     * @param lon 经度（度）
     * @param lat 纬度（度）
     * @param crs 目标 CRS（如 {@code "EPSG:4545"}）
     * @return {@code [东坐标, 北坐标]}（米）；地理坐标系 CRS 时原样返回 {@code [lon, lat]}
     * @throws IllegalArgumentException CRS 不支持或纬度超出范围时
     */
    public static double[] fromGeographic(double lon, double lat, String crs) {
        int epsg = CrsRegistry.parseEpsg(crs);
        if (epsg == 4326 || epsg == 4490) return new double[]{lon, lat};
        if (epsg == 3857) return webMercatorForward(lon, lat);
        return tmForward(resolveTM(epsg, crs), lon, lat);
    }

    // -------------------------------------------------------------------------
    // 中央经线驱动的高斯克吕格换算（CGCS2000，k0=1，FE=500000）
    // -------------------------------------------------------------------------

    /**
     * 高斯克吕格反算（指定中央经线）。东坐标含带号前缀（≥1000000）时自动剥离。
     *
     * @param centralMeridianDeg 中央经线（度），如 108
     */
    public static double[] toGeographic(double easting, double northing, double centralMeridianDeg) {
        TM tm = new TM(A, F_CGCS2000, Math.toRadians(centralMeridianDeg), 500000, 0, 1);
        return tmInverse(tm, stripZonePrefix(easting), northing);
    }

    /** 高斯克吕格正算（指定中央经线），Y 不含带号前缀（FE=500000）。 */
    public static double[] fromGeographic(double lon, double lat, double centralMeridianDeg) {
        TM tm = new TM(A, F_CGCS2000, Math.toRadians(centralMeridianDeg), 500000, 0, 1);
        return tmForward(tm, lon, lat);
    }

    /**
     * 带号前缀自动识别反算：东坐标形如 {@code 36500000}（前两位为带号）。
     * 带号 13–23 按 6° 带（CM = 带号×6−3），带号 25–45 按 3° 带（CM = 带号×3）。
     *
     * @throws IllegalArgumentException 东坐标不含带号或带号超出中国范围时
     */
    public static double[] toGeographicAuto(double easting, double northing) {
        if (easting < 1_000_000) {
            throw new IllegalArgumentException(
                    "东坐标不含带号前缀（应 ≥ 1000000），请改用 toGeographic(e, n, crs)");
        }
        int zone = (int) (easting / 1_000_000);
        double cm;
        if (zone >= 13 && zone <= 23)      cm = zone * 6.0 - 3;
        else if (zone >= 25 && zone <= 45) cm = zone * 3.0;
        else throw new IllegalArgumentException("带号超出范围（6° 带 13–23 / 3° 带 25–45）：" + zone);
        return toGeographic(easting, northing, cm);
    }

    /**
     * 带号前缀自动识别正算：按 3° 带输出含带号前缀的东坐标（国内常用约定）。
     * 带号 = round(经度 / 3)，中央经线 = 带号×3。
     */
    public static double[] fromGeographicAuto(double lon, double lat) {
        int zone = (int) Math.round(lon / 3.0);
        double[] en = fromGeographic(lon, lat, zone * 3.0);
        return new double[]{en[0] + zone * 1_000_000.0, en[1]};
    }

    // -------------------------------------------------------------------------
    // 几何 / 实体批量转换
    // -------------------------------------------------------------------------

    /**
     * 几何坐标批量转换（逐顶点，Z 值保持不变）。
     * 离散化后的圆弧/样条按顶点转换，曲线形状在顶点密度足够时保持。
     *
     * @param fromCrs 源 CRS
     * @param toCrs   目标 CRS
     */
    public static Geometry transformGeometry(Geometry geometry, String fromCrs, String toCrs) {
        if (geometry == null) return null;
        if (normalize(fromCrs).equals(normalize(toCrs))) return geometry;
        return new GeometryTransformer() {
            @Override
            protected CoordinateSequence transformCoordinates(
                    CoordinateSequence coords, Geometry parent) {
                CoordinateSequence copy = coords.copy();
                for (int i = 0; i < copy.size(); i++) {
                    double[] lonLat = toGeographic(coords.getX(i), coords.getY(i), fromCrs);
                    double[] xy = fromGeographic(lonLat[0], lonLat[1], toCrs);
                    copy.setOrdinate(i, 0, xy[0]);
                    copy.setOrdinate(i, 1, xy[1]);
                }
                return copy;
            }
        }.transform(geometry);
    }

    /**
     * 实体列表批量转换，返回新列表（实体不可变，几何被替换，属性保留）。
     * 几何为 null 的实体原样保留。
     */
    public static List<CADEntity> transformEntities(List<CADEntity> entities,
                                                     String fromCrs, String toCrs) {
        List<CADEntity> out = new ArrayList<>(entities.size());
        for (CADEntity e : entities) {
            Geometry g = e.geometry();
            out.add(g == null ? e : e.withGeometry(transformGeometry(g, fromCrs, toCrs)));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // 横轴墨卡托正 / 反算（Snyder 级数）
    // -------------------------------------------------------------------------

    private static double[] tmInverse(TM tm, double easting, double northing) {
        double e2 = tm.f() * (2 - tm.f());
        double ep2 = e2 / (1 - e2);

        double m = (northing - tm.fn()) / tm.k0();
        double mu = m / (tm.a() * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256));

        double e1 = (1 - Math.sqrt(1 - e2)) / (1 + Math.sqrt(1 - e2));
        double e1_2 = e1 * e1, e1_3 = e1_2 * e1, e1_4 = e1_3 * e1;
        double phi1 = mu
                + (3 * e1 / 2 - 27 * e1_3 / 32) * Math.sin(2 * mu)
                + (21 * e1_2 / 16 - 55 * e1_4 / 32) * Math.sin(4 * mu)
                + (151 * e1_3 / 96) * Math.sin(6 * mu)
                + (1097 * e1_4 / 512) * Math.sin(8 * mu);

        double sinPhi1 = Math.sin(phi1), cosPhi1 = Math.cos(phi1), tanPhi1 = Math.tan(phi1);
        double c1 = ep2 * cosPhi1 * cosPhi1;
        double t1 = tanPhi1 * tanPhi1;
        double n1 = tm.a() / Math.sqrt(1 - e2 * sinPhi1 * sinPhi1);
        double r1 = tm.a() * (1 - e2) / Math.pow(1 - e2 * sinPhi1 * sinPhi1, 1.5);
        double d = (easting - tm.fe()) / (n1 * tm.k0());
        double d2 = d * d, d3 = d2 * d, d4 = d2 * d2, d5 = d4 * d, d6 = d3 * d3;

        double lat = phi1 - (n1 * tanPhi1 / r1) * (d2 / 2
                - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d4 / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d6 / 720);
        double lon = tm.cmRad() + (d
                - (1 + 2 * t1 + c1) * d3 / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d5 / 120) / cosPhi1;

        return new double[]{Math.toDegrees(lon), Math.toDegrees(lat)};
    }

    private static double[] tmForward(TM tm, double lon, double lat) {
        if (lat <= -90 || lat >= 90) {
            throw new IllegalArgumentException("纬度超出范围（-90, 90）：" + lat);
        }
        double e2 = tm.f() * (2 - tm.f());
        double ep2 = e2 / (1 - e2);

        double phi = Math.toRadians(lat);
        double lam = Math.toRadians(lon) - tm.cmRad();
        double sinPhi = Math.sin(phi), cosPhi = Math.cos(phi), tanPhi = Math.tan(phi);

        double n = tm.a() / Math.sqrt(1 - e2 * sinPhi * sinPhi);
        double t = tanPhi * tanPhi;
        double c = ep2 * cosPhi * cosPhi;
        double aa = lam * cosPhi;
        double a2 = aa * aa, a3 = a2 * aa, a4 = a2 * a2, a5 = a4 * aa, a6 = a3 * a3;

        double e2_2 = e2 * e2, e2_3 = e2_2 * e2;
        double m = tm.a() * ((1 - e2 / 4 - 3 * e2_2 / 64 - 5 * e2_3 / 256) * phi
                - (3 * e2 / 8 + 3 * e2_2 / 32 + 45 * e2_3 / 1024) * Math.sin(2 * phi)
                + (15 * e2_2 / 256 + 45 * e2_3 / 1024) * Math.sin(4 * phi)
                - (35 * e2_3 / 3072) * Math.sin(6 * phi));

        double easting = tm.fe() + tm.k0() * n * (aa
                + (1 - t + c) * a3 / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a5 / 120);
        double northing = tm.fn() + tm.k0() * (m + n * tanPhi * (a2 / 2
                + (5 - t + 9 * c + 4 * c * c) * a4 / 24
                + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a6 / 720));

        return new double[]{easting, northing};
    }

    // -------------------------------------------------------------------------
    // Web Mercator（EPSG:3857，球面）
    // -------------------------------------------------------------------------

    private static double[] webMercatorInverse(double x, double y) {
        double lon = Math.toDegrees(x / MERC_RADIUS);
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(y / MERC_RADIUS)) - Math.PI / 2);
        return new double[]{lon, lat};
    }

    private static double[] webMercatorForward(double lon, double lat) {
        double clamped = Math.max(-MERC_MAX_LAT, Math.min(MERC_MAX_LAT, lat));
        double phi = Math.toRadians(clamped);
        return new double[]{
                MERC_RADIUS * Math.toRadians(lon),
                MERC_RADIUS * Math.log(Math.tan(Math.PI / 4 + phi / 2))};
    }

    // -------------------------------------------------------------------------
    // EPSG → 投影参数
    // -------------------------------------------------------------------------

    private static TM resolveTM(int epsg, String crs) {
        // CGCS2000 3° 带（无带号前缀）：4534 = CM 75°E，每 +1 → CM +3°
        if (epsg >= 4534 && epsg <= 4554) {
            return new TM(A, F_CGCS2000, Math.toRadians(75 + (epsg - 4534) * 3), 500000, 0, 1);
        }
        // CGCS2000 3° 带（含带号前缀）：4513 = 带号 25
        if (epsg >= 4513 && epsg <= 4533) {
            int zone = epsg - 4513 + 25;
            return new TM(A, F_CGCS2000, Math.toRadians(zone * 3.0), zone * 1_000_000.0 + 500000, 0, 1);
        }
        // CGCS2000 6° 带（含带号前缀）：4491 = 带号 13
        if (epsg >= 4491 && epsg <= 4512) {
            int zone = epsg - 4491 + 13;
            return new TM(A, F_CGCS2000, Math.toRadians(zone * 6.0 - 3), zone * 1_000_000.0 + 500000, 0, 1);
        }
        // WGS84 UTM 北半球
        if (epsg >= 32601 && epsg <= 32660) {
            int zone = epsg - 32600;
            return new TM(A, F_WGS84, Math.toRadians(zone * 6.0 - 183), 500000, 0, 0.9996);
        }
        // WGS84 UTM 南半球
        if (epsg >= 32701 && epsg <= 32760) {
            int zone = epsg - 32700;
            return new TM(A, F_WGS84, Math.toRadians(zone * 6.0 - 183), 500000, 10_000_000, 0.9996);
        }
        throw new IllegalArgumentException("不支持的 CRS：" + crs
                + "（支持 EPSG:4326/4490/3857、4491–4554、32601–32660、32701–32760）");
    }

    /** 剥离东坐标的带号前缀（≥1000000 时前 1–2 位为带号）。 */
    private static double stripZonePrefix(double easting) {
        if (easting < 1_000_000) return easting;
        return easting - Math.floor(easting / 1_000_000) * 1_000_000;
    }

    private static String normalize(String crs) {
        return crs == null ? "" : crs.trim().toUpperCase(Locale.ROOT);
    }
}