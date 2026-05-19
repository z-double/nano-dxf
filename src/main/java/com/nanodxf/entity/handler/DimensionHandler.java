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
 * DIMENSION 实体解析器（线性、角度、半径等标注）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 1  - 标注文字（"&lt;&gt;" 表示由 CAD 自动计算）</li>
 *   <li>code 2  - 关联的标注块名</li>
 *   <li>code 5  - handle</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 定义点（起点或锚点，含义随标注类型变化）</li>
 *   <li>code 11/21/31 - 标注文字中点（文字的几何中心位置）</li>
 *   <li>code 70 - 标注类型（0=旋转，1=对齐，2=角度，3=直径，4=半径，5=角度3点，6=序列）</li>
 * </ul>
 *
 * <p>输出：标注文字中点作为 JTS {@link Point}，文字内容存入 properties。
 * 标注的测量线等图形通常存在关联的标注块中，不直接解析。
 */
public class DimensionHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");
        String text   = buffer.getString(1, "<>"); // "<>" = 自动计算值

        // 优先使用标注文字中点（code 11/21/31），无则退用定义点（code 10/20/30）
        double tx = buffer.getDouble(11, buffer.getDouble(10, 0));
        double ty = buffer.getDouble(21, buffer.getDouble(20, 0));
        double tz = buffer.getDouble(31, buffer.getDouble(30, 0));

        int dimType = buffer.getInt(70, 0);

        Point geom = GeometryBuilder.factory()
                .createPoint(new Coordinate(tx, ty, tz));

        return List.of(CADEntity.builder("DIMENSION")
                .handle(handle)
                .layer(layer)
                .geometry(geom)
                .property("text",    text)
                .property("dimType", dimType)
                .build());
    }
}
