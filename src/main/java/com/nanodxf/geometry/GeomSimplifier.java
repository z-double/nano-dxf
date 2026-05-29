package com.nanodxf.geometry;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.survey.ContourSet;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

import java.util.*;

/**
 * 几何简化工具（全静态方法）。
 *
 * <p>对实体的 JTS 几何做顶点抽稀，减少点数以加快显示和输出，同时保持形态逼真度。
 * 支持两种算法（见 {@link SimplifyMode}）；默认使用拓扑保持算法。
 *
 * <p>使用场景：
 * <ul>
 *   <li>等高线顶点过密（CASS 采集的等高线常含数百个顶点），SVG 输出前先简化</li>
 *   <li>大型 Shapefile 写出前降低文件体积</li>
 *   <li>GIS 叠加分析前减少几何复杂度</li>
 * </ul>
 *
 * <pre>{@code
 * // 等高线简化（0.5m 容差，拓扑保持）
 * ContourSet simplified = GeomSimplifier.simplifyContours(cs, 0.5);
 *
 * // 全部实体简化（Douglas-Peucker，速度优先）
 * List<CADEntity> slim = GeomSimplifier.simplify(entities, 0.1, SimplifyMode.DOUGLAS_PEUCKER);
 * }</pre>
 */
public final class GeomSimplifier {

    private GeomSimplifier() {}

    /**
     * 对实体列表做几何简化（默认 {@link SimplifyMode#TOPOLOGY_PRESERVING}）。
     *
     * @param entities  输入实体列表
     * @param tolerance 简化容差（坐标单位）；≤0 时原样返回
     * @return 简化后的新实体列表（Point 实体原样保留；简化后退化为空的实体被丢弃）
     */
    public static List<CADEntity> simplify(List<CADEntity> entities, double tolerance) {
        return simplify(entities, tolerance, SimplifyMode.TOPOLOGY_PRESERVING);
    }

    /**
     * 对实体列表做几何简化，指定算法模式。
     *
     * @param entities  输入实体列表
     * @param tolerance 简化容差（坐标单位）
     * @param mode      简化算法（{@link SimplifyMode#TOPOLOGY_PRESERVING} 或
     *                  {@link SimplifyMode#DOUGLAS_PEUCKER}）
     * @return 简化后的新实体列表
     */
    public static List<CADEntity> simplify(List<CADEntity> entities, double tolerance,
                                            SimplifyMode mode) {
        if (tolerance <= 0) return new ArrayList<>(entities);
        List<CADEntity> result = new ArrayList<>(entities.size());
        for (CADEntity e : entities) {
            Geometry g = e.geometry();
            if (g == null || g instanceof Point) {
                result.add(e);
                continue;
            }
            Geometry simplified = doSimplify(g, tolerance, mode);
            if (simplified == null || simplified.isEmpty()) continue;
            result.add(e.withGeometry(simplified));
        }
        return result;
    }

    /**
     * 对 {@link ContourSet} 中所有等高线做几何简化，返回新 ContourSet。
     * 简化后退化为空的等高线被丢弃；若某高程下所有等高线均退化，则该高程层级消失。
     *
     * @param cs        等高线集合
     * @param tolerance 简化容差（坐标单位）
     * @return 简化后的新 ContourSet
     */
    public static ContourSet simplifyContours(ContourSet cs, double tolerance) {
        TreeMap<Double, List<CADEntity>> map = new TreeMap<>();
        cs.byElevation().forEach((elev, list) -> {
            List<CADEntity> simplified = simplify(list, tolerance);
            if (!simplified.isEmpty()) map.put(elev, simplified);
        });
        return new ContourSet(map);
    }

    // -------------------------------------------------------------------------

    private static Geometry doSimplify(Geometry g, double tolerance, SimplifyMode mode) {
        try {
            if (mode == SimplifyMode.DOUGLAS_PEUCKER) {
                return DouglasPeuckerSimplifier.simplify(g, tolerance);
            } else {
                return TopologyPreservingSimplifier.simplify(g, tolerance);
            }
        } catch (Exception ex) {
            return g; // 简化失败时返回原始几何
        }
    }
}
