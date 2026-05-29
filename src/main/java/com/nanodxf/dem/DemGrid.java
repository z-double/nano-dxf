package com.nanodxf.dem;

/**
 * 规则格网 DEM（Digital Elevation Model）。
 *
 * <p>数据布局：行从北（高 Y）到南（低 Y），列从西（低 X）到东（高 X）。
 * {@code data[row][col]} 中 NaN 表示无效值（NODATA）。
 *
 * <p>通常由 {@link DemBuilder} 构建，由 {@link AscGridWriter} 写出。
 */
public final class DemGrid {

    private final int    ncols;
    private final int    nrows;
    private final double xllCorner;
    private final double yllCorner;
    private final double cellSize;
    private final double noDataValue;
    private final double[][] data;    // [row][col]，row 0 = 最北行

    public DemGrid(int ncols, int nrows, double xllCorner, double yllCorner,
            double cellSize, double noDataValue, double[][] data) {
        this.ncols       = ncols;
        this.nrows       = nrows;
        this.xllCorner   = xllCorner;
        this.yllCorner   = yllCorner;
        this.cellSize    = cellSize;
        this.noDataValue = noDataValue;
        this.data        = data;
    }

    public int    getNcols()       { return ncols; }
    public int    getNrows()       { return nrows; }
    public double getXllCorner()   { return xllCorner; }
    public double getYllCorner()   { return yllCorner; }
    public double getCellSize()    { return cellSize; }
    public double getNoDataValue() { return noDataValue; }

    /**
     * 获取指定行列的高程值；行 0 为最北行，列 0 为最西列。
     * 越界时返回 {@link #getNoDataValue()}。
     */
    public double get(int row, int col) {
        if (row < 0 || row >= nrows || col < 0 || col >= ncols) return noDataValue;
        return data[row][col];
    }

    /** 内部原始数据数组（不拷贝，调用方勿修改）。 */
    double[][] rawData() { return data; }

    /**
     * 获取格网内有效格点（非 NODATA）数量。
     */
    public int validCount() {
        int cnt = 0;
        for (double[] row : data)
            for (double v : row)
                if (!Double.isNaN(v) && v != noDataValue) cnt++;
        return cnt;
    }
}
