package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.model.DXFContext;

/**
 * ATTRIB 实体解析器（INSERT 块引用的属性值）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 1 - 属性值（如 "1234.56"）</li>
 *   <li>code 2 - 属性标签名（tag，如 "ELV" 表示高程属性）</li>
 *   <li>code 10/20/30 - 属性插入点</li>
 * </ul>
 *
 * <p>属性值存入父 INSERT 实体的 properties，key 为属性 tag。
 * 南方 CASS 常用 ATTRIB 存储高程点的高程值（tag = "ELV" 或类似）。
 *
 * <p>TODO Phase 2：完整实现。
 */
public class AttribHandler implements EntityHandler {
    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        return null;
    }
}
