package com.nanodxf.entity;

import com.nanodxf.model.DXFContext;

/**
 * DXF 实体解析器接口。
 *
 * <p>设计原则：handler <strong>不直接操作 DXFReader</strong>。
 * EntityDispatcher 负责将单个实体的所有 group code 收集到 {@link EntityBuffer} 后，
 * 再传入 handler。handler 是纯函数，输入确定输出确定，便于独立单元测试。
 *
 * <p>每个内置实体类型（LINE、ARC、CIRCLE 等）对应一个实现类，
 * 外部扩展通过 ServiceLoader SPI 注入（见 EntityDispatcher）。
 *
 * <p>对于不需要解析的实体类型（如 VIEWPORT），使用 {@link #SKIP} 常量：
 * <pre>{@code
 * dispatcher.register("VIEWPORT", EntityHandler.SKIP);
 * }</pre>
 */
@FunctionalInterface
public interface EntityHandler {

    /** 跳过指定实体类型，不产生 CADEntity 输出。 */
    EntityHandler SKIP = (buffer, ctx) -> null;

    /**
     * 将 EntityBuffer 中的 group code 解析为 CADEntity。
     *
     * @param buffer 包含该实体全部 group code 的缓冲区，只读
     * @param ctx    解析上下文（图层表、块定义等），只读
     * @return 解析结果；无法解析时返回 null（调用方记录 WARN 并跳过）
     */
    CADEntity handle(EntityBuffer buffer, DXFContext ctx);
}
