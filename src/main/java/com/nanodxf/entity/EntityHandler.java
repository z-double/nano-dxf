package com.nanodxf.entity;

import com.nanodxf.model.DXFContext;

import java.util.List;

/**
 * DXF 实体解析器接口。
 *
 * <p>设计原则：handler <strong>不直接操作 DXFReader</strong>。
 * EntityDispatcher 负责将单个实体的所有 group code 收集到 {@link EntityBuffer} 后，
 * 再传入 handler。handler 是纯函数，输入确定输出确定，便于独立单元测试。
 *
 * <p>返回值语义：
 * <ul>
 *   <li>单元素列表 — 正常解析，产出一个实体（绝大多数 handler）</li>
 *   <li>多元素列表 — INSERT 块展开，一个块引用扩展为多个实体</li>
 *   <li>空列表 — 有意跳过（零长度线段、不支持的类型等），不记录错误</li>
 * </ul>
 */
@FunctionalInterface
public interface EntityHandler {

    /** 跳过指定实体类型，不产生任何 CADEntity 输出，不记录错误。 */
    EntityHandler SKIP = (buffer, ctx) -> List.of();

    /**
     * 将 EntityBuffer 中的 group code 解析为 CADEntity 列表。
     *
     * @param buffer 包含该实体全部 group code 的缓冲区，只读
     * @param ctx    解析上下文（图层表、块定义等），只读（InsertHandler 可访问 blocks）
     * @return 解析结果列表；空列表表示有意跳过，不产生错误
     */
    List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx);
}
