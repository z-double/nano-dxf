package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.model.DXFContext;

/**
 * HATCH 实体解析器（填充/剖面线，含内边界洞）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 91 - 边界路径数量</li>
 *   <li>code 92 - 边界路径类型（bit 0=外边界，bit 1=外边，bit 4=内边界/洞）</li>
 *   <li>code 93 - 该路径的边段数量</li>
 *   <li>code 72 - 边段类型（1=直线，2=圆弧，3=椭圆弧，4=样条）</li>
 * </ul>
 *
 * <p>外边界（pathType bit 0=1）构建 LinearRing 作为 shell，
 * 内边界构建 holes，最终组装为 JTS {@code Polygon}。
 * 绕行方向需统一：外环逆时针（CCW），内环顺时针（CW）。
 *
 * <p>TODO Phase 2：完整实现（中风险，四种边段类型各需实现）。
 */
public class HatchHandler implements EntityHandler {
    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        return null;
    }
}
