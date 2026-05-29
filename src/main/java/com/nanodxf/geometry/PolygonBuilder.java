package com.nanodxf.geometry;

import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.polygonize.Polygonizer;

import java.util.*;

/**
 * 将闭合多段线（{@link LinearRing}）重建为 {@link Polygon} 实体。
 *
 * <p>测绘图纸中建筑轮廓、地块边界、闭合等高线等通常存为 LWPOLYLINE 闭合折线，
 * 解析后几何类型为 {@link LinearRing}，无法直接进行面积量算和空间包含查询。
 * 本工具将其转换为真正的 {@link Polygon}，支持孔洞检测（内圈自动识别为洞）。
 *
 * <pre>{@code
 * // 获取建筑图层的多边形
 * List<CADEntity> polygons = PolygonBuilder.build(
 *     result.getEntities().stream()
 *         .filter(e -> "建筑".equals(e.getLayer()))
 *         .toList());
 *
 * // 计算总面积
 * double totalArea = polygons.stream()
 *     .mapToDouble(e -> e.geometry().getArea())
 *     .sum();
 * }</pre>
 */
public final class PolygonBuilder {

    private PolygonBuilder() {}

    /**
     * 将实体列表中所有 {@link LinearRing} 几何重建为 {@link Polygon}，
     * 自动识别孔洞（内圈成为外圈的洞）。
     *
     * @param entities 实体列表（含 LinearRing 几何的实体）
     * @return 新实体列表；类型改为 {@code "POLYGON"}，几何改为 {@link Polygon}；
     *         原实体的图层、handle、properties 均保留。
     *         无 LinearRing 实体时返回空列表。
     */
    public static List<CADEntity> build(List<CADEntity> entities) {
        List<CADEntity> ringEntities = entities.stream()
                .filter(e -> e.geometry() instanceof LinearRing)
                .toList();

        if (ringEntities.isEmpty()) return List.of();

        // 用 JTS Polygonizer 组合外环与孔洞
        Polygonizer polygonizer = new Polygonizer();
        for (CADEntity e : ringEntities) {
            polygonizer.add((LineString) e.geometry()); // LinearRing extends LineString
        }

        @SuppressWarnings("unchecked")
        Collection<Polygon> polys = polygonizer.getPolygons();

        if (polys.isEmpty()) {
            // Polygonizer 无法识别（如未闭合），退化为直接转换（无孔洞）
            return toSimplePolygons(ringEntities);
        }

        // 将 Polygon 与源 ringEntity 配对（按外环坐标序列匹配）
        List<CADEntity> result = new ArrayList<>(polys.size());
        for (Polygon poly : polys) {
            CADEntity src = findSource(ringEntities, poly.getExteriorRing().getCoordinates());
            String layer  = src != null ? src.getLayer()  : "0";
            String handle = src != null ? src.getHandle() : "";

            CADEntity.Builder b = CADEntity.builder("POLYGON")
                    .handle(handle).layer(layer).geometry(poly);
            if (src != null) src.getProperties().forEach(b::property);
            result.add(b.build());
        }
        return result;
    }

    /**
     * 简单转换：每个 LinearRing 直接转为无孔 Polygon（不做孔洞检测）。
     * 适合已知无嵌套关系的图层（如道路边界线）。
     */
    public static List<CADEntity> buildSimple(List<CADEntity> entities) {
        List<CADEntity> rings = entities.stream()
                .filter(e -> e.geometry() instanceof LinearRing)
                .toList();
        return toSimplePolygons(rings);
    }

    // -------------------------------------------------------------------------

    private static List<CADEntity> toSimplePolygons(List<CADEntity> rings) {
        List<CADEntity> result = new ArrayList<>(rings.size());
        for (CADEntity e : rings) {
            Polygon poly = GeometryBuilder.factory()
                    .createPolygon((LinearRing) e.geometry());
            CADEntity.Builder b = CADEntity.builder("POLYGON")
                    .handle(e.getHandle()).layer(e.getLayer()).geometry(poly);
            e.getProperties().forEach(b::property);
            result.add(b.build());
        }
        return result;
    }

    /**
     * 按外环坐标序列找到对应的源实体。
     * 匹配策略：首顶点坐标完全相等且顶点总数相等。
     */
    private static CADEntity findSource(List<CADEntity> rings, Coordinate[] extCoords) {
        for (CADEntity e : rings) {
            Coordinate[] rc = e.geometry().getCoordinates();
            if (rc.length == extCoords.length && rc[0].equals2D(extCoords[0]))
                return e;
        }
        // 退化匹配：找最近首顶点
        CADEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (CADEntity e : rings) {
            double d = e.geometry().getCoordinates()[0].distance(extCoords[0]);
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }
}
