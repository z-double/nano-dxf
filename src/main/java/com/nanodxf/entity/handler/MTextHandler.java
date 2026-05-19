package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import com.nanodxf.text.MTextCleaner;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;

/**
 * MTEXT（多行格式文字）实体解析器。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 1  - 主文本内容（含格式控制码，需清洗）</li>
 *   <li>code 3  - 文本续行（超过 250 字符时分段，需拼接后再清洗）</li>
 *   <li>code 5  - handle</li>
 *   <li>code 8  - 图层名</li>
 *   <li>code 10/20/30 - 插入点 X/Y/Z</li>
 *   <li>code 40 - 字高</li>
 *   <li>code 50 - 旋转角度（度）</li>
 *   <li>code 71 - 附着点（1=左上，2=中上，...，9=右下）</li>
 * </ul>
 *
 * <p>格式控制码清洗由 {@link MTextCleaner} 负责：
 * 嵌套花括号格式块 {@code {\fArial|b0;text}} → "text"，
 * 段落换行 {@code \P} → {@code \n}，特殊符号 {@code %%d} → °，等。
 *
 * <p>注意：code 3 续行必须在 code 1 之前拼接，然后统一传给清洗器；
 * 否则格式块可能被截断在 code 3/code 1 边界处导致清洗错误。
 */
public class MTextHandler implements EntityHandler {

    @Override
    public CADEntity handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");

        double x    = buffer.getDouble(10, 0);
        double y    = buffer.getDouble(20, 0);
        double z    = buffer.getDouble(30, 0);
        double height   = buffer.getDouble(40, 2.5);
        double rotation = buffer.getDouble(50, 0);

        // 拼接文本：code 3 续行（先出现）+ code 1 主内容
        // DXF 规范：code 3 可以出现多次，按顺序拼接，code 1 在最后
        StringBuilder rawText = new StringBuilder();
        buffer.all(3).forEach(rawText::append); // 续行（按序）
        rawText.append(buffer.getString(1, "")); // 主内容

        // 清洗格式控制码
        String cleanText = MTextCleaner.clean(rawText.toString());

        Point geom = GeometryBuilder.factory()
                .createPoint(new Coordinate(x, y, z));

        return CADEntity.builder("MTEXT")
                .handle(handle)
                .layer(layer)
                .geometry(geom)
                .property("text",     cleanText)
                .property("rawText",  rawText.toString()) // 保留原始，供调试
                .property("height",   height)
                .property("rotation", rotation)
                .build();
    }
}
