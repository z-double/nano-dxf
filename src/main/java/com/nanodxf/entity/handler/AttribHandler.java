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
 * ATTRIB 实体解析器（INSERT 块引用的属性值）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 1 - 属性值（如 "1234.56"）</li>
 *   <li>code 2 - 属性标签名（tag，如 "ELV" 表示高程）</li>
 *   <li>code 5 - handle</li>
 *   <li>code 8 - 图层名</li>
 *   <li>code 10/20/30 - 属性插入点</li>
 *   <li>code 40 - 字高</li>
 * </ul>
 *
 * <p>当 ATTRIB 跟随 INSERT（code 66=1）时，它由 {@link InsertHandler} 通过
 * {@code EntityBuffer.getChildren()} 处理，不会经过此 handler。
 * 此 handler 仅处理极少数独立出现的 ATTRIB 实体（返回类 TEXT 格式）。
 */
public class AttribHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");
        String tag    = buffer.getString(2, "");
        String value  = buffer.getString(1, "");

        double x = buffer.getDouble(10, 0);
        double y = buffer.getDouble(20, 0);
        double z = buffer.getDouble(30, 0);

        Point geom = GeometryBuilder.factory()
                .createPoint(new Coordinate(x, y, z));

        return List.of(CADEntity.builder("ATTRIB")
                .handle(handle)
                .layer(layer)
                .geometry(geom)
                .property("tag",   tag)
                .property("value", value)
                .build());
    }
}
