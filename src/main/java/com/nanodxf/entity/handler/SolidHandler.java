package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.model.DXFContext;

/**
 * SOLID 实体解析器（填充三角形或四边形）。
 *
 * <p>group code 与 3DFACE 相同（code 10-13/20-23/30-33），
 * 但顶点顺序有差异：SOLID 的第三、四点是交叉的（蝴蝶结排列），
 * 需要交换第三第四点以还原正确的多边形顶点顺序。
 *
 * <p>TODO Phase 2：完整实现。
 */
public class SolidHandler implements EntityHandler {
    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        return null;
    }
}
