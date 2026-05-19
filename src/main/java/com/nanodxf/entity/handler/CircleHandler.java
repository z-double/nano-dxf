package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.Discretizer;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LinearRing;

import java.util.ArrayList;
import java.util.List;

/**
 * CIRCLE 实体解析器。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 5  - handle</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 圆心 X/Y/Z</li>
 *   <li>code 40 - 半径</li>
 * </ul>
 *
 * <p>输出：360° 弧线离散化为闭合 JTS {@link LinearRing}。
 * 采样点数基于弦高误差（sagitta ≤ arcTolerance）自适应计算，不低于 8 点。
 * 末尾强制令首尾坐标完全相同，保证 JTS 闭合要求。
 *
 * <p>注意：忽略 OCS 拉伸向量（code 210/220/230）。
 */
public class CircleHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");

        double cx = buffer.getDouble(10, 0);
        double cy = buffer.getDouble(20, 0);
        double cz = buffer.getDouble(30, 0);
        double r  = buffer.getDouble(40, 0);

        if (r <= 0) return List.of(); // 无效半径

        double tolerance = ctx.config.getArcTolerance();
        // 生成 0°~360° 的离散点列
        List<Coordinate> pts = new ArrayList<>(
                Discretizer.arc(new double[]{cx, cy}, r, 0, 360, tolerance));

        // 强制令首尾坐标完全相同（arc() 末点与首点因浮点误差可能不完全相等）
        pts.set(pts.size() - 1, new Coordinate(pts.get(0)));

        // 设置 Z 坐标
        pts.forEach(p -> p.setZ(cz));

        LinearRing geom = GeometryBuilder.factory()
                .createLinearRing(pts.toArray(new Coordinate[0]));

        return List.of(CADEntity.builder("CIRCLE")
                .handle(handle)
                .layer(layer)
                .geometry(geom)
                .build());
    }
}
