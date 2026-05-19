package com.nanodxf.geometry;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

import java.util.List;

/**
 * JTS 几何对象工厂。
 *
 * <p>持有共享的 {@link GeometryFactory} 实例（SRID=0，浮点精度），
 * 提供常用几何构建方法。
 *
 * <p>不依赖任何解析状态，所有方法均为无副作用的工具方法。
 */
public class GeometryBuilder {

    /**
     * 共享 GeometryFactory，SRID=0（坐标参考系由调用方通过 ParseConfig.crs 传入，不内嵌到 JTS）。
     * 浮点精度模型：不做精度截断，保留原始坐标精度。
     */
    private static final GeometryFactory GF = new GeometryFactory(
        new PrecisionModel(PrecisionModel.FLOATING), 0
    );

    /** 返回共享 GeometryFactory 实例。 */
    public static GeometryFactory factory() { return GF; }

    /**
     * 构建圆弧 LineString。
     *
     * @param center    圆心 [cx, cy]
     * @param radius    半径
     * @param startDeg  起始角度（度）
     * @param endDeg    终止角度（度）
     * @param tolerance 弦高误差上限（控制离散化精度）
     */
    public static Geometry buildArc(double[] center, double radius,
                                    double startDeg, double endDeg,
                                    double tolerance) {
        List<Coordinate> pts = Discretizer.arc(center, radius, startDeg, endDeg, tolerance);
        return GF.createLineString(pts.toArray(new Coordinate[0]));
    }
}
