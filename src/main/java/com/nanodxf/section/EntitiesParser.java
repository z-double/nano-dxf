package com.nanodxf.section;

import com.nanodxf.ParseError;
import com.nanodxf.ParseErrorLevel;
import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityDispatcher;
import com.nanodxf.filter.PaperSpaceFilter;
import com.nanodxf.geometry.AciColorTable;
import com.nanodxf.layer.CADLayer;
import com.nanodxf.model.DXFContext;
import com.nanodxf.xdata.FeatureCodeRegistry;
import com.nanodxf.xdata.XDataEntry;
import com.nanodxf.xdata.XDataParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * ENTITIES section 解析器（R12 格式遗留段）。
 *
 * <p>解析流程（每个实体）：
 * <ol>
 *   <li>读取 code=0，得到实体类型字符串</li>
 *   <li>收集该实体所有 group code 到 {@link EntityBuffer}</li>
 *   <li>对 POLYLINE / INSERT+code66 收集子实体缓冲（在 PaperSpaceFilter 之前，保持 reader 流同步）</li>
 *   <li>通过 {@link PaperSpaceFilter} 过滤图纸空间实体（code 67=1）</li>
 *   <li>通过 {@link EntityDispatcher} 分发到对应 handler（返回 List，可为空）</li>
 *   <li>富化：颜色（ACI + True Color）+ XDATA（地物编码映射）</li>
 *   <li>将所有结果追加到 {@code ctx.entities}</li>
 * </ol>
 */
public class EntitiesParser {

    private final EntityDispatcher dispatcher = new EntityDispatcher();
    private final XDataParser      xdataParser = new XDataParser();

    public void parse(DXFReader reader, DXFContext ctx) throws IOException {
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0 && "ENDSEC".equals(pair.value())) break;
            if (pair.code() != 0) continue;

            String entityType = pair.value();
            EntityBuffer buffer = collectBuffer(reader);

            // 子实体必须先于 PaperSpaceFilter 消费，保持 reader 流同步
            if ("POLYLINE".equals(entityType)) {
                collectChildEntities(reader, buffer, Set.of("VERTEX"));
            } else if ("INSERT".equals(entityType) && buffer.getInt(66, 0) == 1) {
                collectChildEntities(reader, buffer, Set.of("ATTRIB"));
            }

            if (!PaperSpaceFilter.isModelSpace(buffer)) continue;

            List<CADEntity> dispatched = dispatcher.dispatch(entityType, buffer, ctx);
            if (dispatched == null) {
                // 未注册类型 → 记录 skipped（INFO 级别，由 CADParser 统一追加到 errors）
                ctx.skippedEntityTypes.add(entityType);
                continue;
            }

            // 逐实体富化后追加到 ctx.entities
            for (CADEntity entity : dispatched) {
                entity = enrichColor(entity, buffer, ctx);
                entity = enrichXData(entity, buffer);
                ctx.entities.add(entity);
            }
        }
    }

    /**
     * 创建 ENTITIES 段的惰性 Spliterator，用于流式解析。
     *
     * <p>调用前 reader 必须已定位在 ENTITIES 段内（SECTION/ENTITIES 已消费）。
     * Spliterator 内部一次读取一个 DXF 实体，消费者按需拉取，不全量加载到内存。
     */
    public Spliterator<CADEntity> spliterator(DXFReader reader, DXFContext ctx) {
        return new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE,
                Spliterator.ORDERED | Spliterator.NONNULL) {

            private final Deque<CADEntity> pending = new ArrayDeque<>();
            private boolean done = false;

            @Override
            public boolean tryAdvance(Consumer<? super CADEntity> action) {
                // 先从缓冲区消费（一个 DXF 实体可能产生多个 CADEntity）
                if (!pending.isEmpty()) { action.accept(pending.poll()); return true; }
                if (done) return false;

                // 读下一个实体
                while (true) {
                    List<CADEntity> batch = readNext();
                    if (batch == null) { done = true; return false; }
                    if (!batch.isEmpty()) {
                        pending.addAll(batch);
                        action.accept(pending.poll());
                        return true;
                    }
                    // 空批次（被过滤）继续读
                }
            }

            private List<CADEntity> readNext() {
                try {
                    while (reader.hasNext()) {
                        GroupCodePair pair = reader.next();
                        if (pair == null) return null;
                        if (pair.code() == 0 && "ENDSEC".equals(pair.value())) return null;
                        if (pair.code() != 0) continue;

                        String entityType = pair.value();
                        EntityBuffer buffer = collectBuffer(reader);

                        if ("POLYLINE".equals(entityType))
                            collectChildEntities(reader, buffer, Set.of("VERTEX"));
                        else if ("INSERT".equals(entityType) && buffer.getInt(66, 0) == 1)
                            collectChildEntities(reader, buffer, Set.of("ATTRIB"));

                        if (!PaperSpaceFilter.isModelSpace(buffer)) return List.of();

                        List<CADEntity> dispatched = dispatcher.dispatch(entityType, buffer, ctx);
                        if (dispatched == null) {
                            ctx.skippedEntityTypes.add(entityType);
                            return List.of();
                        }
                        List<CADEntity> result = new ArrayList<>(dispatched.size());
                        for (CADEntity entity : dispatched) {
                            entity = enrichColor(entity, buffer, ctx);
                            entity = enrichXData(entity, buffer);
                            result.add(entity);
                        }
                        return result;
                    }
                    return null;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // 颜色富化
    // -------------------------------------------------------------------------

    CADEntity enrichColor(CADEntity entity, EntityBuffer buffer, DXFContext ctx) {
        int trueColor = buffer.getInt(420, -1);
        if (trueColor >= 0) {
            int r = (trueColor >> 16) & 0xFF;
            int g = (trueColor >>  8) & 0xFF;
            int b =  trueColor        & 0xFF;
            return entity.withProperty("colorRgb", new int[]{r, g, b});
        }
        int aci = buffer.getInt(62, 256);
        if (aci == 256) {
            CADLayer layer = ctx.layers.get(entity.getLayer());
            if (layer != null && layer.colorRgb() != null) {
                return entity.withProperty("colorRgb", layer.colorRgb());
            }
            return entity;
        }
        if (aci == 0) return entity;
        int[] rgb = AciColorTable.toRgb(aci);
        return rgb != null
            ? entity.withProperty("colorAci", aci).withProperty("colorRgb", rgb)
            : entity;
    }

    // -------------------------------------------------------------------------
    // XDATA 富化
    // -------------------------------------------------------------------------

    CADEntity enrichXData(CADEntity entity, EntityBuffer buffer) {
        Map<String, List<XDataEntry>> xdata = xdataParser.parseFromBuffer(buffer);
        if (xdata.isEmpty()) return entity;

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("xdata", xdata);

        xdataParser.extractFeatureCode(xdata).ifPresent(code -> {
            extra.put("featureCode", code);
            FeatureCodeRegistry.lookup(code).ifPresentOrElse(
                info -> {
                    extra.put("featureType",       info.name());
                    extra.put("featureCategory",   info.category());
                    extra.put("featureTypeSource", "registry");
                },
                () -> extra.put("featureTypeSource", "unknown")
            );
        });

        return entity.withProperties(extra);
    }

    // -------------------------------------------------------------------------
    // 辅助方法（同 BlocksParser 中的同名方法，职责相同）
    // -------------------------------------------------------------------------

    EntityBuffer collectBuffer(DXFReader reader) throws IOException {
        EntityBuffer buffer = new EntityBuffer();
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0) { reader.pushBack(pair); break; }
            buffer.add(pair);
        }
        return buffer;
    }

    void collectChildEntities(DXFReader reader, EntityBuffer parent,
                               Set<String> childTypes) throws IOException {
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0) {
                String type = pair.value();
                if ("SEQEND".equals(type)) break;
                if (childTypes.contains(type)) {
                    parent.addChild(collectBuffer(reader));
                } else {
                    reader.pushBack(pair);
                    break;
                }
            }
        }
    }
}
