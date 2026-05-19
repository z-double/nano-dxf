package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * INSERT 实体解析器（块引用，含属性值）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 2  - 块名（在 DXFContext.blocks 中查找定义）</li>
 *   <li>code 10/20/30 - 插入点 X/Y/Z</li>
 *   <li>code 41/42/43 - X/Y/Z 比例（默认 1.0）</li>
 *   <li>code 50 - 旋转角度（度，逆时针）</li>
 *   <li>code 66 - 属性标志（1 = 有后续 ATTRIB 实体）</li>
 * </ul>
 *
 * <p>坐标变换公式（2D 仿射）：
 * <pre>
 *   double rad = Math.toRadians(rotation);
 *   x = scaleX * (ex * cos - ey * sin) + insertX;
 *   y = scaleY * (ex * sin + ey * cos) + insertY;
 * </pre>
 *
 * <p><b>Phase 2 实现策略</b>：
 * 返回插入点作为 {@link Point}，块名/比例/旋转存入 properties。
 * ATTRIB 子实体的 tag-value 对存入 {@code properties["attributes"]}。
 *
 * <p><b>TODO Phase 3</b>：递归展开块定义中的实体，应用仿射变换，
 * 并实现基于路径集合的循环引用检测（路径集合方案比深度计数更可靠，
 * 能检测 A→B→A 这类只有 2 层但真正循环的引用）。
 */
public class InsertHandler implements EntityHandler {

    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        String handle    = buffer.getString(5, "");
        String layer     = buffer.getString(8, "0");
        String blockName = buffer.getString(2, "");

        double ix = buffer.getDouble(10, 0);
        double iy = buffer.getDouble(20, 0);
        double iz = buffer.getDouble(30, 0);
        double sx = buffer.getDouble(41, 1.0); // X 比例
        double sy = buffer.getDouble(42, 1.0); // Y 比例
        double rotation = buffer.getDouble(50, 0);

        // 收集 ATTRIB 子实体：tag(code 2) → value(code 1)
        Map<String, String> attributes = new LinkedHashMap<>();
        for (EntityBuffer attrib : buffer.getChildren()) {
            String tag   = attrib.getString(2, "");
            String value = attrib.getString(1, "");
            if (!tag.isEmpty()) attributes.put(tag, value);
        }

        Point geom = GeometryBuilder.factory()
                .createPoint(new Coordinate(ix, iy, iz));

        CADEntity.Builder b = CADEntity.builder("INSERT")
                .handle(handle)
                .layer(layer)
                .geometry(geom)
                .property("blockName", blockName)
                .property("scaleX",    sx)
                .property("scaleY",    sy)
                .property("rotation",  rotation);

        if (!attributes.isEmpty()) {
            b.property("attributes", attributes);
        }

        return b.build();
    }
}
