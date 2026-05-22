package com.nanodxf.entity.handler;

import com.nanodxf.EntityProperty;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import com.nanodxf.text.MTextCleaner;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import java.util.ArrayList;
import java.util.List;

/**
 * MULTILEADER（多重引线）实体解析器（AC1021+）。
 *
 * <p>MULTILEADER 比 LEADER 复杂得多，支持多条引线、MText 或块内容标注。
 * 本解析器采用务实策略：从 group code 10/20/30 序列中提取引线顶点，
 * 从 code 304（MText 内容）或 code 1（普通文字）中提取标注文字。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 5  - handle</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 引线顶点坐标（重复出现，构成折线）</li>
 *   <li>code 11/21 - 文字锚点（落点），辅助定位，不加入主折线</li>
 *   <li>code 1  - 文字内容（简单文字）</li>
 *   <li>code 304 - MText 内容字符串（含格式码，需清洗）</li>
 *   <li>code 170 - 引线类型（0=无，1=直线，2=样条）</li>
 *   <li>code 172 - 内容类型（0=无，1=块，2=MText，3=容差框）</li>
 * </ul>
 *
 * <p>输出：引线顶点连接为 JTS {@link LineString}，文字内容存入 {@link EntityProperty#TEXT}。
 * 若只有 1 个顶点则返回空（不产生实体）。
 */
public class MultiLeaderHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");

        // 文字内容：code 304（MText）优先，回退到 code 1
        String rawText = buffer.getString(304, buffer.getString(1, ""));
        String text = rawText.isEmpty() ? "" : MTextCleaner.clean(rawText);

        // 引线顶点：按 code 10/20/30 序列顺序解析
        List<double[]> pts = new ArrayList<>();
        double px = 0, py = 0;
        boolean hasX = false, hasY = false;

        for (GroupCodePair pair : buffer.all()) {
            switch (pair.code()) {
                case 10 -> { px = pair.asDouble(); hasX = true; }
                case 20 -> { py = pair.asDouble(); hasY = true; }
                case 30 -> {
                    if (hasX && hasY) {
                        pts.add(new double[]{px, py, pair.asDouble()});
                        hasX = hasY = false;
                    }
                }
            }
        }

        // 兜底：若文件无 code 30（二维引线），从 code 10/20 列表构建
        if (pts.isEmpty()) {
            List<String> xs = buffer.all(10);
            List<String> ys = buffer.all(20);
            int count = Math.min(xs.size(), ys.size());
            for (int i = 0; i < count; i++) {
                try {
                    pts.add(new double[]{
                        Double.parseDouble(xs.get(i).trim()),
                        Double.parseDouble(ys.get(i).trim()),
                        0.0
                    });
                } catch (NumberFormatException ignored) {}
            }
        }

        if (pts.size() < 2) return List.of();

        Coordinate[] coords = pts.stream()
                .map(pt -> new Coordinate(pt[0], pt[1], pt[2]))
                .toArray(Coordinate[]::new);

        LineString geom = GeometryBuilder.factory().createLineString(coords);

        CADEntity.Builder builder = CADEntity.builder("MULTILEADER")
                .handle(handle).layer(layer).geometry(geom);
        if (!text.isEmpty()) builder.property(EntityProperty.TEXT, text);

        return List.of(builder.build());
    }
}
