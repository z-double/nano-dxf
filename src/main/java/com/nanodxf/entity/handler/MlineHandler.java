package com.nanodxf.entity.handler;

import com.nanodxf.EntityProperty;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.util.List;

/**
 * MLINE（多线）实体解析器。
 *
 * <p>MLINE 是由多条平行线段组成的复合实体，由 MLINESTYLE 定义各条线的偏移量和颜色。
 * 本解析器采用务实策略：从顶点坐标（code 10/20/30）提取基准线，输出为 LineString
 * 或闭合时为 LinearRing；多线样式名、对正方式和比例存入 properties 供调用方参考。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 2  - MLINESTYLE 样式名</li>
 *   <li>code 40 - 比例因子</li>
 *   <li>code 70 - 对正方式（0=顶，1=零/中，2=底）</li>
 *   <li>code 71 - 标志（bit 0=闭合，bit 1=压制起点，bit 2=压制终点）</li>
 *   <li>code 72 - 顶点数</li>
 *   <li>code 10/20/30 - 顶点坐标（每顶点一组）</li>
 *   <li>code 11/21/31 - 顶点方向向量（不加入几何）</li>
 *   <li>code 12/22/32 - 斜接方向（不加入几何）</li>
 * </ul>
 */
public class MlineHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");
        String style  = buffer.getString(2, "STANDARD");
        double scale  = buffer.getDouble(40, 1.0);
        int justification = buffer.getInt(70, 0);
        int flags     = buffer.getInt(71, 0);
        boolean closed = (flags & 1) == 1;

        // code 10/20/30 分别列出所有顶点的 X/Y/Z（与方向向量 11/21/31 不混淆）
        List<String> xs = buffer.all(10);
        List<String> ys = buffer.all(20);
        List<String> zs = buffer.all(30);
        int n = Math.min(xs.size(), Math.min(ys.size(), zs.isEmpty() ? xs.size() : zs.size()));

        if (n < 2) return List.of();

        Coordinate[] coords = new Coordinate[n];
        for (int i = 0; i < n; i++) {
            double x = parseD(xs.get(i));
            double y = parseD(ys.get(i));
            double z = zs.isEmpty() ? 0.0 : parseD(zs.get(i));
            coords[i] = new Coordinate(x, y, z);
        }

        Geometry geom;
        if (closed && !coords[0].equals2D(coords[n - 1])) {
            Coordinate[] ring = new Coordinate[n + 1];
            System.arraycopy(coords, 0, ring, 0, n);
            ring[n] = new Coordinate(coords[0]);
            geom = GeometryBuilder.factory().createLinearRing(ring);
        } else {
            geom = GeometryBuilder.factory().createLineString(coords);
        }

        return List.of(CADEntity.builder("MLINE")
                .handle(handle).layer(layer).geometry(geom)
                .property(EntityProperty.MLINE_STYLE, style)
                .property(EntityProperty.MLINE_JUSTIFICATION, justification)
                .property(EntityProperty.MLINE_SCALE, scale)
                .build());
    }

    private static double parseD(String s) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0.0; }
    }
}