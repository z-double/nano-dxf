package com.nanodxf.entity.handler;

import java.util.List;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

/**
 * LINE 实体解析器。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 5  - handle（实体唯一标识）</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 起点 X/Y/Z</li>
 *   <li>code 11/21/31 - 终点 X/Y/Z</li>
 * </ul>
 *
 * <p>输出 JTS {@link LineString}（2 个顶点）。
 * 起终点完全相同（零长度线段）时返回 null，由调用方记录 WARN。
 *
 * <p>注意：忽略 OCS 拉伸向量（code 210/220/230），假设实体在 WCS 中。
 * 非默认拉伸向量的正确处理留待后续版本支持。
 */
public class LineHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");

        double x1 = buffer.getDouble(10, 0);
        double y1 = buffer.getDouble(20, 0);
        double z1 = buffer.getDouble(30, 0);
        double x2 = buffer.getDouble(11, 0);
        double y2 = buffer.getDouble(21, 0);
        double z2 = buffer.getDouble(31, 0);

        Coordinate start = new Coordinate(x1, y1, z1);
        Coordinate end   = new Coordinate(x2, y2, z2);

        // 零长度线段无意义，返回 null 由调用方记录 WARN
        if (start.equals2D(end)) return List.of();

        LineString geom = GeometryBuilder.factory()
                .createLineString(new Coordinate[]{start, end});

        return List.of(CADEntity.builder("LINE")
                .handle(handle)
                .layer(layer)
                .geometry(geom)
                .build());
    }
}
