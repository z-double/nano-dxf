package com.nanodxf.topology;

import com.nanodxf.EntityIndex;
import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;

import java.util.*;

/**
 * DXF 实体拓扑检查工具。
 *
 * <p>支持 5 条规则（见 {@link TopologyRule}），通过 {@link TopologyCheckConfig} 按需选择。
 * 全部算法均使用 JTS 和 {@link EntityIndex}（STRtree），无 O(n²) 暴力运算。
 *
 * <pre>{@code
 * TopologyReport report = TopologyChecker.check(result.getEntities(),
 *     TopologyCheckConfig.builder()
 *         .rules(TopologyRule.DUPLICATE_ENTITY, TopologyRule.CONTOUR_CROSSING)
 *         .contourLayers("等高线", "计曲线")
 *         .snapTolerance(0.01)
 *         .build());
 *
 * if (!report.isValid()) {
 *     report.getErrors().forEach(e -> System.err.println(e));
 * }
 * }</pre>
 */
public final class TopologyChecker {

    private static final Set<String> LINE_TYPES = Set.of("LWPOLYLINE", "POLYLINE", "LINE");

    private TopologyChecker() {}

    /**
     * 对实体列表执行拓扑检查，使用默认配置（全部 5 条规则）。
     */
    public static TopologyReport check(List<CADEntity> entities) {
        return check(entities, TopologyCheckConfig.defaults());
    }

    /**
     * 对实体列表执行拓扑检查。
     *
     * @param entities 实体列表（来自 {@code ParseResult.getEntities()}）
     * @param config   检查配置
     * @return 拓扑检查报告
     */
    public static TopologyReport check(List<CADEntity> entities, TopologyCheckConfig config) {
        long t0 = System.currentTimeMillis();
        List<TopologyError> errors = new ArrayList<>();
        EntityIndex index = new EntityIndex(entities);

        for (TopologyRule rule : config.getRules()) {
            if (errors.size() >= config.getMaxErrors()) break;
            switch (rule) {
                case DUPLICATE_ENTITY    -> checkDuplicates(entities, index, config, errors);
                case SELF_INTERSECTION   -> checkSelfIntersections(entities, config, errors);
                case ZERO_LENGTH         -> checkZeroLength(entities, config, errors);
                case DANGLING_ENDPOINT   -> checkDanglingEndpoints(entities, config, errors);
                case CONTOUR_CROSSING    -> checkContourCrossings(entities, config, errors);
            }
        }

        long ms = System.currentTimeMillis() - t0;
        return new TopologyReport(errors, ms);
    }

    // =========================================================================
    // DUPLICATE_ENTITY
    // =========================================================================

    private static void checkDuplicates(List<CADEntity> entities, EntityIndex index,
                                        TopologyCheckConfig cfg, List<TopologyError> errors) {
        Set<String> reported = new HashSet<>(); // "handleA-handleB" 已报告的对

        for (CADEntity e : entities) {
            if (errors.size() >= cfg.getMaxErrors()) return;
            Geometry g = e.geometry();
            if (g == null || g.isEmpty()) continue;

            Envelope env = g.getEnvelopeInternal();
            env.expandBy(cfg.getSnapTolerance());

            for (CADEntity other : index.query(env)) {
                if (other == e) continue;
                String key = sortedKey(e.getHandle(), other.getHandle());
                if (reported.contains(key)) continue;

                Geometry og = other.geometry();
                if (og != null && g.equalsExact(og, cfg.getSnapTolerance())) {
                    reported.add(key);
                    errors.add(new TopologyError(
                            TopologyRule.DUPLICATE_ENTITY, e, other,
                            g.getCentroid(),
                            String.format("重复实体：handle=%s 与 handle=%s 几何完全相同（图层=%s）",
                                    e.getHandle(), other.getHandle(), e.getLayer())));
                }
            }
        }
    }

    // =========================================================================
    // SELF_INTERSECTION
    // =========================================================================

    private static void checkSelfIntersections(List<CADEntity> entities,
                                               TopologyCheckConfig cfg,
                                               List<TopologyError> errors) {
        for (CADEntity e : entities) {
            if (errors.size() >= cfg.getMaxErrors()) return;
            Geometry g = e.geometry();
            if (g == null || g.isEmpty()) continue;
            if (!(g instanceof Polygon || g instanceof LinearRing || g instanceof LineString)) continue;

            IsValidOp validOp = new IsValidOp(g);
            if (!validOp.isValid()) {
                TopologyValidationError ve = validOp.getValidationError();
                Geometry loc = ve != null
                        ? g.getFactory().createPoint(ve.getCoordinate())
                        : g.getCentroid();
                errors.add(new TopologyError(
                        TopologyRule.SELF_INTERSECTION, e, null, loc,
                        String.format("自相交几何：%s（图层=%s）",
                                ve != null ? ve.getMessage() : "无效几何", e.getLayer())));
            }
        }
    }

    // =========================================================================
    // ZERO_LENGTH
    // =========================================================================

    private static void checkZeroLength(List<CADEntity> entities,
                                        TopologyCheckConfig cfg,
                                        List<TopologyError> errors) {
        double tol = cfg.getSnapTolerance();
        for (CADEntity e : entities) {
            if (errors.size() >= cfg.getMaxErrors()) return;
            Geometry g = e.geometry();
            if (g == null || g.isEmpty()) continue;

            boolean degenerate = false;
            if (g instanceof LineString || g instanceof LinearRing) {
                degenerate = g.getLength() < tol;
            } else if (g instanceof Polygon || g instanceof MultiPolygon) {
                degenerate = g.getArea() < tol * tol;
            }

            if (degenerate) {
                errors.add(new TopologyError(
                        TopologyRule.ZERO_LENGTH, e, null, g.getCentroid(),
                        String.format("零长度/零面积实体：length=%.6f（图层=%s）",
                                g.getLength(), e.getLayer())));
            }
        }
    }

    // =========================================================================
    // DANGLING_ENDPOINT
    // =========================================================================

    private static void checkDanglingEndpoints(List<CADEntity> entities,
                                               TopologyCheckConfig cfg,
                                               List<TopologyError> errors) {
        Set<String> targetLayers = cfg.getLineConnectLayers();
        GeometryFactory gf = new GeometryFactory();

        List<CADEntity> lineEntities = entities.stream()
                .filter(e -> LINE_TYPES.contains(e.getType()))
                .filter(e -> targetLayers.isEmpty() || targetLayers.contains(upper(e.getLayer())))
                .filter(e -> e.geometry() instanceof LineString ls && ls.getNumPoints() >= 2)
                .toList();

        if (lineEntities.isEmpty()) return;

        // 建端点空间索引：每个实体用 "EP_<index>" 作为合成点的 handle，
        // 同一实体的两个端点共享同一 pseudo-handle，便于后续排除自身。
        List<CADEntity> synths = new ArrayList<>();
        for (int i = 0; i < lineEntities.size(); i++) {
            CADEntity e = lineEntities.get(i);
            String ph = "EP_" + i;
            LineString ls = (LineString) e.geometry();
            synths.add(CADEntity.builder("POINT").layer("__ep__").handle(ph)
                    .geometry(gf.createPoint(ls.getStartPoint().getCoordinate())).build());
            synths.add(CADEntity.builder("POINT").layer("__ep__").handle(ph)
                    .geometry(gf.createPoint(ls.getEndPoint().getCoordinate())).build());
        }
        EntityIndex ptIndex = new EntityIndex(synths);

        double tol = cfg.getSnapTolerance();
        Set<String> reported = new HashSet<>();

        for (int i = 0; i < lineEntities.size(); i++) {
            if (errors.size() >= cfg.getMaxErrors()) return;
            CADEntity e = lineEntities.get(i);
            String ph = "EP_" + i;
            LineString ls = (LineString) e.geometry();
            checkEndpoint(ls.getStartPoint().getCoordinate(), e, ph, ptIndex, tol, reported, errors);
            if (errors.size() < cfg.getMaxErrors())
                checkEndpoint(ls.getEndPoint().getCoordinate(), e, ph, ptIndex, tol, reported, errors);
        }
    }

    private static void checkEndpoint(Coordinate c, CADEntity owner, String ownerPseudoHandle,
                                      EntityIndex ptIndex, double tol, Set<String> reported,
                                      List<TopologyError> errors) {
        GeometryFactory gf = new GeometryFactory();
        Envelope env = new Envelope(c.x - tol, c.x + tol, c.y - tol, c.y + tol);
        List<CADEntity> near = ptIndex.query(env);

        // 有来自其他实体（不同 pseudo-handle）的端点在 snapTolerance 内 → 非悬挂
        boolean hasNeighbor = near.stream()
                .anyMatch(n -> {
                    Coordinate nc = ((Point) n.geometry()).getCoordinate();
                    return nc.distance(c) <= tol && !ownerPseudoHandle.equals(n.getHandle());
                });

        if (!hasNeighbor) {
            String key = owner.getHandle() + "@" + Math.round(c.x * 1000) + "," + Math.round(c.y * 1000);
            if (reported.add(key)) {
                errors.add(new TopologyError(
                        TopologyRule.DANGLING_ENDPOINT, owner, null,
                        gf.createPoint(c),
                        String.format("悬挂端点：(%.4f, %.4f)（图层=%s）", c.x, c.y, owner.getLayer())));
            }
        }
    }

    // =========================================================================
    // CONTOUR_CROSSING
    // =========================================================================

    private static void checkContourCrossings(List<CADEntity> entities,
                                              TopologyCheckConfig cfg,
                                              List<TopologyError> errors) {
        Set<String> targetLayers = cfg.getContourLayers();

        List<CADEntity> contours = entities.stream()
                .filter(e -> e.getType().equals("LWPOLYLINE") || e.getType().equals("POLYLINE"))
                .filter(e -> targetLayers.isEmpty() || targetLayers.contains(upper(e.getLayer())))
                .filter(e -> e.geometry() instanceof LineString)
                .toList();

        if (contours.size() < 2) return;

        EntityIndex idx = new EntityIndex(contours);
        Set<String> reported = new HashSet<>();

        for (CADEntity e : contours) {
            if (errors.size() >= cfg.getMaxErrors()) return;
            Geometry g = e.geometry();
            if (g == null || g.isEmpty()) continue;

            PreparedGeometry pg = PreparedGeometryFactory.prepare(g);
            List<CADEntity> candidates = idx.query(g.getEnvelopeInternal());

            for (CADEntity other : candidates) {
                if (other == e) continue;
                String key = sortedKey(e.getHandle(), other.getHandle());
                if (reported.contains(key)) continue;

                Geometry og = other.geometry();
                if (og == null) continue;
                if (pg.crosses(og)) {
                    reported.add(key);
                    Geometry intersection;
                    try { intersection = g.intersection(og); }
                    catch (Exception ex) { intersection = g.getCentroid(); }

                    errors.add(new TopologyError(
                            TopologyRule.CONTOUR_CROSSING, e, other,
                            intersection.getGeometryN(0).getCentroid(),
                            String.format("等高线交叉：图层=%s handle=%s × handle=%s",
                                    e.getLayer(), e.getHandle(), other.getHandle())));
                }
            }
        }
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    private static String sortedKey(String h1, String h2) {
        return h1.compareTo(h2) <= 0 ? h1 + "-" + h2 : h2 + "-" + h1;
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase();
    }
}
