package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.model.DXFContext;

/**
 * TEXT 实体解析器（单行文字）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 1  - 文本内容</li>
 *   <li>code 10/20/30 - 插入点 X/Y/Z</li>
 *   <li>code 40 - 字高</li>
 *   <li>code 50 - 旋转角度</li>
 *   <li>code 7  - 文字样式名</li>
 * </ul>
 *
 * <p>文本内容存入 properties["text"]，插入点作为 JTS {@code Point}。
 *
 * <p>TODO Phase 1：完整实现。
 */
public class TextHandler implements EntityHandler {
    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        return null;
    }
}
