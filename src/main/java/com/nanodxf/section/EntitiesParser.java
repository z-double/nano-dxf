package com.nanodxf.section;

import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityDispatcher;
import com.nanodxf.filter.PaperSpaceFilter;
import com.nanodxf.model.DXFContext;

import java.io.IOException;

/**
 * ENTITIES section 解析器（R12 格式遗留段；R2000+ 模型空间实体在 *Model_Space 块中）。
 *
 * <p>解析流程：
 * <ol>
 *   <li>读取 code=0 定界符，获取实体类型字符串</li>
 *   <li>将该实体的所有 group code 收集到 {@link EntityBuffer}（遇到下一个 code=0 停止）</li>
 *   <li>通过 {@link PaperSpaceFilter} 过滤图纸空间实体（code 67=1）</li>
 *   <li>通过 {@link EntityDispatcher} 分发到对应 handler</li>
 *   <li>解析成功的实体追加到 {@code ctx.entities}；handler 返回 null 时跳过</li>
 * </ol>
 *
 * <p>TODO Phase 1：将跳过的未知实体类型记录到 QualityReport.skippedEntityTypes。
 */
public class EntitiesParser {

    private final EntityDispatcher dispatcher = new EntityDispatcher();

    /**
     * 解析 ENTITIES 段，将模型空间实体追加到 {@code ctx.entities}。
     *
     * @param reader 当前位置在 "2 ENTITIES" 之后，即 section 内容起始处
     * @param ctx    共享解析上下文（图层表只读，entities 列表可写）
     */
    public void parse(DXFReader reader, DXFContext ctx) throws IOException {
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0 && "ENDSEC".equals(pair.value())) break;
            if (pair.code() != 0) continue;

            String entityType = pair.value();

            // 将该实体所有 group code 收集到缓冲区（遇到下一个 code=0 回退并停止）
            EntityBuffer buffer = collectBuffer(reader);

            // 过滤图纸空间实体（code 67=1）
            if (!PaperSpaceFilter.isModelSpace(buffer)) continue;

            // 分发到对应 handler
            CADEntity entity = dispatcher.dispatch(entityType, buffer, ctx);
            if (entity != null) {
                ctx.entities.add(entity);
            }
            // TODO: 未知/不支持的类型记录到 QualityReport.skippedEntityTypes
        }
    }

    /**
     * 从 reader 顺序读取并缓冲当前实体的所有 group code，
     * 直到遇到下一个 code=0 为止（code=0 被回退到 reader，供外层循环重新读取）。
     */
    private EntityBuffer collectBuffer(DXFReader reader) throws IOException {
        EntityBuffer buffer = new EntityBuffer();
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0) {
                reader.pushBack(pair); // 归还边界标记
                break;
            }
            buffer.add(pair);
        }
        return buffer;
    }
}
