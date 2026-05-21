package com.nanodxf.entity.handler;

import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import java.util.ArrayList;
import java.util.List;

/**
 * LEADER 实体解析器（引线，用于图纸标注引出线）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 5  - handle</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 引线顶点坐标（重复出现，构成折线）</li>
 *   <li>code 71 - 箭头类型（0=无，1=实心，…；当前忽略）</li>
 *   <li>code 72 - 路径类型（0=直线，1=样条；当前忽略，均输出为折线）</li>
 *   <li>code 76 - 顶点数</li>
 * </ul>
 *
 * <p>输出：所有引线顶点连接为 JTS {@link LineString}。
 * 顶点顺序从箭头端（code 10/20/30 首次出现）到文字端（最后出现）。
 */
public class LeaderHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");

        List<double[]> rawPts = new ArrayList<>();
        double px = 0, py = 0, pz = 0;
        boolean hasX = false, hasY = false;

        for (GroupCodePair pair : buffer.all()) {
            switch (pair.code()) {
                case 10 -> { px = pair.asDouble(); hasX = true; }
                case 20 -> { py = pair.asDouble(); hasY = true; }
                case 30 -> {
                    if (hasX && hasY) {
                        pz = pair.asDouble();
                        rawPts.add(new double[]{px, py, pz});
                        hasX = hasY = false;
                    }
                }
            }
        }

        // 若文件无 code 30（二维引线），从 code 10/20 列表构建
        if (rawPts.isEmpty()) {
            List<String> xs = buffer.all(10);
            List<String> ys = buffer.all(20);
            int count = Math.min(xs.size(), ys.size());
            for (int i = 0; i < count; i++) {
                try {
                    rawPts.add(new double[]{
                        Double.parseDouble(xs.get(i).trim()),
                        Double.parseDouble(ys.get(i).trim()),
                        0.0
                    });
                } catch (NumberFormatException ignored) {}
            }
        }

        if (rawPts.size() < 2) return List.of();

        Coordinate[] coords = rawPts.stream()
                .map(pt -> new Coordinate(pt[0], pt[1], pt[2]))
                .toArray(Coordinate[]::new);

        LineString geom = GeometryBuilder.factory().createLineString(coords);

        return List.of(CADEntity.builder("LEADER")
                .handle(handle).layer(layer).geometry(geom).build());
    }
}
