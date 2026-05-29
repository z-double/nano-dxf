package com.nanodxf.dem;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 将 {@link DemGrid} 写出为 ESRI ASCII Grid（{@code .asc}）格式。
 *
 * <p>文件格式（ESRI ASCII Raster）：
 * <pre>
 * ncols         100
 * nrows         80
 * xllcorner     500000.000
 * yllcorner     3900000.000
 * cellsize      1.000
 * NODATA_value  -9999
 * 12.3 13.1 ...
 * ...
 * </pre>
 *
 * @see DemGrid
 * @see DemBuilder
 */
public final class AscGridWriter {

    private AscGridWriter() {}

    /**
     * 写出 DEM 到指定路径（UTF-8 编码）。
     *
     * @param dem  DEM 格网
     * @param path 输出文件路径
     */
    public static void write(DemGrid dem, Path path) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            write(dem, bw);
        }
    }

    /**
     * 写出 DEM 到 Writer。
     */
    public static void write(DemGrid dem, Writer out) throws IOException {
        PrintWriter pw = new PrintWriter(out);

        // --- 头部 ---
        pw.printf(Locale.ROOT, "ncols         %d%n",   dem.getNcols());
        pw.printf(Locale.ROOT, "nrows         %d%n",   dem.getNrows());
        pw.printf(Locale.ROOT, "xllcorner     %.6f%n", dem.getXllCorner());
        pw.printf(Locale.ROOT, "yllcorner     %.6f%n", dem.getYllCorner());
        pw.printf(Locale.ROOT, "cellsize      %.6f%n", dem.getCellSize());
        pw.printf(Locale.ROOT, "NODATA_value  %.0f%n", dem.getNoDataValue());

        // --- 数据行（row 0 = 最北，与 ESRI 约定一致）---
        double nd = dem.getNoDataValue();
        double[][] data = dem.rawData();
        int nrows = dem.getNrows();
        int ncols = dem.getNcols();
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < nrows; r++) {
            sb.setLength(0);
            for (int c = 0; c < ncols; c++) {
                if (c > 0) sb.append(' ');
                double v = data[r][c];
                if (Double.isNaN(v) || v == nd) {
                    sb.append((long) nd);
                } else {
                    sb.append(String.format(Locale.ROOT, "%.4f", v));
                }
            }
            pw.println(sb);
        }
        pw.flush();
    }
}
