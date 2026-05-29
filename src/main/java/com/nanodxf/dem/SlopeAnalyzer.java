package com.nanodxf.dem;

/**
 * 基于规则格网 DEM 计算坡度和坡向（Horn's method，3×3 邻域有限差分）。
 *
 * <p>Horn's method 使用 8 个邻域格点对中心格点做加权有限差分，
 * 精度优于简单中心差分，是 ESRI ArcGIS 坡度分析的标准算法。
 *
 * <pre>{@code
 * DemGrid dem = DemBuilder.build(contourSet, 1.0);
 * SlopeGrid sg = SlopeAnalyzer.analyze(dem);
 * System.out.printf("平均坡度: %.2f°%n", sg.meanSlope());
 * }</pre>
 *
 * @see DemGrid
 * @see SlopeGrid
 */
public final class SlopeAnalyzer {

    private SlopeAnalyzer() {}

    /**
     * 对整个 DEM 格网计算坡度和坡向。
     *
     * @param dem 输入 DEM
     * @return 坡度/坡向格网（与 dem 等大小）
     */
    public static SlopeGrid analyze(DemGrid dem) {
        int nrows = dem.getNrows();
        int ncols = dem.getNcols();
        double cs = dem.getCellSize();
        double nd = dem.getNoDataValue();

        double[][] slope  = new double[nrows][ncols];
        double[][] aspect = new double[nrows][ncols];

        for (int r = 0; r < nrows; r++) {
            for (int c = 0; c < ncols; c++) {
                double center = dem.get(r, c);
                if (Double.isNaN(center) || center == nd) {
                    slope[r][c]  = Double.NaN;
                    aspect[r][c] = Double.NaN;
                    continue;
                }

                // 3×3 邻域（缺失格点用中心值填充，边界格点退化处理）
                double a = safe(dem, r - 1, c - 1, center, nd);
                double b = safe(dem, r - 1, c,     center, nd);
                double d_val = safe(dem, r - 1, c + 1, center, nd);
                double e = safe(dem, r,     c - 1, center, nd);
                double f = safe(dem, r,     c + 1, center, nd);
                double g = safe(dem, r + 1, c - 1, center, nd);
                double h = safe(dem, r + 1, c,     center, nd);
                double i = safe(dem, r + 1, c + 1, center, nd);

                // Horn's method 权重差分
                double dzdx = ((d_val + 2 * f + i) - (a + 2 * e + g)) / (8 * cs);
                double dzdy = ((g + 2 * h + i) - (a + 2 * b + d_val)) / (8 * cs);

                double slopeRad  = Math.atan(Math.sqrt(dzdx * dzdx + dzdy * dzdy));
                slope[r][c] = Math.toDegrees(slopeRad);

                // 坡向：正北为 0°，顺时针。平地（坡度 < 0.001°）标 -1
                if (slope[r][c] < 0.001) {
                    aspect[r][c] = -1.0;
                } else {
                    double aspectRad = Math.atan2(dzdx, dzdy);  // atan2(E-W, N-S)
                    double deg = Math.toDegrees(aspectRad);
                    if (deg < 0) deg += 360.0;
                    aspect[r][c] = deg;
                }
            }
        }
        return new SlopeGrid(dem, slope, aspect);
    }

    // -------------------------------------------------------------------------

    /** 安全读取 DEM 格点；越界或 NODATA 时返回 fallback。 */
    private static double safe(DemGrid dem, int r, int c, double fallback, double nd) {
        if (r < 0 || r >= dem.getNrows() || c < 0 || c >= dem.getNcols()) return fallback;
        double v = dem.get(r, c);
        return (Double.isNaN(v) || v == nd) ? fallback : v;
    }
}
