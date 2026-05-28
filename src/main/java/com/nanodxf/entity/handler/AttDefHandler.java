package com.nanodxf.entity.handler;

import com.nanodxf.EntityProperty;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;

import java.util.List;

/**
 * ATTDEF（属性定义）实体解析器。
 *
 * <p>ATTDEF 定义块内的属性模板，描述属性的标签名（tag）、提示字符串（prompt）和默认值。
 * INSERT 插入该块时，每个 ATTDEF 对应一个 ATTRIB 实例（存储实际值）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 1  - 默认属性值（Default value，如 "25.30"）</li>
 *   <li>code 2  - 属性标签名（Tag，如 "ELV"）</li>
 *   <li>code 3  - 提示字符串（Prompt，如 "请输入高程"）</li>
 *   <li>code 5  - handle</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 插入点 X/Y/Z</li>
 *   <li>code 40 - 字高</li>
 *   <li>code 50 - 旋转角（度）</li>
 *   <li>code 70 - 属性标志（bit 0=不可见，bit 1=固定值，bit 2=验证，bit 3=预设）</li>
 * </ul>
 *
 * <p>输出：插入点 {@link Point}，tag / prompt / 默认值 / 标志存入 properties。
 */
public class AttDefHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle  = buffer.getString(5, "");
        String layer   = buffer.getString(8, "0");
        String defVal  = buffer.getString(1, "");
        String tag     = buffer.getString(2, "");
        String prompt  = buffer.getString(3, "");
        double x       = buffer.getDouble(10, 0);
        double y       = buffer.getDouble(20, 0);
        double z       = buffer.getDouble(30, 0);
        double height  = buffer.getDouble(40, 2.5);
        double rotation = buffer.getDouble(50, 0);
        int flags      = buffer.getInt(70, 0);

        Point geom = GeometryBuilder.factory()
                .createPoint(new Coordinate(x, y, z));

        return List.of(CADEntity.builder("ATTDEF")
                .handle(handle)
                .layer(layer)
                .geometry(geom)
                .property(EntityProperty.TAG,      tag)
                .property(EntityProperty.PROMPT,   prompt)
                .property(EntityProperty.TEXT,     defVal)
                .property(EntityProperty.HEIGHT,   height)
                .property(EntityProperty.ROTATION, rotation)
                .property("attdefFlags",            flags)
                .build());
    }
}
