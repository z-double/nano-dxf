package com.nanodxf.filter;

import com.nanodxf.entity.EntityBuffer;

/**
 * 图纸空间过滤器。
 *
 * <p>图纸空间（Paper Space）存放图框、标题栏、视口等布局元素，
 * 不是测量数据，必须在进入 handler 之前过滤掉，否则会污染 GeoJSON 输出。
 *
 * <p>判断方式（三种互补）：
 * <ol>
 *   <li>实体 code 67 = 1 → 图纸空间（最直接）</li>
 *   <li>实体所在块名以 {@code *Paper_Space} 开头 → 图纸空间（R2000+，由 BlocksParser 处理）</li>
 *   <li>实体类型为 VIEWPORT → 始终跳过（已在 EntityDispatcher 中注册为 SKIP）</li>
 * </ol>
 *
 * <p>此过滤器只处理方式 1，方式 2 由 EntitiesParser 根据 block 上下文判断。
 */
public class PaperSpaceFilter {

    /**
     * 检查实体是否属于模型空间。
     *
     * @param buffer 实体的 group code 缓冲区
     * @return true=模型空间（保留），false=图纸空间（过滤）
     */
    public static boolean isModelSpace(EntityBuffer buffer) {
        // code 67 缺失（默认 0）或 = 0 → 模型空间；= 1 → 图纸空间
        return buffer.getInt(67, 0) != 1;
    }
}
