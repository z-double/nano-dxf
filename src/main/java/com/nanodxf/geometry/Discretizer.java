package com.nanodxf.geometry;

import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;

/**
 * 曲线离散化工具：将圆弧、bulge 段、样条等曲线转换为折线点列。
 *
 * <p>所有方法均基于弦高误差（sagitta）自适应计算采样点数，
 * 而非均匀分割，确保曲率大处密、平直处疏。
 */
public class Discretizer {

    /**
     * 将圆弧离散化为折线点列。
     *
     * <p>采样点数公式：sagitta = r * (1 - cos(θ/2)) ≤ tolerance，
     * 解得 n = ceil(spanRad / 2*acos(1 - tolerance/r))，最少 8 点。
     *
     * @param center    圆心坐标 [cx, cy]
     * @param r         半径
     * @param startDeg  起始角度（度，逆时针）
     * @param endDeg    终止角度（度）
     * @param tolerance 弦高误差上限（与坐标单位相同）
     */
    public static List<Coordinate> arc(double[] center, double r,
                                       double startDeg, double endDeg,
                                       double tolerance) {
        double startRad = Math.toRadians(startDeg);
        double endRad   = Math.toRadians(endDeg);
        double spanRad  = endRad - startRad;
        if (spanRad < 0) spanRad += 2 * Math.PI;

        int n = (int) Math.ceil(spanRad / (2 * Math.acos(1.0 - tolerance / r)));
        n = Math.max(n, 8);

        List<Coordinate> pts = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            double angle = startRad + spanRad * (double) i / n;
            pts.add(new Coordinate(center[0] + r * Math.cos(angle),
                                   center[1] + r * Math.sin(angle)));
        }
        return pts;
    }

    /**
     * 将 LWPOLYLINE 的 bulge 段（圆弧线段）离散化。
     *
     * <p>bulge = tan(夹角/4)，正值逆时针，负值顺时针。
     * 先从 bulge 值还原圆弧参数（圆心、半径、起终角），再调用 {@link #arc}。
     *
     * @param p1      线段起点
     * @param p2      线段终点
     * @param bulge   凸度值（非 0）
     * @param tolerance 弦高误差上限
     */
    public static List<Coordinate> bulge(Coordinate p1, Coordinate p2,
                                         double bulge, double tolerance) {
        double angle = 4 * Math.atan(Math.abs(bulge));
        double chord = p1.distance(p2);
        if (chord < 1e-12) return List.of(p2); // 退化：起终点重合
        double r = chord / (2 * Math.sin(angle / 2));

        // 从 bulge 符号和几何关系推算圆心
        double dx = p2.x - p1.x;
        double dy = p2.y - p1.y;
        double d  = chord;
        double sagitta = r - Math.sqrt(Math.max(0, r * r - (d / 2) * (d / 2)));
        // 圆心在弦的法线方向偏移 (r - sagitta)
        double offsetLen = r - sagitta;
        double cx = (p1.x + p2.x) / 2 + Math.signum(bulge) * (-dy / d) * offsetLen;
        double cy = (p1.y + p2.y) / 2 + Math.signum(bulge) * ( dx / d) * offsetLen;

        double startAngle = Math.toDegrees(Math.atan2(p1.y - cy, p1.x - cx));
        double endAngle   = Math.toDegrees(Math.atan2(p2.y - cy, p2.x - cx));
        if (bulge > 0 && endAngle < startAngle) endAngle += 360;
        if (bulge < 0 && endAngle > startAngle) endAngle -= 360;

        return arc(new double[]{cx, cy}, r, startAngle, endAngle, tolerance);
    }

    /**
     * 样条曲线（SPLINE）离散化占位方法。
     * TODO Phase 2：实现 de Boor 算法，按曲率自适应采样。
     *
     * @param degree   阶数（group code 71）
     * @param knots    节点向量（group code 40 重复）
     * @param ctrlPts  控制点坐标 [[x,y,z], ...]（group code 10/20/30 重复）
     * @param tolerance 弦高误差上限
     */
    public static List<Coordinate> spline(int degree, double[] knots,
                                          double[][] ctrlPts, double tolerance) {
        throw new UnsupportedOperationException("Spline discretization not yet implemented (Phase 2)");
    }
}
