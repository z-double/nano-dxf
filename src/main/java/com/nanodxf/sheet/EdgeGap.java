package com.nanodxf.sheet;

import com.nanodxf.entity.CADEntity;

/**
 * 接边裂缝：两幅图中线状要素端点在接边线附近未接合。
 *
 * <p>由 {@link SheetEdgeMatcher#match} 产生（不可变）。
 */
public final class EdgeGap {

    private final CADEntity entityA;
    private final CADEntity entityB;
    private final double    distance;

    EdgeGap(CADEntity entityA, CADEntity entityB, double distance) {
        this.entityA  = entityA;
        this.entityB  = entityB;
        this.distance = distance;
    }

    /** 图幅 A 中靠近接边线的端点所属实体。 */
    public CADEntity getEntityA() { return entityA; }

    /** 图幅 B 中靠近接边线的端点所属实体。 */
    public CADEntity getEntityB() { return entityB; }

    /** 两端点之间的距离（坐标单位）。 */
    public double getDistance()   { return distance; }

    @Override
    public String toString() {
        return String.format("EdgeGap[A=%s B=%s dist=%.4f]",
                entityA.getHandle(), entityB.getHandle(), distance);
    }
}
