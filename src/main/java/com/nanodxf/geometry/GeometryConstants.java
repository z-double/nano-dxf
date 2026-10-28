package com.nanodxf.geometry;

/**
 * 几何计算的公共容差常量。
 *
 * <p>集中管理数值比较阈值，避免各处以裸字面量（1e-12 / 1e-9 / 1e-10）散落，
 * 造成同名不同义或同义不同名的维护问题。
 */
public final class GeometryConstants {

    private GeometryConstants() {}

    /** 零长度 / 退化几何判定的通用极小值。 */
    public static final double EPS = 1e-12;

    /** 角度、旋转量、缩放因子比较容差。 */
    public static final double ANGLE_EPS = 1e-9;

    /** LWPOLYLINE 凸度（bulge）视为直线的阈值。 */
    public static final double BULGE_EPS = 1e-10;

    /** 角度跨度是否构成闭合圆的容差（弧度）。 */
    public static final double SPAN_EPS = 1e-6;

    /** 写出样条时节点/控制点公差（DXF group code 42/43 固定值）。 */
    public static final double SPLINE_TOLERANCE = 1e-10;
}
