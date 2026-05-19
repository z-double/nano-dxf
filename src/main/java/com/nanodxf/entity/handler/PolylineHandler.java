package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.model.DXFContext;

/**
 * POLYLINE + VERTEX + SEQEND 实体解析器（旧版多段线，R12 格式常见）。
 *
 * <p>POLYLINE 的 flag（code 70）决定类型：
 * <ul>
 *   <li>bit 1 - 闭合</li>
 *   <li>bit 8 - 3D 折线（VERTEX 有独立 Z）</li>
 *   <li>bit 16 - 多面体网格（3D mesh）</li>
 *   <li>bit 64 - 3D 多边形网格</li>
 * </ul>
 *
 * <p>解析策略：消费 VERTEX 直到遇到 SEQEND，每个 VERTEX 读取 code 10/20/30。
 * 注意：POLYLINE 的 EntityBuffer 不包含 VERTEX/SEQEND 数据，
 * 这些由 EntitiesParser 在流上直接处理（与其他 handler 不同）。
 *
 * <p>TODO Phase 2：完整实现。
 */
public class PolylineHandler implements EntityHandler {
    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        return null;
    }
}
