package com.nanodxf.entity.handler;

import java.util.List;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LinearRing;

/**
 * SOLID 实体解析器（填充三角形或四边形）。
 *
 * <p>group code 与 3DFACE 相同（code 10-13/20-23/30-33），
 * 但 DXF 规范中 SOLID 的第三、四顶点顺序是"蝴蝶结"排列：
 * <pre>
 *   v0 -------- v1
 *     \        /
 *      v3----v2  ← v2 和 v3 在文件中是交叉的
 * </pre>
 * 构建多边形时需将 v2 和 v3 互换，还原为逆时针顶点顺序。
 */
public class SolidHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");

        Coordinate v0 = new Coordinate(buffer.getDouble(10, 0), buffer.getDouble(20, 0), buffer.getDouble(30, 0));
        Coordinate v1 = new Coordinate(buffer.getDouble(11, 0), buffer.getDouble(21, 0), buffer.getDouble(31, 0));
        // SOLID 中 v2(code 12) 和 v3(code 13) 是蝴蝶结，构建多边形时互换
        Coordinate v2 = new Coordinate(buffer.getDouble(12, 0), buffer.getDouble(22, 0), buffer.getDouble(32, 0));
        Coordinate v3 = new Coordinate(buffer.getDouble(13, 0), buffer.getDouble(23, 0), buffer.getDouble(33, 0));

        boolean isTriangle = v2.equals3D(v3);

        Coordinate[] coords;
        if (isTriangle) {
            coords = new Coordinate[]{v0, v1, v2, v0};
        } else {
            // 互换 v2/v3（蝴蝶结 → 正常顺序）
            coords = new Coordinate[]{v0, v1, v3, v2, v0};
        }

        LinearRing geom = GeometryBuilder.factory().createLinearRing(coords);
        return List.of(CADEntity.builder("SOLID")
                .handle(handle).layer(layer).geometry(geom).build());
    }
}
