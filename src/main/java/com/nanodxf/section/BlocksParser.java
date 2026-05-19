package com.nanodxf.section;

import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.model.CADBlock;
import com.nanodxf.model.DXFContext;

import java.io.IOException;

/**
 * BLOCKS section 解析器。
 *
 * <p>读取所有块定义，填充 DXFContext.blocks。
 * 重要块：
 * <ul>
 *   <li>{@code *Model_Space} - R2000+ 的模型空间实体存放在此块中</li>
 *   <li>{@code *Paper_Space} - 图纸空间，需通过 PaperSpaceFilter 过滤</li>
 *   <li>普通块 - 被 INSERT 实体引用，展开时递归处理</li>
 * </ul>
 *
 * <p>TODO Phase 1：完整实现，包含 BLOCK/ENDBLK 边界处理和块内实体收集。
 */
public class BlocksParser {

    public void parse(DXFReader reader, DXFContext ctx) throws IOException {
        CADBlock currentBlock = null;

        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0 && "ENDSEC".equals(pair.value())) break;

            if (pair.code() == 0 && "BLOCK".equals(pair.value())) {
                currentBlock = null;
                // 读取块头部属性直到实体开始
                while (reader.hasNext()) {
                    GroupCodePair bp = reader.next();
                    if (bp == null || bp.code() == 0) {
                        if (bp != null) reader.pushBack(bp);
                        break;
                    }
                    if (bp.code() == 2 && currentBlock == null) {
                        currentBlock = new CADBlock(bp.value());
                        ctx.blocks.put(bp.value(), currentBlock);
                    } else if (currentBlock != null) {
                        switch (bp.code()) {
                            case 10 -> currentBlock.setInsertionPoint(
                                    bp.asDouble(), currentBlock.getInsertY(), currentBlock.getInsertZ());
                            case 20 -> currentBlock.setInsertionPoint(
                                    currentBlock.getInsertX(), bp.asDouble(), currentBlock.getInsertZ());
                            case 30 -> currentBlock.setInsertionPoint(
                                    currentBlock.getInsertX(), currentBlock.getInsertY(), bp.asDouble());
                        }
                    }
                }
            }
            // ENDBLK 和块内实体 TODO Phase 1
        }
    }
}
