package com.nanodxf.entity.handler;

import java.util.List;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;

/**
 * TEXT 实体解析器（单行文字）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 1  - 文本内容（原始字符串，未经任何清洗）</li>
 *   <li>code 5  - handle</li>
 *   <li>code 7  - 文字样式名</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 插入点 X/Y/Z（文字基点）</li>
 *   <li>code 40 - 字高</li>
 *   <li>code 50 - 旋转角度（度，逆时针）</li>
 * </ul>
 *
 * <p>输出插入点作为 JTS {@link Point}，文本内容存入 {@code properties["text"]}。
 * 同时保存字高和旋转角以便后续渲染或标注匹配使用。
 *
 * <p>注意：此处处理 TEXT（单行），多行文字（MTEXT）由 MTextHandler 负责，
 * 需要额外的格式码清洗（见 MTextHandler）。
 */
public class TextHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");

        double x    = buffer.getDouble(10, 0);
        double y    = buffer.getDouble(20, 0);
        double z    = buffer.getDouble(30, 0);
        String text = buffer.getString(1, "");
        double height   = buffer.getDouble(40, 2.5);
        double rotation = buffer.getDouble(50, 0);
        String style = buffer.getString(7, "Standard");

        Point geom = GeometryBuilder.factory()
                .createPoint(new Coordinate(x, y, z));

        return List.of(CADEntity.builder("TEXT")
                .handle(handle)
                .layer(layer)
                .geometry(geom)
                .property("text",     text)
                .property("height",   height)
                .property("rotation", rotation)
                .property("style",    style)
                .build());
    }
}
