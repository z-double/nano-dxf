package com.nanodxf.entity.handler;

import java.util.List;

import com.nanodxf.EntityProperty;
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
 *   <li>code 13/23/33 - 第一定义点（线性标注：第一延伸线起点）</li>
 *   <li>code 14/24/34 - 第二定义点（线性标注：第二延伸线起点）</li>
 *   <li>code 42 - 实测值（CAD 自动计算的距离/角度数值）</li>
 *   <li>code 50 - 旋转角度（线性标注的倾斜角，度）</li>
 *   <li>code 70 - 标注类型（0=旋转，1=对齐，2=角度，3=直径，4=半径，5=角度3点，6=序列）</li>
 * </ul>
 *
 * <p>输出：标注文字中点作为 JTS {@link Point}，文字内容和测量值存入 properties。
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

        int    dimType = buffer.getInt(70, 0);
        double dimValue = buffer.getDouble(42, Double.NaN);

        // 第一、第二定义点（线性标注延伸线起点）
        double p1x = buffer.getDouble(13, Double.NaN);
        double p1y = buffer.getDouble(23, Double.NaN);
        double p2x = buffer.getDouble(14, Double.NaN);
        double p2y = buffer.getDouble(24, Double.NaN);

        double dimRotation = buffer.getDouble(50, Double.NaN);

        Point geom = GeometryBuilder.factory()
                .createPoint(new Coordinate(tx, ty, tz));

        CADEntity.Builder builder = CADEntity.builder("DIMENSION")
                .handle(handle)
                .layer(layer)
                .geometry(geom)
                .property(EntityProperty.TEXT,           text)
                .property(EntityProperty.DIMENSION_TYPE, dimType);

        if (!Double.isNaN(dimValue))
            builder.property(EntityProperty.DIMENSION_VALUE, dimValue);

        if (!Double.isNaN(p1x) && !Double.isNaN(p1y))
            builder.property(EntityProperty.DIM_POINT1, new double[]{p1x, p1y});

        if (!Double.isNaN(p2x) && !Double.isNaN(p2y))
            builder.property(EntityProperty.DIM_POINT2, new double[]{p2x, p2y});

        if (!Double.isNaN(dimRotation))
            builder.property("dimRotation", dimRotation);

        return List.of(builder.build());
    }
}
