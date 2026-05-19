package com.nanodxf.section;

import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.model.DXFContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OBJECTS section 解析器（R2000+ 专有，R12 无此段）。
 *
 * <p>OBJECTS 段存储非图形对象数据，通过 DICTIONARY → XRECORD 结构提供
 * 实体的扩展属性，是与 XDATA 并列的另一套附加属性机制。
 *
 * <p>解析结果存入 {@link DXFContext#objectData}（handle → 属性 map），
 * EntitiesParser 可通过实体的 owner handle（code 330）找到关联的 XRECORD 数据。
 *
 * <p>当前解析内容：
 * <ul>
 *   <li>XRECORD：将其 group code 按 code/value 存入属性 map</li>
 *   <li>DICTIONARY：记录 handle → 对象类型映射（不深度展开）</li>
 * </ul>
 *
 * <p>R12 文件（AC1009）无 OBJECTS 段，{@link com.nanodxf.core.SectionDispatcher}
 * 遇到时会调用此 parser，遇到空 ENDSEC 正常退出。
 */
public class ObjectsParser {

    public void parse(DXFReader reader, DXFContext ctx) throws IOException {
        String currentHandle = null;
        String currentType   = null;
        Map<String, Object> currentData = null;

        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0 && "ENDSEC".equals(pair.value())) {
                flushObject(currentType, currentHandle, currentData, ctx);
                break;
            }

            if (pair.code() == 0) {
                // 新对象开始：先保存上一个
                flushObject(currentType, currentHandle, currentData, ctx);
                currentType   = pair.value();
                currentHandle = null;
                currentData   = new LinkedHashMap<>();
            } else if (currentData != null) {
                switch (pair.code()) {
                    case 5   -> currentHandle = pair.value(); // handle
                    case 3   -> accumulate(currentData, "key_" + currentData.size(), pair.value());
                    case 350 -> accumulate(currentData, "ref_" + currentData.size(), pair.value());
                    // XRECORD 数据字段（通用：存所有 code/value）
                    default  -> accumulate(currentData, "c" + pair.code(), pair.value());
                }
            }
        }
    }

    /** 将收集好的对象数据存入 ctx.objectData（只保存有 handle 的对象）。 */
    private void flushObject(String type, String handle,
                              Map<String, Object> data, DXFContext ctx) {
        if (handle != null && data != null && !data.isEmpty()) {
            data.put("_type", type);
            ctx.objectData.put(handle, Map.copyOf(data));
        }
    }

    /** 同一 code 多次出现时，追加为 List；第一次出现存 String。 */
    @SuppressWarnings("unchecked")
    private void accumulate(Map<String, Object> map, String key, String value) {
        Object existing = map.get(key);
        if (existing == null) {
            map.put(key, value);
        } else if (existing instanceof List) {
            ((List<String>) existing).add(value);
        } else {
            List<String> list = new ArrayList<>();
            list.add((String) existing);
            list.add(value);
            map.put(key, list);
        }
    }
}
