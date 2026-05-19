package com.nanodxf.geometry;

import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 曲线离散化工具：将圆弧、bulge 段、B 样条等曲线转换为折线点列。
 *
 * <p>所有方法均基于弦高误差（sagitta）自适应计算采样点数，
 * 确保曲率大处密、平直处疏。
 */
public class Discretizer {

    // -------------------------------------------------------------------------
    // 圆弧
    // -------------------------------------------------------------------------

    /**
     * 将圆弧离散化为折线点列（含首尾端点）。
     *
     * <p>采样点数公式：sagitta = r·(1 - cos(θ/2)) ≤ tolerance<br>
     * 解得 n = ceil(spanRad / (2·acos(1 - tolerance/r)))，不足 8 时取 8。
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

    // -------------------------------------------------------------------------
    // LWPOLYLINE bulge（凸度）
    // -------------------------------------------------------------------------

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
        double chord = p1.distance(p2);
        if (chord < 1e-12) return List.of(new Coordinate(p2)); // 退化

        double angle    = 4 * Math.atan(Math.abs(bulge));
        double r        = chord / (2 * Math.sin(angle / 2));
        double sagitta  = r - Math.sqrt(Math.max(0, r * r - (chord / 2) * (chord / 2)));
        double offsetLen = r - sagitta;

        double dx = p2.x - p1.x, dy = p2.y - p1.y;
        // 圆心偏移方向（bulge>0 逆时针，bulge<0 顺时针）
        double cx = (p1.x + p2.x) / 2 + Math.signum(bulge) * (-dy / chord) * offsetLen;
        double cy = (p1.y + p2.y) / 2 + Math.signum(bulge) * ( dx / chord) * offsetLen;

        double startAngle = Math.toDegrees(Math.atan2(p1.y - cy, p1.x - cx));
        double endAngle   = Math.toDegrees(Math.atan2(p2.y - cy, p2.x - cx));
        if (bulge > 0 && endAngle < startAngle) endAngle += 360;
        if (bulge < 0 && endAngle > startAngle) endAngle -= 360;

        return arc(new double[]{cx, cy}, r, startAngle, endAngle, tolerance);
    }

    // -------------------------------------------------------------------------
    // B 样条（SPLINE，de Boor 算法）
    // -------------------------------------------------------------------------

    /**
     * 将 B 样条曲线离散化为折线点列（Phase 2：均匀参数采样）。
     *
     * <p>使用 de Boor 算法求值，采样点数由控制多边形总长度与 tolerance 比值决定，
     * 最少 8 点，最多 2000 点（防止超大样条失控）。
     *
     * <p>TODO Phase 3：改为按曲率自适应采样（曲率大处密、平直处疏）。
     *
     * @param degree   阶数（group code 71）
     * @param knots    节点向量（group code 40 重复），长度 = n + degree + 2
     * @param ctrlPts  控制点坐标，每行 [x, y] 或 [x, y, z]
     * @param tolerance 弦高误差上限（用于估算采样点数）
     */
    public static List<Coordinate> spline(int degree, double[] knots,
                                          double[][] ctrlPts, double tolerance) {
        int n = ctrlPts.length - 1;
        if (n < degree || knots.length < n + degree + 2) {
            return Collections.emptyList();
        }

        // 参数域 [t_min, t_max]（取夹持节点向量的有效区间）
        double tMin = knots[degree];
        double tMax = knots[n + 1];
        if (tMax - tMin < 1e-12) return Collections.emptyList();

        // 根据控制多边形估算合适的采样点数
        double polyLen = 0;
        for (int i = 0; i < n; i++) {
            double dx = ctrlPts[i + 1][0] - ctrlPts[i][0];
            double dy = ctrlPts[i + 1][1] - ctrlPts[i][1];
            polyLen += Math.sqrt(dx * dx + dy * dy);
        }
        // 以控制多边形长度 / (tolerance * 10) 为采样数基准
        int numSamples = Math.max(8, Math.min(2000, (int) (polyLen / (tolerance * 10))));

        List<Coordinate> result = new ArrayList<>(numSamples + 1);
        for (int i = 0; i <= numSamples; i++) {
            double t  = tMin + (tMax - tMin) * (double) i / numSamples;
            double[] pt = deBoor(degree, knots, ctrlPts, t);
            double z  = pt.length > 2 ? pt[2] : 0.0;
            result.add(new Coordinate(pt[0], pt[1], z));
        }
        return result;
    }

    /**
     * de Boor 算法：在参数 {@code t} 处求 B 样条曲线上的点坐标。
     *
     * <p>算法步骤：
     * <ol>
     *   <li>二分查找节点区间 k：T[k] ≤ t &lt; T[k+1]</li>
     *   <li>初始化 d[j] = P[k-p+j]（j = 0..p）</li>
     *   <li>递推 r 次（r = 1..p）：<br>
     *       alpha = (t - T[j+k-p]) / (T[j+k-p+1] - T[j+k-p])<br>
     *       d[j] = (1-alpha)·d[j-1] + alpha·d[j]</li>
     *   <li>返回 d[p]</li>
     * </ol>
     */
    private static double[] deBoor(int p, double[] T, double[][] P, double t) {
        int n   = P.length - 1;
        int k   = findKnotSpan(p, n, T, t);
        int dims = P[0].length;

        // 初始化局部控制点副本 d[0..p]
        double[][] d = new double[p + 1][dims];
        for (int j = 0; j <= p; j++) {
            int idx = Math.max(0, Math.min(k - p + j, n)); // 防越界（k-p+j 通常 ≥0，加 max 防退化文件）
            System.arraycopy(P[idx], 0, d[j], 0, dims);
        }

        // de Boor 递推
        for (int r = 1; r <= p; r++) {
            for (int j = p; j >= r; j--) {
                int left  = j + k - p;
                int right = left + p - r + 1;
                // 防数组越界（对于格式不完整的节点向量）
                if (left < 0 || right >= T.length) continue;
                double denom = T[right] - T[left];
                double alpha = denom < 1e-12 ? 0.0 : (t - T[left]) / denom;
                for (int dim = 0; dim < dims; dim++) {
                    d[j][dim] = (1.0 - alpha) * d[j - 1][dim] + alpha * d[j][dim];
                }
            }
        }
        return d[p];
    }

    /**
     * 二分查找节点区间 k，满足 T[k] ≤ t &lt; T[k+1]。
     * 对 t = T[n+1]（参数域右端点）特殊处理，返回 n。
     */
    private static int findKnotSpan(int p, int n, double[] T, double t) {
        if (t >= T[n + 1]) return n; // 右端点特殊处理
        int lo = p, hi = n + 1, mid = (lo + hi) / 2;
        while (t < T[mid] || t >= T[mid + 1]) {
            if (t < T[mid]) hi = mid;
            else lo = mid;
            mid = (lo + hi) / 2;
        }
        return mid;
    }
}
