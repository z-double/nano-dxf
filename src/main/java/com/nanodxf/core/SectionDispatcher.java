package com.nanodxf.core;

import com.nanodxf.model.DXFContext;
import com.nanodxf.section.BlocksParser;
import com.nanodxf.section.EntitiesParser;
import com.nanodxf.section.HeaderParser;
import com.nanodxf.section.ObjectsParser;
import com.nanodxf.section.TablesParser;

import java.io.IOException;

/**
 * 根据 SECTION 名称将 DXFReader 分发到对应的 section 解析器。
 *
 * <p>调用时，reader 的当前位置已在 "2 SECTION_NAME" 对之后，
 * section 内容从下一个 group code 开始，由各解析器消费到 "0 ENDSEC" 为止。
 *
 * <p>未知 section（如私有扩展段）通过 {@link #skipSection} 快进到 ENDSEC，不报错。
 *
 * <p>DXF section 分发表：
 * <ul>
 *   <li>HEADER   - 图纸变量（版本、单位、范围）</li>
 *   <li>TABLES   - 命名符号表（图层、字体、线型）</li>
 *   <li>BLOCKS   - 块定义（含 *Model_Space）</li>
 *   <li>ENTITIES - 模型空间实体（R12 遗留段）</li>
 *   <li>OBJECTS  - 非图形对象（DICTIONARY/XRECORD，R2000+）</li>
 *   <li>CLASSES  - 自定义类定义（跳过）</li>
 * </ul>
 */
public class SectionDispatcher {

    private final HeaderParser   headerParser   = new HeaderParser();
    private final TablesParser   tablesParser   = new TablesParser();
    private final BlocksParser   blocksParser   = new BlocksParser();
    private final EntitiesParser entitiesParser = new EntitiesParser();
    private final ObjectsParser  objectsParser  = new ObjectsParser();

    /**
     * 分发到对应 section 解析器。
     *
     * @param sectionName SECTION 的名称（HEADER / TABLES / BLOCKS / ENTITIES / OBJECTS / ...）
     * @param reader      当前位置在 section 内容开头（"2 NAME" 已被消费）
     * @param ctx         共享解析上下文，各 parser 负责填充或读取
     */
    public void dispatch(String sectionName, DXFReader reader, DXFContext ctx)
            throws IOException {
        switch (sectionName) {
            case "HEADER"   -> headerParser.parse(reader, ctx);
            case "TABLES"   -> tablesParser.parse(reader, ctx);
            case "BLOCKS"   -> blocksParser.parse(reader, ctx);
            case "ENTITIES" -> entitiesParser.parse(reader, ctx);
            case "OBJECTS"  -> objectsParser.parse(reader, ctx);
            // CLASSES 及私有扩展段：快进跳过
            default         -> skipSection(reader);
        }
    }

    /** 快进消费 reader 直到遇到 "0 ENDSEC"，用于跳过未实现的 section。 */
    private void skipSection(DXFReader reader) throws IOException {
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null || (pair.code() == 0 && "ENDSEC".equals(pair.value()))) break;
        }
    }
}
