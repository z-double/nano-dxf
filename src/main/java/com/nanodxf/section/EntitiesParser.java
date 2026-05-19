package com.nanodxf.section;

import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityDispatcher;
import com.nanodxf.filter.PaperSpaceFilter;
import com.nanodxf.model.DXFContext;

import java.io.IOException;
import java.util.Set;

/**
 * ENTITIES section 解析器（R12 格式遗留段）。
 *
 * <p>解析流程（每个实体）：
 * <ol>
 *   <li>读取 code=0，得到实体类型字符串</li>
 *   <li>将该实体所有 group code 收集到 {@link EntityBuffer}（遇到下一个 code=0 回退停止）</li>
 *   <li>对特殊实体收集子实体缓冲（POLYLINE→VERTEX、INSERT+code66→ATTRIB），
 *       <em>无论是否通过图纸空间过滤，均需消费 reader 中的子实体以保持流同步</em></li>
 *   <li>通过 {@link PaperSpaceFilter} 过滤图纸空间实体</li>
 *   <li>通过 {@link EntityDispatcher} 分发到对应 handler</li>
 *   <li>解析成功的实体追加到 {@code ctx.entities}</li>
 * </ol>
 */
public class EntitiesParser {

    private final EntityDispatcher dispatcher = new EntityDispatcher();

    /**
     * 解析 ENTITIES 段，将模型空间实体追加到 {@code ctx.entities}。
     *
     * @param reader 当前位置在 "2 ENTITIES" 之后
     * @param ctx    共享解析上下文（entities 列表可写）
     */
    public void parse(DXFReader reader, DXFContext ctx) throws IOException {
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0 && "ENDSEC".equals(pair.value())) break;
            if (pair.code() != 0) continue;

            String entityType = pair.value();

            // 收集实体自身的 group code（到下一个 code=0 边界）
            EntityBuffer buffer = collectBuffer(reader);

            // ---------------------------------------------------------------
            // 特殊实体：POLYLINE 和带属性的 INSERT 需要从 reader 继续消费子实体。
            // 必须先于 PaperSpaceFilter，确保即使被过滤也能保持 reader 同步。
            // ---------------------------------------------------------------
            if ("POLYLINE".equals(entityType)) {
                collectChildEntities(reader, buffer, Set.of("VERTEX"));
            } else if ("INSERT".equals(entityType) && buffer.getInt(66, 0) == 1) {
                collectChildEntities(reader, buffer, Set.of("ATTRIB"));
            }

            // 过滤图纸空间实体（code 67=1）
            if (!PaperSpaceFilter.isModelSpace(buffer)) continue;

            CADEntity entity = dispatcher.dispatch(entityType, buffer, ctx);
            if (entity != null) ctx.entities.add(entity);
            // TODO: 未知类型记录到 QualityReport.skippedEntityTypes
        }
    }

    /**
     * 从 reader 顺序读取并缓冲当前实体的 group code，
     * 直到遇到下一个 code=0（回退到 reader，供外层循环重新读取）。
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

    /**
     * 从 reader 读取指定类型的子实体（VERTEX / ATTRIB）直到遇到 SEQEND 或未知实体，
     * 并将每个子实体的缓冲追加到 {@code parentBuffer.children}。
     *
     * <p>若 DXF 文件缺少 SEQEND（格式损坏），遇到非子实体的 code=0 时回退并停止，
     * 不抛出异常，继续解析后续内容。
     */
    private void collectChildEntities(DXFReader reader, EntityBuffer parentBuffer,
                                       Set<String> childTypes) throws IOException {
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0) {
                String type = pair.value();
                if ("SEQEND".equals(type)) break;
                if (childTypes.contains(type)) {
                    EntityBuffer childBuffer = collectBuffer(reader);
                    parentBuffer.addChild(childBuffer);
                } else {
                    // 未预期的 code=0，可能是文件损坏——回退保护后续解析
                    reader.pushBack(pair);
                    break;
                }
            }
        }
    }
}
