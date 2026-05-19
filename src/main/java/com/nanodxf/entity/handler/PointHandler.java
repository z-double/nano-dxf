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
 * POINT 实体解析器。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 5  - handle</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 坐标 X/Y/Z（code 30 即高程，测绘中最关键）</li>
 * </ul>
 *
 * <p>输出 JTS {@link Point}（三维）。
 * Z 值同时存入 {@code properties["elevation"]}，便于 GIS 属性查询，
 * 也便于 ZStrategy.Z_AS_ATTRIBUTE 模式下保留高程语义。
 */
public class PointHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");

        double x = buffer.getDouble(10, 0);
        double y = buffer.getDouble(20, 0);
        double z = buffer.getDouble(30, 0); // 高程值

        Point geom = GeometryBuilder.factory()
                .createPoint(new Coordinate(x, y, z));

        return List.of(CADEntity.builder("POINT")
                .handle(handle)
                .layer(layer)
                .geometry(geom)
                .property("elevation", z)
                .build());
    }
}
