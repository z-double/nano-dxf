package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 基于 JTS {@link STRtree} 的 CAD 实体空间索引，提供高效的空间查询和属性过滤。
 *
 * <p>索引在首次调用 {@link #query(Envelope)} 时懒建（线程安全），
 * 建树后不可变。对于无几何（{@code geometry() == null}）或空几何的实体，
 * 不加入空间索引，但仍可通过 {@link #byLayer} / {@link #byType} 枚举。
 *
 * <pre>{@code
 * ParseResult result = CADParser.parse(path);
 * EntityIndex idx = result.index();
 *
 * // 空间查询：返回与 bbox 相交的实体（候选集，需自行做精确相交判断）
 * List<CADEntity> candidates = idx.query(new Envelope(100, 200, 50, 150));
 *
 * // 属性过滤
 * List<CADEntity> roads   = idx.byLayer("道路");
 * List<CADEntity> circles = idx.byType("CIRCLE");
 *
 * // 组合：空间范围内的某图层实体
 * List<CADEntity> found = idx.query(bbox, "等高线", null);
 * }</pre>
 *
 * <p>注意：{@link #query(Envelope)} 基于包围盒相交（非精确几何相交），
 * 性能为 O(log n + k)；精确判断需调用方对结果再做 {@code geom.intersects(queryGeom)}。
 */
public class EntityIndex {

    private final List<CADEntity> all;
    private final AtomicReference<STRtree> treeRef = new AtomicReference<>();

    /** 使用指定实体列表构建索引（不立即建树）。 */
    public EntityIndex(List<CADEntity> entities) {
        this.all = List.copyOf(Objects.requireNonNull(entities, "entities"));
    }

    // -------------------------------------------------------------------------
    // 空间查询
    // -------------------------------------------------------------------------

    /**
     * 返回与 {@code bbox} 包围盒相交的所有实体（候选集，包围盒粗筛）。
     *
     * <p>首次调用时自动建树（O(n log n)）；后续调用 O(log n + k)。
     * 不保证结果顺序；不返回几何为 null 的实体（它们未加入索引）。
     *
     * @param bbox 查询包围盒（不可为 null）
     * @return 实体列表（可能为空，不可修改）
     */
    @SuppressWarnings("unchecked")
    public List<CADEntity> query(Envelope bbox) {
        Objects.requireNonNull(bbox, "bbox");
        return (List<CADEntity>) tree().query(bbox);
    }

    /**
     * 在 {@code bbox} 范围内进一步按图层和类型过滤。
     *
     * @param bbox  空间包围盒（不可为 null）
     * @param layer 图层名（null 表示不过滤）
     * @param type  实体类型（null 表示不过滤；大小写不敏感）
     * @return 过滤后的实体列表
     */
    public List<CADEntity> query(Envelope bbox, String layer, String type) {
        return query(bbox).stream()
                .filter(e -> layer == null || layer.equals(e.getLayer()))
                .filter(e -> type  == null || type.equalsIgnoreCase(e.getType()))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // 属性过滤（全量扫描，O(n)）
    // -------------------------------------------------------------------------

    /**
     * 返回所有属于指定图层的实体（精确匹配，大小写敏感）。
     *
     * @param layer 图层名
     */
    public List<CADEntity> byLayer(String layer) {
        return all.stream()
                .filter(e -> layer.equals(e.getLayer()))
                .collect(Collectors.toList());
    }

    /**
     * 返回所有指定类型的实体（大小写不敏感，如 "LINE"、"circle"）。
     *
     * @param type 实体类型字符串
     */
    public List<CADEntity> byType(String type) {
        String upper = type.toUpperCase();
        return all.stream()
                .filter(e -> upper.equals(e.getType()))
                .collect(Collectors.toList());
    }

    /** 返回实体总数（含无几何的实体）。 */
    public int size() { return all.size(); }

    /** 返回已加入空间索引的实体数（几何有效的实体）。 */
    public int indexedSize() { return tree().size(); }

    // -------------------------------------------------------------------------
    // 内部
    // -------------------------------------------------------------------------

    private STRtree tree() {
        STRtree t = treeRef.get();
        if (t != null) return t;

        STRtree built = new STRtree();
        for (CADEntity e : all) {
            Geometry g = e.geometry();
            if (g != null && !g.isEmpty()) {
                built.insert(g.getEnvelopeInternal(), e);
            }
        }
        built.build();

        treeRef.compareAndSet(null, built);
        return treeRef.get();
    }
}
