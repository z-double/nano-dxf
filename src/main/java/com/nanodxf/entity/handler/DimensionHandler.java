package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.model.DXFContext;

/**
 * DIMENSION 实体解析器（标注）。
 *
 * <p>输出为标注插入点，文字内容存入 properties["text"]。
 * 标注几何线条通常在关联的块定义中，不在实体本身。
 *
 * <p>TODO Phase 2：完整实现。
 */
public class DimensionHandler implements EntityHandler {
    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        return null;
    }
}
