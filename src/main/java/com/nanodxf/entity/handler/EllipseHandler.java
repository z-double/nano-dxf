package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;

import java.util.ArrayList;
import java.util.List;

/**
 * ELLIPSE 实体解析器。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 10/20/30 - 中心点 X/Y/Z</li>
 *   <li>code 11/21/31 - 长轴端点相对中心的向量（长轴长度 = 向量模长）</li>
 *   <li>code 40 - 短轴与长轴之比（ratio = b/a，范围 [0,1]）</li>
 *   <li>code 41 - 起始参数（弧度，0 = 完整椭圆起始位置）</li>
 *   <li>code 42 - 终止参数（弧度，2π = 完整椭圆）</li>
 * </ul>
 *
 * <p>参数方程（长轴与 X 轴夹角为 θ）：
 * <pre>
 *   x = cx + a·cos(t)·cos(θ) - b·sin(t)·sin(θ)
 *   y = cy + a·cos(t)·sin(θ) + b·sin(t)·cos(θ)
 * </pre>
 *
 * <p>输出：参数域离散化为 JTS {@code LineString}（开放）或 {@link LinearRing}（完整椭圆）。
 * 采样点数取椭圆最大轴的弦高误差计算结果，最少 16 点。
 */
public class EllipseHandler implements EntityHandler {

    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");

        double cx = buffer.getDouble(10, 0);
        double cy = buffer.getDouble(20, 0);
        double cz = buffer.getDouble(30, 0);

        // 长轴向量（相对中心）
        double mx = buffer.getDouble(11, 1);
        double my = buffer.getDouble(21, 0);

        double ratio      = buffer.getDouble(40, 1.0); // b/a
        double startParam = buffer.getDouble(41, 0);
        double endParam   = buffer.getDouble(42, 2 * Math.PI);

        // 长半轴长度和旋转角
        double a     = Math.sqrt(mx * mx + my * my);
        double theta = Math.atan2(my, mx);
        double b     = a * ratio;

        if (a < 1e-12) return null;

        double span = endParam - startParam;
        if (span < 0) span += 2 * Math.PI;
        if (span < 1e-9) return null;

        // 采样点数：以最大轴的弦高误差为基准
        double rMax      = Math.max(a, b);
        double tolerance = ctx.config.getArcTolerance();
        int n = (int) Math.ceil(span / (2 * Math.acos(1.0 - tolerance / rMax)));
        n = Math.max(n, 16);

        List<Coordinate> pts = new ArrayList<>(n + 1);
        double cosTheta = Math.cos(theta), sinTheta = Math.sin(theta);
        for (int i = 0; i <= n; i++) {
            double t  = startParam + span * i / n;
            double ct = Math.cos(t), st = Math.sin(t);
            double x  = cx + a * ct * cosTheta - b * st * sinTheta;
            double y  = cy + a * ct * sinTheta + b * st * cosTheta;
            pts.add(new Coordinate(x, y, cz));
        }

        boolean isClosed = Math.abs(span - 2 * Math.PI) < 1e-6;
        Geometry geom;
        if (isClosed) {
            // 强制首尾精确相同，构建 LinearRing
            pts.set(pts.size() - 1, new Coordinate(pts.get(0)));
            geom = GeometryBuilder.factory()
                    .createLinearRing(pts.toArray(new Coordinate[0]));
        } else {
            geom = GeometryBuilder.factory()
                    .createLineString(pts.toArray(new Coordinate[0]));
        }

        return CADEntity.builder("ELLIPSE")
                .handle(handle).layer(layer).geometry(geom).build();
    }
}
