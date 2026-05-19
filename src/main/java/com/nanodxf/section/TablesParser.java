package com.nanodxf.section;

import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.layer.CADLayer;
import com.nanodxf.layer.LineTypeDef;
import com.nanodxf.layer.TextStyle;
import com.nanodxf.model.DXFContext;

import java.io.IOException;

/**
 * TABLES section 解析器。
 *
 * <p>解析命名符号表，填充 DXFContext 中的图层、文字样式、线型等映射，
 * 供后续 EntitiesParser 阶段只读引用。
 *
 * <p>当前实现解析：
 * <ul>
 *   <li>LAYER 表  - 图层名、颜色号（ACI）、可见性、线型名</li>
 *   <li>STYLE 表  - 文字样式名、字体文件、字高、宽度系数</li>
 *   <li>LTYPE 表  - 线型名、描述（暂不解析线型图案数据）</li>
 * </ul>
 *
 * <p>TODO Phase 1：完整实现各子表解析。
 */
public class TablesParser {

    public void parse(DXFReader reader, DXFContext ctx) throws IOException {
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0 && "ENDSEC".equals(pair.value())) break;

            if (pair.code() == 0 && "TABLE".equals(pair.value())) {
                GroupCodePair namePair = reader.next();
                if (namePair != null && namePair.code() == 2) {
                    parseTable(namePair.value(), reader, ctx);
                }
            }
        }
    }

    private void parseTable(String tableName, DXFReader reader, DXFContext ctx) throws IOException {
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0 && "ENDTAB".equals(pair.value())) break;

            if (pair.code() == 0) {
                switch (pair.value()) {
                    case "LAYER" -> parseLayer(reader, ctx);
                    case "STYLE" -> parseStyle(reader, ctx);
                    case "LTYPE" -> parseLtype(reader, ctx);
                    default      -> skipEntry(reader);
                }
            }
        }
    }

    private void parseLayer(DXFReader reader, DXFContext ctx) throws IOException {
        CADLayer layer = null;
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null || pair.code() == 0) {
                if (pair != null) reader.pushBack(pair);
                break;
            }
            switch (pair.code()) {
                case 2 -> layer = ctx.getOrCreateLayer(pair.value());
                case 62 -> { if (layer != null) layer.setColorNumber(Math.abs(pair.asInt())); }
                case 70 -> { if (layer != null) layer.setVisible((pair.asInt() & 1) == 0); }
                case 6  -> { if (layer != null) layer.setLineTypeName(pair.value()); }
            }
        }
    }

    private void parseStyle(DXFReader reader, DXFContext ctx) throws IOException {
        String name = null; String fontFile = null;
        double height = 0; double widthFactor = 1.0;
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null || pair.code() == 0) {
                if (pair != null) reader.pushBack(pair);
                break;
            }
            switch (pair.code()) {
                case 2  -> name = pair.value();
                case 3  -> fontFile = pair.value();
                case 40 -> height = pair.asDouble();
                case 41 -> widthFactor = pair.asDouble();
            }
        }
        if (name != null) {
            TextStyle style = new TextStyle(name);
            style.setFontFile(fontFile);
            style.setHeight(height);
            style.setWidthFactor(widthFactor);
            ctx.textStyles.put(name, style);
        }
    }

    private void parseLtype(DXFReader reader, DXFContext ctx) throws IOException {
        String name = null; String desc = null;
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null || pair.code() == 0) {
                if (pair != null) reader.pushBack(pair);
                break;
            }
            switch (pair.code()) {
                case 2 -> name = pair.value();
                case 3 -> desc = pair.value();
            }
        }
        if (name != null) {
            LineTypeDef lt = new LineTypeDef(name);
            lt.setDescription(desc);
            ctx.lineTypes.put(name, lt);
        }
    }

    /** 跳过当前表项的所有 group code，直到下一个 code=0 边界。 */
    private void skipEntry(DXFReader reader) throws IOException {
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null || pair.code() == 0) {
                if (pair != null) reader.pushBack(pair);
                break;
            }
        }
    }
}
