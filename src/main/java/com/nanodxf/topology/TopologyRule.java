package com.nanodxf.topology;

/**
 * 拓扑检查规则枚举。
 *
 * <p>每条规则对应一类常见的测绘数据拓扑错误，
 * 通过 {@link TopologyCheckConfig.Builder#rules} 选择启用的规则子集。
 */
public enum TopologyRule {

    /**
     * 重复实体：两个几何对象完全相同（位置和形态），在图层合并或重复采集时常见。
     * 算法：EntityIndex 近邻查询 + JTS {@code equalsExact}。
     */
    DUPLICATE_ENTITY,

    /**
     * 自相交几何：Polygon / LinearRing / LineString 的边界自身穿越。
     * 直接影响 GIS 面积计算和叠置分析的正确性。
     * 算法：JTS {@code IsValidOp}。
     */
    SELF_INTERSECTION,

    /**
     * 零长度/零面积实体：线长度或面面积低于容差阈值，为退化几何。
     * 算法：{@code geometry.getLength()} / {@code geometry.getArea()} 阈值判断。
     */
    ZERO_LENGTH,

    /**
     * 悬挂端点：线状实体的端点未与任何其他线的端点相接（孤立端），
     * 表示道路/管线/地类界存在断裂。
     * 算法：端点 STRtree 近邻查询，查不到相接端点的即为悬挂。
     */
    DANGLING_ENDPOINT,

    /**
     * 等高线交叉：同一图层内的等高线相互穿越，在真实地形中不可能发生。
     * 通常由 CASS 手工编辑错误引起，会导致 DEM 插值失败。
     * 算法：EntityIndex 候选过滤 + JTS {@code PreparedGeometry.crosses()}。
     */
    CONTOUR_CROSSING
}
