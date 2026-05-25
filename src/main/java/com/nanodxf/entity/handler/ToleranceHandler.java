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
 * TOLERANCE（形位公差框）实体解析器。
 *
 * <p>TOLERANCE 实体表示 GD&amp;T（几何尺寸与公差）标注符号框，
 * 常见于精密机械图纸，测绘图纸中较少但也有出现。
 *
 * <p>本解析器将插入点（code 10/20/30）作为 JTS {@link Point} 输出，
 * 公差内容字符串（code 1，包含特殊符号编码）存入 {@link EntityProperty#TEXT}，
 * 关联标注样式名（code 3）存入 properties。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 1  - 公差字符串（含 DXF 控制码，如 "{\Fgdt;n}%%v0.1"）</li>
 *   <li>code 3  - 标注样式名（DIMSTYLE）</li>
 *   <li>code 5  - handle</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 插入点（公差框左上角）</li>
 * </ul>
 */
public class ToleranceHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle    = buffer.getString(5, "");
        String layer     = buffer.getString(8, "0");
        String text      = buffer.getString(1, "");
        String dimStyle  = buffer.getString(3, "Standard");

        double x = buffer.getDouble(10, 0);
        double y = buffer.getDouble(20, 0);
        double z = buffer.getDouble(30, 0);

        Point geom = GeometryBuilder.factory().createPoint(new Coordinate(x, y, z));

        CADEntity.Builder b = CADEntity.builder("TOLERANCE")
                .handle(handle).layer(layer).geometry(geom)
                .property(EntityProperty.TEXT, text);
        if (!dimStyle.isBlank()) b.property(EntityProperty.STYLE, dimStyle);

        return List.of(b.build());
    }
}
