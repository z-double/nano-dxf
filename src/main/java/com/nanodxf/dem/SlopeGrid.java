package com.nanodxf.dem;

/**
 * 坡度 / 坡向格网（由 {@link SlopeAnalyzer} 产生）。
 *
 * <p>数据布局与来源 {@link DemGrid} 相同（行从北到南，列从西到东）。
 * <ul>
 *   <li>{@code slope[r][c]}  — 坡度（度），范围 [0, 90]；NODATA 格为 NaN</li>
 *   <li>{@code aspect[r][c]} — 坡向（度），正北为 0，顺时针；平地（坡度≈0）标记为 -1；NODATA 为 NaN</li>
 * </ul>
 */
public final class SlopeGrid {

    private final DemGrid  source;
    private final double[][] slope;
    private final double[][] aspect;

    SlopeGrid(DemGrid source, double[][] slope, double[][] aspect) {
        this.source = source;
        this.slope  = slope;
        this.aspect = aspect;
    }

    /** 来源 DEM。 */
    public DemGrid getSource() { return source; }

    /**
     * 坡度（度），[0, 90]；NODATA 格为 NaN。
     * 行 0 = 最北行，列 0 = 最西列。
     */
    public double getSlope(int row, int col)  { return slope[row][col]; }

    /**
     * 坡向（度），正北 = 0，顺时针；平地（坡度近似 0）= -1；NODATA = NaN。
     */
    public double getAspect(int row, int col) { return aspect[row][col]; }

    public int getNrows() { return source.getNrows(); }
    public int getNcols() { return source.getNcols(); }

    /** 统计坡度均值（排除 NODATA）。 */
    public double meanSlope() {
        double sum = 0; int cnt = 0;
        for (double[] row : slope)
            for (double v : row)
                if (!Double.isNaN(v)) { sum += v; cnt++; }
        return cnt == 0 ? Double.NaN : sum / cnt;
    }
}
