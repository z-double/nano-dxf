package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.model.DXFContext;

/**
 * POINT 实体解析器。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 10/20/30 - 坐标 X/Y/Z（code 30 直接是高程值，测绘中最常见）</li>
 * </ul>
 *
 * <p>输出 JTS {@code Point}。Z 值存入 properties["elevation"]（当 ZStrategy 为 Z_AS_ATTRIBUTE 时）。
 *
 * <p>TODO Phase 1：完整实现。
 */
public class PointHandler implements EntityHandler {
    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        return null;
    }
}
