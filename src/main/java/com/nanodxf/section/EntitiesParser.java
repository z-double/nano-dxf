package com.nanodxf.section;

import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityDispatcher;
import com.nanodxf.filter.PaperSpaceFilter;
import com.nanodxf.model.DXFContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ENTITIES section 解析器（R12 格式遗留段）。
 *
 * <p>R2000+ 格式的模型空间实体存放在 BLOCKS 段的 *Model_Space 块中，
 * 此处的 ENTITIES 段仅在 R12（AC1009）中使用。
 * 两种情况均通过此类解析，调用方由 CADParser 根据版本选择数据来源。
 *
 * <p>解析流程：
 * <ol>
 *   <li>读取 code=0 定界符，获取实体类型</li>
 *   <li>将该实体的所有 group code 收集到 EntityBuffer（遇到下一个 code=0 停止）</li>
 *   <li>经 PaperSpaceFilter 过滤图纸空间实体</li>
 *   <li>通过 EntityDispatcher 分发到对应 handler</li>
 *   <li>handler 返回 null（解析失败或已跳过）时记录 WARN 并继续</li>
 * </ol>
 *
 * <p>TODO Phase 1：完整实现，包括 WARN 记录和错误收集。
 */
public class EntitiesParser {
    private final EntityDispatcher dispatcher = new EntityDispatcher();

    /**
     * 解析 ENTITIES 段并收集所有模型空间实体。
     *
     * @param reader 当前位置在 section 内容开头（2 ENTITIES 已被消费）
     * @param ctx    共享解析上下文（图层表等，只读）
     * @return 解析成功的实体列表（几何为 null 的实体也会包含在内）
     */
    public List<CADEntity> parse(DXFReader reader, DXFContext ctx) throws IOException {
        List<CADEntity> entities = new ArrayList<>();
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0 && "ENDSEC".equals(pair.value())) break;
            if (pair.code() != 0) continue;

            String entityType = pair.value();
            // 收集该实体的所有 group code 到 buffer
            EntityBuffer buffer = collectBuffer(reader);

            // 过滤图纸空间实体（code 67 = 1）
            if (!PaperSpaceFilter.isModelSpace(buffer)) continue;

            CADEntity entity = dispatcher.dispatch(entityType, buffer, ctx);
            if (entity != null) {
                entities.add(entity);
            }
            // TODO: 未知类型记录到 QualityReport.skippedEntityTypes
        }
        return entities;
    }

    /**
     * 从 reader 读取并缓冲当前实体的所有 group code，
     * 直到遇到下一个 code=0（该 code=0 会被回退到 reader 中）。
     */
    private EntityBuffer collectBuffer(DXFReader reader) throws IOException {
        EntityBuffer buffer = new EntityBuffer();
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0) {
                reader.pushBack(pair); // 回退边界标记，供下轮循环读取
                break;
            }
            buffer.add(pair);
        }
        return buffer;
    }
}
