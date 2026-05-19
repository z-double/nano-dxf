package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.model.DXFContext;

/**
 * LINE 实体解析器。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 起点 X/Y/Z</li>
 *   <li>code 11/21/31 - 终点 X/Y/Z</li>
 *   <li>code 5  - handle</li>
 * </ul>
 *
 * <p>输出 JTS {@code LineString}。Z 坐标策略由 {@code ParseConfig.zStrategy} 决定。
 *
 * <p>TODO Phase 1：完整实现。
 */
public class LineHandler implements EntityHandler {
    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        return null;
    }
}
