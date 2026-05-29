package com.nanodxf.topology;

import com.nanodxf.EntityIndex;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.geometry.GeometryBuilder;
import org.locationtech.jts.geom.*;

import java.util.*;

/**
 * DXF 实体拓扑自动修复工具。
 *
 * <p>在 {@link TopologyChecker} 发现问题后，本工具可对以下 3 条规则做自动修复：
 * <ul>
 *   <li>{@link TopologyRule#DUPLICATE_ENTITY}  — 移除重复实体（保留第一个）</li>
 *   <li>{@link TopologyRule#ZERO_LENGTH}        — 移除零长度线 / 零面积面</li>
 *   <li>{@link TopologyRule#DANGLING_ENDPOINT}  — 吸附近邻端点（snap）</li>
 * </ul>
 *
 * <p>操作顺序固定：先删除（DUPLICATE → ZERO_LENGTH），再吸附（DANGLING_ENDPOINT）。
 * 所有操作均不修改原实体列表，返回新的不可变列表。
 *
 * <pre>{@code
 * TopologyFixResult result = TopologyFixer.fix(entities,
 *     TopologyFixConfig.builder()
 *         .rules(TopologyRule.DUPLICATE_ENTITY, TopologyRule.ZERO_LENGTH,
 *                TopologyRule.DANGLING_ENDPOINT)
 *         .snapTolerance(0.01)
 *         .build());
 *
 * if (result.hasChanges()) {
 *     System.out.println(result.summary());
 *     List<CADEntity> fixed = result.getEntities();
 * }
 * }</pre>
 */
public final class TopologyFixer {

    private TopologyFixer() {}

    /** 使用默认配置修复（全部 3 条可修复规则，snapTolerance=0.001）。 */
    public static TopologyFixResult fix(List<CADEntity> entities) {
        return fix(entities, TopologyFixConfig.defaults());
    }

    /**
     * 按配置修复拓扑问题。
     *
     * @param entities 原始实体列表（不修改）
     * @param config   修复配置
     * @return 修复结果（包含新实体列表和统计信息）
     */
    public static TopologyFixResult fix(List<CADEntity> entities, TopologyFixConfig config) {
        long t0 = System.currentTimeMillis();
        int originalCount = entities.size();

        List<CADEntity> working = new ArrayList<>(entities);
        Map<TopologyRule, Integer> fixed = new EnumMap<>(TopologyRule.class);

        for (TopologyRule rule : config.getRules()) {
            switch (rule) {
                case DUPLICATE_ENTITY ->
                        fixed.put(rule, removeDuplicates(working, config.getSnapTolerance()));
                case ZERO_LENGTH ->
                        fixed.put(rule, removeZeroLength(working, config.getSnapTolerance()));
                case DANGLING_ENDPOINT ->
                        fixed.put(rule, snapEndpoints(working, config.getSnapTolerance(),
                                config.getMaxFixes()));
                default -> { /* SELF_INTERSECTION / CONTOUR_CROSSING 不可自动修复 */ }
            }
        }

        return new TopologyFixResult(working, originalCount, fixed,
                System.currentTimeMillis() - t0);
    }

    // =========================================================================
    // DUPLICATE_ENTITY：移除重复实体
    // =========================================================================

    private static int removeDuplicates(List<CADEntity> entities, double tol) {
        EntityIndex index = new EntityIndex(entities);
        Set<String> toRemove = new HashSet<>();
        Set<String> reported = new HashSet<>();

        for (CADEntity e : entities) {
            if (toRemove.contains(e.getHandle())) continue;
            Geometry g = e.geometry();
            if (g == null || g.isEmpty()) continue;

            Envelope env = g.getEnvelopeInternal().copy();
            env.expandBy(tol);

            for (CADEntity other : index.query(env)) {
                if (other == e) continue;
                if (toRemove.contains(other.getHandle())) continue;
                String key = sortedKey(e.getHandle(), other.getHandle());
                if (reported.contains(key)) continue;

                Geometry og = other.geometry();
                if (og != null && g.equalsExact(og, tol)) {
                    reported.add(key);
                    toRemove.add(other.getHandle());
                }
            }
        }

        if (toRemove.isEmpty()) return 0;
        entities.removeIf(e -> toRemove.contains(e.getHandle()));
        return toRemove.size();
    }

    // =========================================================================
    // ZERO_LENGTH：移除零长度线 / 零面积面
    // =========================================================================

    private static int removeZeroLength(List<CADEntity> entities, double tol) {
        int before = entities.size();
        entities.removeIf(e -> {
            Geometry g = e.geometry();
            if (g == null || g.isEmpty()) return false;
            if (g instanceof LineString || g instanceof LinearRing)
                return g.getLength() < tol;
            if (g instanceof Polygon || g instanceof MultiPolygon)
                return g.getArea() < tol * tol;
            return false;
        });
        return before - entities.size();
    }

    // =========================================================================
    // DANGLING_ENDPOINT：端点吸附（snap near-miss endpoints）
    // =========================================================================

    /**
     * 找到距离在 (0, snapTolerance] 之间的端点对（来自不同实体），
     * 将两端点均移动到其中点，消除微小间隙。
     */
    private static int snapEndpoints(List<CADEntity> entities, double tol, int maxFixes) {
        // 收集所有线性实体的端点
        List<EndpointRef> refs = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            CADEntity e = entities.get(i);
            if (!(e.geometry() instanceof LineString ls) || ls.getNumPoints() < 2) continue;
            refs.add(new EndpointRef(i, e, ls, true));
            refs.add(new EndpointRef(i, e, ls, false));
        }
        if (refs.isEmpty()) return 0;

        // 建立端点空间索引（合成 POINT 实体，handle = "EP_<refIndex>"）
        List<CADEntity> synths = new ArrayList<>(refs.size());
        for (int i = 0; i < refs.size(); i++) {
            Coordinate c = refs.get(i).coord();
            synths.add(CADEntity.builder("POINT").layer("__ep__")
                    .handle("EP_" + i)
                    .geometry(GeometryBuilder.factory().createPoint(c)).build());
        }
        EntityIndex ptIdx = new EntityIndex(synths);

        // 对每个端点查找来自其他实体、距离在 (0, tol] 的近邻端点
        // updates: entityIndex → 修改后的 Coordinate[start, end]
        Map<Integer, Coordinate[]> updates = new HashMap<>();
        Set<String> processed = new HashSet<>();
        int count = 0;

        for (int i = 0; i < refs.size() && count < maxFixes; i++) {
            EndpointRef ref = refs.get(i);
            Coordinate c = ref.coord();
            String key1 = "EP_" + i;

            Envelope env = new Envelope(c.x - tol, c.x + tol, c.y - tol, c.y + tol);
            for (CADEntity synth : ptIdx.query(env)) {
                String key2 = synth.getHandle();
                if (key1.equals(key2)) continue;

                int j = Integer.parseInt(key2.substring(3));
                EndpointRef other = refs.get(j);
                if (other.entityIdx == ref.entityIdx) continue; // 同一实体

                String pairKey = Math.min(i, j) + "-" + Math.max(i, j);
                if (processed.contains(pairKey)) continue;

                Coordinate oc = other.coord();
                double d = c.distance(oc);
                if (d > 0 && d <= tol) {
                    processed.add(pairKey);
                    count++;

                    // 两端点均移至中点
                    Coordinate mid = new Coordinate((c.x + oc.x) / 2, (c.y + oc.y) / 2,
                            Double.isNaN(c.getZ()) ? oc.getZ() : c.getZ());

                    applyUpdate(updates, ref,   mid, (LineString) ref.entity.geometry());
                    applyUpdate(updates, other, mid, (LineString) other.entity.geometry());
                }
            }
        }

        if (updates.isEmpty()) return 0;

        // 应用更新：重建实体几何
        for (Map.Entry<Integer, Coordinate[]> entry : updates.entrySet()) {
            int idx = entry.getKey();
            Coordinate[] newPts = entry.getValue();
            CADEntity old = entities.get(idx);
            LineString oldLs = (LineString) old.geometry();
            Coordinate[] coords = oldLs.getCoordinates().clone();
            if (newPts[0] != null) coords[0] = newPts[0];
            if (newPts[1] != null) coords[coords.length - 1] = newPts[1];
            LineString newLs = GeometryBuilder.factory().createLineString(coords);
            entities.set(idx, old.withGeometry(newLs));
        }
        return count;
    }

    private static void applyUpdate(Map<Integer, Coordinate[]> updates,
                                    EndpointRef ref, Coordinate mid,
                                    LineString ls) {
        Coordinate[] arr = updates.computeIfAbsent(ref.entityIdx,
                k -> new Coordinate[]{null, null});
        if (ref.isStart) arr[0] = mid;
        else             arr[1] = mid;
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    private record EndpointRef(int entityIdx, CADEntity entity, LineString ls, boolean isStart) {
        Coordinate coord() {
            return isStart ? ls.getStartPoint().getCoordinate()
                           : ls.getEndPoint().getCoordinate();
        }
    }

    private static String sortedKey(String h1, String h2) {
        return h1.compareTo(h2) <= 0 ? h1 + "-" + h2 : h2 + "-" + h1;
    }
}
