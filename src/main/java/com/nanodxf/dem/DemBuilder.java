package com.nanodxf.dem;

import com.nanodxf.survey.ContourSet;
import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.triangulate.DelaunayTriangulationBuilder;

import java.util.*;

/**
 * 由等高线集合构建规则格网 DEM。
 *
 * <p>算法：
 * <ol>
 *   <li>从等高线顶点采样离散点（点的 Z 值 = 高程）</li>
 *   <li>用 JTS {@link DelaunayTriangulationBuilder} 构建 Delaunay TIN</li>
 *   <li>对每个格点查找所在三角形并做重心插值</li>
 *   <li>格点在 TIN 外时标记为 NODATA</li>
 * </ol>
 *
 * <pre>{@code
 * DemGrid dem = DemBuilder.build(contourSet, 1.0);   // 格距 1 坐标单位
 * AscGridWriter.write(dem, Path.of("dem.asc"));
 * }</pre>
 */
public final class DemBuilder {

    private DemBuilder() {}

    /**
     * 以默认参数（NODATA = -9999）构建 DEM。
     *
     * @param cs       等高线集合（顶点 Z 坐标须已赋高程值）
     * @param cellSize 格距（坐标单位）
     * @return 规则格网 DEM
     */
    public static DemGrid build(ContourSet cs, double cellSize) {
        return build(cs, cellSize, -9999.0);
    }

    /**
     * 构建 DEM。
     *
     * @param cs          等高线集合
     * @param cellSize    格距
     * @param noDataValue NODATA 标记值
     * @return 规则格网 DEM
     */
    public static DemGrid build(ContourSet cs, double cellSize, double noDataValue) {
        // 1. 收集所有带高程的采样点（X, Y, Z）
        List<Coordinate> pts = collectPoints(cs);
        if (pts.isEmpty()) throw new IllegalArgumentException("等高线集合中无可用采样点");

        // 2. 计算范围
        double minX = pts.stream().mapToDouble(c -> c.x).min().orElseThrow();
        double maxX = pts.stream().mapToDouble(c -> c.x).max().orElseThrow();
        double minY = pts.stream().mapToDouble(c -> c.y).min().orElseThrow();
        double maxY = pts.stream().mapToDouble(c -> c.y).max().orElseThrow();

        int ncols = (int) Math.ceil((maxX - minX) / cellSize) + 1;
        int nrows = (int) Math.ceil((maxY - minY) / cellSize) + 1;

        // 3. 构建 Delaunay TIN（仅用 XY；Z 保存在 Coordinate.z）
        GeometryFactory gf = new GeometryFactory();
        MultiPoint mp = gf.createMultiPointFromCoords(pts.toArray(new Coordinate[0]));
        DelaunayTriangulationBuilder builder = new DelaunayTriangulationBuilder();
        builder.setSites(mp);
        Geometry tins = builder.getTriangles(gf);   // GeometryCollection of Polygons

        // 4. 为每个格点做插值
        double[][] data = new double[nrows][ncols];
        for (double[] row : data) Arrays.fill(row, noDataValue);

        for (int r = 0; r < nrows; r++) {
            double y = minY + (nrows - 1 - r) * cellSize;   // row 0 = 最北
            for (int c = 0; c < ncols; c++) {
                double x = minX + c * cellSize;
                Point p = gf.createPoint(new Coordinate(x, y));
                double z = interpolate(tins, p, pts);
                if (!Double.isNaN(z)) data[r][c] = z;
            }
        }

        return new DemGrid(ncols, nrows, minX, minY, cellSize, noDataValue, data);
    }

    // -------------------------------------------------------------------------

    private static List<Coordinate> collectPoints(ContourSet cs) {
        List<Coordinate> result = new ArrayList<>();
        cs.byElevation().forEach((elev, entities) -> {
            for (CADEntity e : entities) {
                Geometry g = e.geometry();
                if (g == null) continue;
                for (Coordinate coord : g.getCoordinates()) {
                    result.add(new Coordinate(coord.x, coord.y, elev));
                }
            }
        });
        return result;
    }

    /**
     * 在 TIN 中查找包含 p 的三角形，返回重心插值 Z；找不到时返回 NaN。
     */
    private static double interpolate(Geometry tins, Point p, List<Coordinate> pts) {
        for (int i = 0; i < tins.getNumGeometries(); i++) {
            Polygon tri = (Polygon) tins.getGeometryN(i);
            if (!tri.contains(p) && !tri.touches(p)) continue;
            Coordinate[] coords = tri.getExteriorRing().getCoordinates();
            // coords[0..2] = 三角形三顶点（coords[3] 与 coords[0] 相同）
            Coordinate a = findNearest(pts, coords[0]);
            Coordinate b = findNearest(pts, coords[1]);
            Coordinate c = findNearest(pts, coords[2]);
            if (a == null || b == null || c == null) continue;
            return barycentricZ(p.getX(), p.getY(), a, b, c);
        }
        return Double.NaN;
    }

    /** 在点集中找 XY 最近的含 Z 坐标。 */
    private static Coordinate findNearest(List<Coordinate> pts, Coordinate target) {
        Coordinate best = null;
        double minD = Double.MAX_VALUE;
        for (Coordinate c : pts) {
            double d = c.distance(target);
            if (d < minD) { minD = d; best = c; }
        }
        return best;
    }

    /** 重心插值 Z。 */
    private static double barycentricZ(double px, double py,
                                        Coordinate a, Coordinate b, Coordinate c) {
        double denom = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
        if (Math.abs(denom) < 1e-12) return (a.z + b.z + c.z) / 3.0;
        double w1 = ((b.y - c.y) * (px - c.x) + (c.x - b.x) * (py - c.y)) / denom;
        double w2 = ((c.y - a.y) * (px - c.x) + (a.x - c.x) * (py - c.y)) / denom;
        double w3 = 1.0 - w1 - w2;
        return w1 * a.z + w2 * b.z + w3 * c.z;
    }
}
