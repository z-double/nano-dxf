package com.nanodxf.section;

import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityDispatcher;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.CADBlock;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * BLOCKS section 解析器。
 *
 * <p>读取所有块定义，将块内实体解析为 {@link CADEntity} 并存入 {@link CADBlock#entities()}，
 * 供 {@link com.nanodxf.entity.handler.InsertHandler} 在 ENTITIES 阶段展开使用。
 *
 * <p><b>INSERT 处理策略</b>：块内遇到 INSERT 实体时，<em>不展开</em>，
 * 而是创建占位 CADEntity（type=INSERT，geometry=插入点，properties 保存变换参数）。
 * 这样做的原因：BLOCKS 段解析时 ctx.blocks 尚未完整，强行展开会产生鸡生蛋问题。
 * InsertHandler 在 ENTITIES 阶段展开时，ctx.blocks 已完整，可安全递归。
 *
 * <p>重要块说明：
 * <ul>
 *   <li>{@code *Model_Space} - R2000+ 的模型空间实体（由 CADParser 路由）</li>
 *   <li>{@code *Paper_Space} - 图纸空间实体（由 PaperSpaceFilter 过滤）</li>
 *   <li>普通块 - INSERT 引用时由 InsertHandler 展开</li>
 * </ul>
 */
public class BlocksParser {

    private final EntityDispatcher dispatcher = new EntityDispatcher();
    private final EntitiesParser helper = new EntitiesParser(); // 复用 collectBuffer

    public void parse(DXFReader reader, DXFContext ctx) throws IOException {
        CADBlock currentBlock = null;

        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() != 0) continue;

            switch (pair.value()) {
                case "ENDSEC":
                    return;

                case "BLOCK":
                    currentBlock = parseBlockHeader(reader, ctx);
                    break;

                case "ENDBLK":
                    consumeUntilCode0(reader);
                    currentBlock = null;
                    break;

                default:
                    if (currentBlock != null) {
                        parseBlockEntity(pair.value(), reader, currentBlock, ctx);
                    } else {
                        consumeUntilCode0(reader); // 意外的实体（格式异常）
                    }
                    break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // 块头部解析
    // -------------------------------------------------------------------------

    private CADBlock parseBlockHeader(DXFReader reader, DXFContext ctx) throws IOException {
        String name = null;
        double bx = 0, by = 0, bz = 0;

        while (reader.hasNext()) {
            GroupCodePair p = reader.next();
            if (p == null) break;
            if (p.code() == 0) { reader.pushBack(p); break; } // 实体开始
            switch (p.code()) {
                case 2: if (name == null) name = p.value(); break; // 块名
                case 10: bx = p.asDouble(); break;
                case 20: by = p.asDouble(); break;
                case 30: bz = p.asDouble(); break;
                // code 3 = 块名重复（忽略），code 8 = 图层，code 70 = 标志
            }
        }
        if (name == null) return null;

        CADBlock block = new CADBlock(name);
        block.setInsertionPoint(bx, by, bz);
        ctx.blocks.put(name, block);
        return block;
    }

    // -------------------------------------------------------------------------
    // 块内实体解析
    // -------------------------------------------------------------------------

    private void parseBlockEntity(String entityType, DXFReader reader,
                                   CADBlock block, DXFContext ctx) throws IOException {
        EntityBuffer buffer = helper.collectBuffer(reader);

        // POLYLINE 和带属性的 INSERT 需要收集子实体
        if ("POLYLINE".equals(entityType)) {
            helper.collectChildEntities(reader, buffer, Set.of("VERTEX"));
        } else if ("INSERT".equals(entityType) && buffer.getInt(66, 0) == 1) {
            helper.collectChildEntities(reader, buffer, Set.of("ATTRIB"));
        }

        if ("INSERT".equals(entityType)) {
            // 块内 INSERT：创建占位实体，保存变换参数，不展开
            // （展开由 InsertHandler 在 ENTITIES 阶段完成，届时 ctx.blocks 已完整）
            block.addEntity(buildInsertPlaceholder(buffer));
        } else {
            // 普通实体：正常分发
            List<CADEntity> entities = dispatcher.dispatch(entityType, buffer, ctx);
            if (entities != null) entities.forEach(block::addEntity);
        }
    }

    /**
     * 为块内 INSERT 创建占位实体。
     * 保存了展开所需的全部变换参数，InsertHandler 在运行时读取这些 properties。
     */
    private CADEntity buildInsertPlaceholder(EntityBuffer buffer) {
        String handle    = buffer.getString(5, "");
        String layer     = buffer.getString(8, "0");
        String blockName = buffer.getString(2, "");
        double ix  = buffer.getDouble(10, 0);
        double iy  = buffer.getDouble(20, 0);
        double iz  = buffer.getDouble(30, 0);
        double sx  = buffer.getDouble(41, 1.0);
        double sy  = buffer.getDouble(42, 1.0);
        double sz  = buffer.getDouble(43, 1.0);
        double rot = buffer.getDouble(50, 0);

        return CADEntity.builder("INSERT")
                .handle(handle)
                .layer(layer)
                .geometry(GeometryBuilder.factory().createPoint(new Coordinate(ix, iy, iz)))
                .property("blockName", blockName)
                .property("scaleX",    sx)
                .property("scaleY",    sy)
                .property("scaleZ",    sz)
                .property("rotation",  rot)
                .build();
    }

    /** 消费直到遇到下一个 code=0（pushBack 该 code=0 供外层读取）。 */
    private void consumeUntilCode0(DXFReader reader) throws IOException {
        while (reader.hasNext()) {
            GroupCodePair p = reader.next();
            if (p == null || p.code() == 0) {
                if (p != null) reader.pushBack(p);
                break;
            }
        }
    }
}
