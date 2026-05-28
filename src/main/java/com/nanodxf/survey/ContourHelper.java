package com.nanodxf.survey;

import com.nanodxf.EntityProperty;
import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.util.*;

/**
 * 等高线分析工具。
 *
 * <p>从 {@link CADEntity} 列表中提取 LWPOLYLINE / POLYLINE 等高线实体，
 * 按高程分组并提供验证支持。
 *
 * <p>高程提取优先级：
 * <ol>
 *   <li>{@code properties["elevation"]}（LWPOLYLINE code 38，CASS 等软件的标准做法）</li>
 *   <li>几何顶点 Z 坐标均值（非 NaN 且非 0 时）</li>
 * </ol>
 *
 * <p>典型用法：
 * <pre>{@code
 * ContourSet cs = ContourHelper.extract(result.getEntities(), "等高线", "计曲线");
 * List<Double> bad = cs.validate(result.getMetadata().getContourInterval());
 * if (!bad.isEmpty()) {
 *     System.err.println("等高距异常的高程值：" + bad);
 * }
 * }</pre>
 */
public final class ContourHelper {

    private static final Set<String> CONTOUR_TYPES = Set.of("LWPOLYLINE", "POLYLINE");

    private ContourHelper() {}

    /**
     * 从实体列表中提取所有等高线（LWPOLYLINE / POLYLINE，不限图层），按高程分组。
     *
     * @param entities 实体列表（来自 {@code ParseResult.getEntities()}）
     * @return {@link ContourSet}
     */
    public static ContourSet extract(List<CADEntity> entities) {
        return extract(entities, (String[]) null);
    }

    /**
     * 从实体列表中提取指定图层的等高线，按高程分组。
     *
     * @param entities 实体列表
     * @param layers   图层白名单（大小写不敏感）；传 null 或空时不限图层
     * @return {@link ContourSet}
     */
    public static ContourSet extract(List<CADEntity> entities, String... layers) {
        Set<String> layerSet = buildLayerSet(layers);

        TreeMap<Double, List<CADEntity>> map = new TreeMap<>();
        for (CADEntity e : entities) {
            if (!CONTOUR_TYPES.contains(e.getType())) continue;
            if (!layerSet.isEmpty() && !layerSet.contains(upper(e.getLayer()))) continue;

            double elev = resolveElevation(e);
            if (Double.isNaN(elev)) continue;

            map.computeIfAbsent(elev, k -> new ArrayList<>()).add(e);
        }
        return new ContourSet(map);
    }

    // -------------------------------------------------------------------------

    static double resolveElevation(CADEntity e) {
        // 优先 elevation 属性（LWPOLYLINE code 38）
        Object elevObj = e.getProperties().get(EntityProperty.ELEVATION);
        if (elevObj instanceof Double d) return d;
        if (elevObj instanceof Number n) return n.doubleValue();

        // 其次顶点 Z 均值
        Geometry g = e.geometry();
        if (g == null || g.isEmpty()) return Double.NaN;
        double sum = 0;
        int cnt = 0;
        for (Coordinate c : g.getCoordinates()) {
            if (!Double.isNaN(c.z)) { sum += c.z; cnt++; }
        }
        if (cnt == 0) return Double.NaN;
        double avg = sum / cnt;
        return avg == 0.0 ? Double.NaN : avg; // Z 全为 0 视为无高程
    }

    private static Set<String> buildLayerSet(String[] layers) {
        if (layers == null || layers.length == 0) return Set.of();
        Set<String> s = new HashSet<>();
        for (String l : layers) if (l != null) s.add(l.toUpperCase());
        return s;
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase();
    }
}
