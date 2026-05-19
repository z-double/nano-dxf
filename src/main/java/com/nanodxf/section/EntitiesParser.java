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
import java.util.*;

/**
 * ENTITIES section 解析器（R12 格式遗留段）。
 *
 * <p>解析流程（每个实体）：
 * <ol>
 *   <li>读取 code=0，得到实体类型字符串</li>
 *   <li>收集该实体所有 group code 到 {@link EntityBuffer}</li>
 *   <li>对 POLYLINE / INSERT+code66 收集子实体缓冲（在 PaperSpaceFilter 之前，保持 reader 流同步）</li>
 *   <li>通过 {@link PaperSpaceFilter} 过滤图纸空间实体（code 67=1）</li>
 *   <li>通过 {@link EntityDispatcher} 分发到对应 handler</li>
 *   <li>富化：颜色（ACI + True Color）+ XDATA（地物编码映射）</li>
 *   <li>成功解析的实体追加到 {@code ctx.entities}；失败时记录 WARN 到 {@code ctx.errors}</li>
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

            // 子实体必须先于 PaperSpaceFilter 消费（保持 reader 流同步）
            if ("POLYLINE".equals(entityType)) {
                collectChildEntities(reader, buffer, Set.of("VERTEX"));
            } else if ("INSERT".equals(entityType) && buffer.getInt(66, 0) == 1) {
                collectChildEntities(reader, buffer, Set.of("ATTRIB"));
            }

            if (!PaperSpaceFilter.isModelSpace(buffer)) continue;

            CADEntity entity = dispatcher.dispatch(entityType, buffer, ctx);

            if (entity == null) {
                // handler 返回 null：未注册类型记录 skipped，已注册但失败记录 WARN
                if (!dispatcher.isKnown(entityType)) {
                    ctx.skippedEntityTypes.add(entityType);
                } else {
                    ctx.errors.add(new ParseError(ParseErrorLevel.WARN, entityType,
                        buffer.getString(5, ""), "handler 返回 null，实体被跳过"));
                }
                continue;
            }

            entity = enrichColor(entity, buffer, ctx);
            entity = enrichXData(entity, buffer);
            ctx.entities.add(entity);
        }
    }

    // -------------------------------------------------------------------------
    // 颜色富化
    // -------------------------------------------------------------------------

    /**
     * 从 buffer 读取颜色 group code，将显式颜色追加到实体属性。
     * <ul>
     *   <li>code 420（True Color，R2004+）优先于 code 62（ACI）</li>
     *   <li>code 62 = 256（BYLAYER）时，尝试从图层色表继承 colorRgb</li>
     *   <li>code 62 = 0（BYBLOCK）时不追加，延迟到块展开时处理</li>
     * </ul>
     */
    private CADEntity enrichColor(CADEntity entity, EntityBuffer buffer, DXFContext ctx) {
        int trueColor = buffer.getInt(420, -1);
        if (trueColor >= 0) {
            int r = (trueColor >> 16) & 0xFF;
            int g = (trueColor >>  8) & 0xFF;
            int b =  trueColor        & 0xFF;
            return entity.withProperty("colorRgb", new int[]{r, g, b});
        }

        int aci = buffer.getInt(62, 256);
        if (aci == 256) {
            // BYLAYER：从图层继承
            CADLayer layer = ctx.layers.get(entity.getLayer());
            if (layer != null && layer.colorRgb() != null) {
                return entity.withProperty("colorRgb", layer.colorRgb());
            }
            return entity;
        }
        if (aci == 0) return entity; // BYBLOCK

        int[] rgb = AciColorTable.toRgb(aci);
        return rgb != null
            ? entity.withProperty("colorAci", aci).withProperty("colorRgb", rgb)
            : entity;
    }

    // -------------------------------------------------------------------------
    // XDATA 富化
    // -------------------------------------------------------------------------

    /**
     * 从 buffer 提取 XDATA，进行地物编码映射，并将结果追加到实体属性：
     * <ul>
     *   <li>{@code xdata} - 原始 XDATA map（{@code Map<应用名, List<XDataEntry>>}）</li>
     *   <li>{@code featureCode} - 地物编码（如 "41000"）</li>
     *   <li>{@code featureType} - 地物名称（如 "普通房屋"）</li>
     *   <li>{@code featureCategory} - 地物大类（如 "建筑"）</li>
     *   <li>{@code featureTypeSource} - "registry"（已收录）或 "unknown"（未收录）</li>
     * </ul>
     */
    private CADEntity enrichXData(CADEntity entity, EntityBuffer buffer) {
        Map<String, List<XDataEntry>> xdata = xdataParser.parseFromBuffer(buffer);
        if (xdata.isEmpty()) return entity;

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("xdata", xdata);

        xdataParser.extractFeatureCode(xdata).ifPresent(code -> {
            extra.put("featureCode", code);
            FeatureCodeRegistry.lookup(code).ifPresentOrElse(
                info -> {
                    extra.put("featureType",      info.name());
                    extra.put("featureCategory",  info.category());
                    extra.put("featureTypeSource", "registry");
                },
                () -> extra.put("featureTypeSource", "unknown")
            );
        });

        return entity.withProperties(extra);
    }

    // -------------------------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------------------------

    /** 收集单个实体的 group code 到缓冲区，遇到 code=0 边界则 pushBack 并停止。 */
    private EntityBuffer collectBuffer(DXFReader reader) throws IOException {
        EntityBuffer buffer = new EntityBuffer();
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0) { reader.pushBack(pair); break; }
            buffer.add(pair);
        }
        return buffer;
    }

    /**
     * 读取 VERTEX / ATTRIB 等子实体直到 SEQEND，追加为父实体的 children。
     * 若文件缺少 SEQEND（格式损坏），遇到非子实体类型时 pushBack 并退出。
     */
    private void collectChildEntities(DXFReader reader, EntityBuffer parent,
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
