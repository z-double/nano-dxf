package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.model.DXFContext;

/**
 * ARC 实体解析器。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 10/20/30 - 圆心 X/Y/Z</li>
 *   <li>code 40 - 半径</li>
 *   <li>code 50 - 起始角度（度，逆时针）</li>
 *   <li>code 51 - 终止角度（度）</li>
 * </ul>
 *
 * <p>输出：弧线离散化为 JTS {@code LineString}，由 {@link com.nanodxf.geometry.Discretizer#arc} 完成，
 * 采样点数基于弦高误差（sagitta ≤ arcTolerance）自适应计算，不低于 8 点。
 *
 * <p>TODO Phase 1：完整实现。
 */
public class ArcHandler implements EntityHandler {
    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        return null;
    }
}
