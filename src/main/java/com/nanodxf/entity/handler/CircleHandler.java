package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.model.DXFContext;

/**
 * CIRCLE 实体解析器。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 10/20/30 - 圆心 X/Y/Z</li>
 *   <li>code 40 - 半径</li>
 * </ul>
 *
 * <p>输出：360° 弧线离散化为闭合 JTS {@code LinearRing}，
 * 采样点数基于弦高误差（sagitta ≤ arcTolerance）自适应计算。
 *
 * <p>TODO Phase 1：完整实现。
 */
public class CircleHandler implements EntityHandler {
    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        return null;
    }
}
