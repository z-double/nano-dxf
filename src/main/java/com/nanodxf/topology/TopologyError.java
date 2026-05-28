package com.nanodxf.topology;

import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.Geometry;

/**
 * 单条拓扑错误，由 {@link TopologyChecker} 检测并收录到 {@link TopologyReport} 中。
 */
public final class TopologyError {

    private final TopologyRule rule;
    private final CADEntity    entity;
    private final CADEntity    otherEntity;
    private final Geometry     location;
    private final String       message;

    TopologyError(TopologyRule rule, CADEntity entity, CADEntity otherEntity,
                  Geometry location, String message) {
        this.rule        = rule;
        this.entity      = entity;
        this.otherEntity = otherEntity;
        this.location    = location;
        this.message     = message;
    }

    /** 触发的拓扑规则。 */
    public TopologyRule getRule() { return rule; }

    /** 主实体（所有规则均有）。 */
    public CADEntity getEntity() { return entity; }

    /**
     * 配对实体（仅 {@link TopologyRule#DUPLICATE_ENTITY} 和
     * {@link TopologyRule#CONTOUR_CROSSING} 时非 null）。
     */
    public CADEntity getOtherEntity() { return otherEntity; }

    /**
     * 错误位置（Point 或 LineString），可直接用于 GIS 标注。
     * 部分规则（如 DANGLING_ENDPOINT）返回端点 Point，
     * CONTOUR_CROSSING 返回交叉点 Point 或 LineString。
     */
    public Geometry getLocation() { return location; }

    /** 人可读错误描述。 */
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return String.format("[%s] %s (handle=%s)", rule, message,
                entity != null ? entity.getHandle() : "?");
    }
}
