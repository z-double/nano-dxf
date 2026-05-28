package com.nanodxf.geometry;

import org.locationtech.jts.geom.Coordinate;

/**
 * OCS（实体坐标系）到 WCS（世界坐标系）的坐标变换工具。
 *
 * <p>DXF 中许多实体（ARC / CIRCLE / TEXT / MTEXT / POINT / LWPOLYLINE / SOLID / 3DFACE）
 * 的坐标存储在实体自身的 OCS 中，由 group code 210/220/230（拉伸向量）定义该坐标系。
 * 当拉伸向量为默认值 (0, 0, 1) 时 OCS == WCS，无需变换；否则需通过本类还原为 WCS 坐标。
 *
 * <h3>任意轴算法（Arbitrary Axis Algorithm）</h3>
 * <pre>
 * 给定已归一化的拉伸向量 N = (Nx, Ny, Nz)：
 *   若 |Nx| < 1/64 且 |Ny| < 1/64：
 *     Wx = normalize(Y_world × N)   // Y_world = (0,1,0)
 *   否则：
 *     Wx = normalize(Z_world × N)   // Z_world = (0,0,1)
 *   Wy = normalize(N × Wx)
 *   Wz = N
 *
 * OCS→WCS：WCS = ocsX·Wx + ocsY·Wy + ocsZ·Wz
 * </pre>
 */
public final class OcsTransformer {

    private static final double THRESHOLD = 1.0 / 64.0;

    private OcsTransformer() {}

    /**
     * 判断拉伸向量是否为默认值 (0, 0, 1)。
     * 为 true 时 OCS == WCS，调用方可跳过变换，零开销。
     */
    public static boolean isDefault(double nx, double ny, double nz) {
        return Math.abs(nx) < 1e-12 && Math.abs(ny) < 1e-12 && nz > 0.9999999;
    }

    /**
     * 将 OCS 坐标变换为 WCS 坐标。
     *
     * @param ocsX OCS X 坐标
     * @param ocsY OCS Y 坐标
     * @param ocsZ OCS Z 坐标（沿拉伸方向的高度）
     * @param nx   拉伸向量 X（可未归一化）
     * @param ny   拉伸向量 Y
     * @param nz   拉伸向量 Z
     * @return WCS {@link Coordinate}
     */
    public static Coordinate toWcs(double ocsX, double ocsY, double ocsZ,
                                   double nx, double ny, double nz) {
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12) return new Coordinate(ocsX, ocsY, ocsZ);
        double ex = nx / len, ey = ny / len, ez = nz / len;

        double[] Wx = buildWx(ex, ey, ez);
        double[] N  = {ex, ey, ez};
        double[] Wy = normalize(cross(N, Wx));

        return new Coordinate(
            ocsX * Wx[0] + ocsY * Wy[0] + ocsZ * ex,
            ocsX * Wx[1] + ocsY * Wy[1] + ocsZ * ey,
            ocsX * Wx[2] + ocsY * Wy[2] + ocsZ * ez
        );
    }

    /** 重载：接受 {@link Coordinate} 形式的 OCS 点。 */
    public static Coordinate toWcs(Coordinate ocs, double nx, double ny, double nz) {
        return toWcs(ocs.x, ocs.y, ocs.z, nx, ny, nz);
    }

    // -------------------------------------------------------------------------

    private static double[] buildWx(double ex, double ey, double ez) {
        if (Math.abs(ex) < THRESHOLD && Math.abs(ey) < THRESHOLD) {
            // Y_world × N = (Nz, 0, -Nx)
            return normalize(new double[]{ez, 0.0, -ex});
        } else {
            // Z_world × N = (-Ny, Nx, 0)
            return normalize(new double[]{-ey, ex, 0.0});
        }
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-12) return v;
        return new double[]{v[0] / len, v[1] / len, v[2] / len};
    }
}
