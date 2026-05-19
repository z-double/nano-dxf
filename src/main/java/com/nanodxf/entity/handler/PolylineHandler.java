package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;

import java.util.ArrayList;
import java.util.List;

/**
 * POLYLINE + VERTEX + SEQEND 实体解析器（旧版多段线，R12 格式常见）。
 *
 * <p>POLYLINE 的 flag（code 70）决定类型：
 * <ul>
 *   <li>bit 1（0x01） - 闭合折线</li>
 *   <li>bit 8（0x08） - 3D 折线（VERTEX 有独立 Z）</li>
 *   <li>bit 16（0x10）- 多面体网格（3D mesh，暂不支持）</li>
 *   <li>bit 64（0x40）- 3D 多边形网格（暂不支持）</li>
 * </ul>
 *
 * <p>VERTEX 子实体通过 {@link EntityBuffer#getChildren()} 获取，
 * 由 {@code EntitiesParser} 在读取流时已提前收集。
 *
 * <p>输出：开放折线 → JTS {@code LineString}；
 * 闭合折线（≥4 个唯一顶点）→ JTS {@link LinearRing}。
 *
 * <p>注意：网格类型（bit 16/64）暂返回 null，后续版本支持。
 */
public class PolylineHandler implements EntityHandler {

    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");
        int flags  = buffer.getInt(70, 0);
        boolean closed = (flags & 1) == 1;
        boolean is3D   = (flags & 8) == 8;
        boolean isMesh = (flags & 16) != 0 || (flags & 64) != 0;

        // 暂不支持网格类型
        if (isMesh) return null;

        List<EntityBuffer> vertexBuffers = buffer.getChildren();
        if (vertexBuffers.isEmpty()) return null;

        List<Coordinate> coords = new ArrayList<>(vertexBuffers.size() + 1);
        for (EntityBuffer vb : vertexBuffers) {
            // VERTEX 子实体的 flag（code 70）bit 64/128 表示网格顶点，跳过
            int vFlags = vb.getInt(70, 0);
            if ((vFlags & 64) != 0 || (vFlags & 128) != 0) continue;

            double x = vb.getDouble(10, 0);
            double y = vb.getDouble(20, 0);
            double z = is3D ? vb.getDouble(30, 0) : 0;
            coords.add(new Coordinate(x, y, z));
        }

        if (coords.size() < 2) return null;

        if (closed) {
            Coordinate first = coords.get(0);
            Coordinate last  = coords.get(coords.size() - 1);
            if (!first.equals2D(last)) {
                coords.add(new Coordinate(first)); // 强制闭合
            }
        }

        Geometry geom;
        if (closed && coords.size() >= 4) {
            geom = GeometryBuilder.factory()
                    .createLinearRing(coords.toArray(new Coordinate[0]));
        } else {
            geom = GeometryBuilder.factory()
                    .createLineString(coords.toArray(new Coordinate[0]));
        }

        return CADEntity.builder("POLYLINE")
                .handle(handle).layer(layer).geometry(geom).build();
    }
}
