package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LinearRing;

/**
 * 3DFACE 实体解析器（三角面或四边形面）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 10/20/30 - 第一顶点 X/Y/Z</li>
 *   <li>code 11/21/31 - 第二顶点 X/Y/Z</li>
 *   <li>code 12/22/32 - 第三顶点 X/Y/Z</li>
 *   <li>code 13/23/33 - 第四顶点 X/Y/Z（与第三顶点相同时为三角面）</li>
 *   <li>code 70 - 边可见性标志（当前忽略）</li>
 * </ul>
 *
 * <p>输出：三角形或四边形闭合为 JTS {@code LinearRing}。
 * 注意：3DFACE 顶点顺序是正常序（v0→v1→v2→v3），
 * 与 SOLID 的蝴蝶结顺序不同（见 {@link SolidHandler}）。
 */
public class ThreeDFaceHandler implements EntityHandler {

    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");

        Coordinate v0 = new Coordinate(buffer.getDouble(10, 0), buffer.getDouble(20, 0), buffer.getDouble(30, 0));
        Coordinate v1 = new Coordinate(buffer.getDouble(11, 0), buffer.getDouble(21, 0), buffer.getDouble(31, 0));
        Coordinate v2 = new Coordinate(buffer.getDouble(12, 0), buffer.getDouble(22, 0), buffer.getDouble(32, 0));
        Coordinate v3 = new Coordinate(buffer.getDouble(13, 0), buffer.getDouble(23, 0), buffer.getDouble(33, 0));

        // 若 v3 与 v2 完全相同则为三角面
        boolean isTriangle = v2.equals3D(v3);

        Coordinate[] coords;
        if (isTriangle) {
            coords = new Coordinate[]{v0, v1, v2, v0};
        } else {
            coords = new Coordinate[]{v0, v1, v2, v3, v0};
        }

        LinearRing geom = GeometryBuilder.factory().createLinearRing(coords);
        return CADEntity.builder("3DFACE")
                .handle(handle).layer(layer).geometry(geom).build();
    }
}
