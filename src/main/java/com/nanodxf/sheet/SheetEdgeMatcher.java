package com.nanodxf.sheet;

import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.*;

/**
 * 图幅接边检查工具。
 *
 * <p>比较两幅相邻图纸中线状要素（LineString、LinearRing）在接边带内的端点，
 * 找出未接合的裂缝（两图幅端点间距大于 0、小于等于 {@code gapTolerance}）。
 *
 * <p>接边带（edgeBand）：以接边线为中心向两侧各延伸 {@code bandWidth} 的矩形区域，
 * 仅检查在此区域内的端点，避免内部端点的误报。
 *
 * <pre>{@code
 * // 竖直接边线 x=5000，接边带宽 2.0，裂缝容差 0.5
 * SheetEdgeReport r = SheetEdgeMatcher.matchVertical(
 *     entitiesA, entitiesB, 5000.0, 2.0, 0.5);
 * System.out.println(r.summary());
 * }</pre>
 */
public final class SheetEdgeMatcher {

    private SheetEdgeMatcher() {}

    /**
     * 按竖直接边线检查（接边线为 x = edgeX）。
     *
     * @param entitiesA  左幅实体列表
     * @param entitiesB  右幅实体列表
     * @param edgeX      接边线 X 坐标
     * @param bandWidth  接边带半宽（从接边线向两侧各延伸 bandWidth）
     * @param gapTolerance 裂缝判定容差：端点间距在 (0, gapTolerance] 时记录为裂缝
     * @return 接边检查报告
     */
    public static SheetEdgeReport matchVertical(List<CADEntity> entitiesA,
                                                 List<CADEntity> entitiesB,
                                                 double edgeX,
                                                 double bandWidth,
                                                 double gapTolerance) {
        // 接边带：以 edgeX 为中心，bandWidth 为半宽
        Envelope band = new Envelope(edgeX - bandWidth, edgeX + bandWidth,
                -Double.MAX_VALUE / 2, Double.MAX_VALUE / 2);
        return match(entitiesA, entitiesB, band, gapTolerance);
    }

    /**
     * 按水平接边线检查（接边线为 y = edgeY）。
     *
     * @param entitiesA  下幅实体列表
     * @param entitiesB  上幅实体列表
     * @param edgeY      接边线 Y 坐标
     * @param bandWidth  接边带半宽
     * @param gapTolerance 裂缝判定容差
     * @return 接边检查报告
     */
    public static SheetEdgeReport matchHorizontal(List<CADEntity> entitiesA,
                                                   List<CADEntity> entitiesB,
                                                   double edgeY,
                                                   double bandWidth,
                                                   double gapTolerance) {
        Envelope band = new Envelope(-Double.MAX_VALUE / 2, Double.MAX_VALUE / 2,
                edgeY - bandWidth, edgeY + bandWidth);
        return match(entitiesA, entitiesB, band, gapTolerance);
    }

    /**
     * 通用接边检查：以任意 {@link Envelope} 定义接边带。
     *
     * @param entitiesA    图幅 A 实体列表
     * @param entitiesB    图幅 B 实体列表
     * @param band         接边带范围（Envelope）
     * @param gapTolerance 裂缝判定容差（≤0 则不报告裂缝）
     * @return 接边检查报告
     */
    public static SheetEdgeReport match(List<CADEntity> entitiesA,
                                         List<CADEntity> entitiesB,
                                         Envelope band,
                                         double gapTolerance) {
        List<EndpointRef> epsA = collectEndpoints(entitiesA, band);
        List<EndpointRef> epsB = collectEndpoints(entitiesB, band);

        // 将 B 的端点建空间索引
        STRtree tree = new STRtree();
        for (EndpointRef ep : epsB) {
            Coordinate c = ep.coord();
            tree.insert(new Envelope(c.x - gapTolerance, c.x + gapTolerance,
                            c.y - gapTolerance, c.y + gapTolerance), ep);
        }

        List<EdgeGap> gaps = new ArrayList<>();

        for (EndpointRef epA : epsA) {
            Coordinate ca = epA.coord();
            Envelope query = new Envelope(ca.x - gapTolerance, ca.x + gapTolerance,
                    ca.y - gapTolerance, ca.y + gapTolerance);
            @SuppressWarnings("unchecked")
            List<EndpointRef> candidates = tree.query(query);

            // 找最近的 B 端点
            EndpointRef nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (EndpointRef epB : candidates) {
                double d = ca.distance(epB.coord());
                if (d < nearestDist) { nearestDist = d; nearest = epB; }
            }

            if (nearest == null) {
                // 接边带内 A 端点在 B 侧无配对 —— 也是裂缝（对端未延伸到接边带）
                // 仅记录有明确 B 对应候选时的裂缝，避免噪声
            } else if (nearestDist > 0 && nearestDist <= gapTolerance) {
                gaps.add(new EdgeGap(epA.entity(), nearest.entity(), nearestDist));
            }
        }

        return new SheetEdgeReport(gaps, gapTolerance, epsA.size(), epsB.size());
    }

    // -------------------------------------------------------------------------

    private static List<EndpointRef> collectEndpoints(List<CADEntity> entities, Envelope band) {
        List<EndpointRef> result = new ArrayList<>();
        for (CADEntity e : entities) {
            Geometry g = e.geometry();
            if (!(g instanceof LineString)) continue;
            LineString ls = (LineString) g;
            addIfInBand(result, e, ls.getStartPoint().getCoordinate(), band);
            addIfInBand(result, e, ls.getEndPoint().getCoordinate(), band);
        }
        return result;
    }

    private static void addIfInBand(List<EndpointRef> list, CADEntity e,
                                     Coordinate c, Envelope band) {
        if (band.contains(c.x, c.y)) list.add(new EndpointRef(e, c));
    }

    private record EndpointRef(CADEntity entity, Coordinate coord) {}
}
