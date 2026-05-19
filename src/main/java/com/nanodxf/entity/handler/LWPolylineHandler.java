package com.nanodxf.entity.handler;

import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.Discretizer;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;

import java.util.ArrayList;
import java.util.List;

/**
 * LWPOLYLINE（轻量多段线）实体解析器。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 5  - handle</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 38 - elevation（整体 Z 高度；等高线高程就存在这里，极易丢失）</li>
 *   <li>code 70 - 标志位（bit 0 = 1 → 闭合多段线）</li>
 *   <li>code 10 - 顶点 X（重复出现，每个顶点一次）</li>
 *   <li>code 20 - 顶点 Y（与 code 10 配对）</li>
 *   <li>code 42 - 凸度 bulge（非 0 表示该顶点到下一顶点为圆弧段）</li>
 * </ul>
 *
 * <p><b>关键细节</b>：
 * <ul>
 *   <li>LWPOLYLINE 是二维实体，所有顶点 Z 均来自 code 38（elevation），不在顶点中存储</li>
 *   <li>bulge = tan(夹角/4)，正值逆时针，负值顺时针；0 表示直线段</li>
 *   <li>闭合标志为 1 时，末尾顶点到首顶点之间仍可能有 bulge（最后一个顶点的 bulge）</li>
 *   <li>闭合多段线的首尾坐标必须强制相等（CAD 中不存储重复首点）</li>
 * </ul>
 *
 * <p>输出：开放折线 → JTS {@code LineString}；
 * 闭合折线（≥4 顶点）→ JTS {@link LinearRing}（可直接用作 Polygon 外环）。
 */
public class LWPolylineHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle    = buffer.getString(5, "");
        String layer     = buffer.getString(8, "0");
        double elevation = buffer.getDouble(38, 0.0); // 整体 Z 高度
        int flags        = buffer.getInt(70, 0);
        boolean closed   = (flags & 1) == 1;

        // 按出现顺序收集顶点和凸度
        // 顶点以 code 10（X）+ code 20（Y）配对出现，bulge（code 42）紧随可选
        List<double[]> vertices = new ArrayList<>();
        List<Double> bulges     = new ArrayList<>();
        double pendingX = 0;
        boolean hasPendingX = false;

        for (GroupCodePair pair : buffer.all()) {
            switch (pair.code()) {
                case 10 -> { pendingX = pair.asDouble(); hasPendingX = true; }
                case 20 -> {
                    if (hasPendingX) {
                        vertices.add(new double[]{pendingX, pair.asDouble()});
                        bulges.add(0.0); // 默认直线段
                        hasPendingX = false;
                    }
                }
                // code 42 跟在顶点 XY 之后，对应最近添加的顶点
                case 42 -> { if (!bulges.isEmpty()) bulges.set(bulges.size() - 1, pair.asDouble()); }
            }
        }

        if (vertices.size() < 2) return List.of(); // 顶点不足，几何无意义

        double tolerance = ctx.config.getArcTolerance();
        List<Coordinate> coords = buildCoords(vertices, bulges, closed, elevation, tolerance);

        if (coords.size() < 2) return List.of();

        Geometry geom = buildGeometry(coords, closed);

        CADEntity.Builder builder = CADEntity.builder("LWPOLYLINE")
                .handle(handle)
                .layer(layer)
                .geometry(geom);

        // elevation 非零时存入属性，便于等高线高程提取
        if (elevation != 0.0) builder.property("elevation", elevation);

        return List.of(builder.build());
    }

    /**
     * 将顶点列表和凸度列表转换为最终坐标序列。
     * bulge 非 0 时调用 {@link Discretizer#bulge} 将对应线段离散化为圆弧点列。
     */
    private List<Coordinate> buildCoords(List<double[]> vertices, List<Double> bulges,
                                          boolean closed, double elevation, double tolerance) {
        List<Coordinate> coords = new ArrayList<>();
        // 加入起点
        coords.add(new Coordinate(vertices.get(0)[0], vertices.get(0)[1], elevation));

        // 处理各线段（v[i] → v[i+1]）
        for (int i = 0; i < vertices.size() - 1; i++) {
            Coordinate p1 = new Coordinate(vertices.get(i)[0], vertices.get(i)[1]);
            Coordinate p2 = new Coordinate(vertices.get(i + 1)[0], vertices.get(i + 1)[1]);
            appendSegment(coords, p1, p2, bulges.get(i), elevation, tolerance);
        }

        // 闭合时，处理末顶点 → 首顶点的线段（可能含 bulge）
        if (closed) {
            int last = vertices.size() - 1;
            Coordinate pLast  = new Coordinate(vertices.get(last)[0], vertices.get(last)[1]);
            Coordinate pFirst = new Coordinate(vertices.get(0)[0], vertices.get(0)[1]);
            appendSegment(coords, pLast, pFirst, bulges.get(last), elevation, tolerance);

            // 强制首尾完全相同（CAD 文件中闭合多段线不重复存储首点）
            Coordinate first = coords.get(0);
            Coordinate lastCoord = coords.get(coords.size() - 1);
            if (!first.equals2D(lastCoord)) {
                coords.add(new Coordinate(first));
            }
        }

        return coords;
    }

    /**
     * 追加从 p1 到 p2 的一段（直线或圆弧）到 coords。
     * 跳过 p1（已在 coords 末尾），只追加 p2 及中间的弧线插值点。
     */
    private void appendSegment(List<Coordinate> coords, Coordinate p1, Coordinate p2,
                                double bulge, double elevation, double tolerance) {
        if (Math.abs(bulge) > 1e-10) {
            // 圆弧段：离散化后追加（跳过首点，它已在 coords 中）
            List<Coordinate> arcPts = Discretizer.bulge(p1, p2, bulge, tolerance);
            for (int j = 1; j < arcPts.size(); j++) {
                Coordinate c = arcPts.get(j);
                coords.add(new Coordinate(c.x, c.y, elevation));
            }
        } else {
            // 直线段：直接追加终点
            coords.add(new Coordinate(p2.x, p2.y, elevation));
        }
    }

    /** 根据闭合状态选择几何类型：闭合 → LinearRing，开放 → LineString。 */
    private Geometry buildGeometry(List<Coordinate> coords, boolean closed) {
        Coordinate[] arr = coords.toArray(new Coordinate[0]);
        if (closed && arr.length >= 4) {
            // LinearRing 要求至少 4 个坐标且首尾相同（已在 buildCoords 中保证）
            return GeometryBuilder.factory().createLinearRing(arr);
        }
        return GeometryBuilder.factory().createLineString(arr);
    }
}
