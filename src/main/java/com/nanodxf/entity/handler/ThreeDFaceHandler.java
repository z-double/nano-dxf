package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.model.DXFContext;

/**
 * 3DFACE 实体解析器（三角面/四边面）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 10/20/30 - 第一顶点 X/Y/Z</li>
 *   <li>code 11/21/31 - 第二顶点 X/Y/Z</li>
 *   <li>code 12/22/32 - 第三顶点 X/Y/Z</li>
 *   <li>code 13/23/33 - 第四顶点 X/Y/Z（若与第三顶点相同则为三角面）</li>
 * </ul>
 *
 * <p>TODO Phase 2：完整实现。
 */
public class ThreeDFaceHandler implements EntityHandler {
    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        return null;
    }
}
