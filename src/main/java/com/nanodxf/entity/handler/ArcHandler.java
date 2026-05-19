package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.Discretizer;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import java.util.List;

/**
 * ARC 实体解析器。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 5  - handle</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 圆心 X/Y/Z</li>
 *   <li>code 40 - 半径</li>
 *   <li>code 50 - 起始角度（度，逆时针，相对 WCS X 轴）</li>
 *   <li>code 51 - 终止角度（度）</li>
 * </ul>
 *
 * <p>输出：弧线离散化为 JTS {@link LineString}。
 * 采样点数基于弦高误差（sagitta ≤ arcTolerance）自适应计算，不低于 8 点。
 *
 * <p>注意：忽略 OCS 拉伸向量（code 210/220/230）。
 */
public class ArcHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");

        double cx = buffer.getDouble(10, 0);
        double cy = buffer.getDouble(20, 0);
        double cz = buffer.getDouble(30, 0);
        double r  = buffer.getDouble(40, 0);
        // DXF 弧线默认逆时针，起始角默认 0°，终止角默认 360°
        double startAngle = buffer.getDouble(50, 0);
        double endAngle   = buffer.getDouble(51, 360);

        if (r <= 0) return List.of();

        double tolerance = ctx.config.getArcTolerance();
        List<Coordinate> pts = Discretizer.arc(
                new double[]{cx, cy}, r, startAngle, endAngle, tolerance);

        // 将圆心 Z 赋给弧线所有点（ARC 是 2D 实体，Z 存在 code 30）
        pts.forEach(p -> p.setZ(cz));

        LineString geom = GeometryBuilder.factory()
                .createLineString(pts.toArray(new Coordinate[0]));

        return List.of(CADEntity.builder("ARC")
                .handle(handle)
                .layer(layer)
                .geometry(geom)
                .build());
    }
}
