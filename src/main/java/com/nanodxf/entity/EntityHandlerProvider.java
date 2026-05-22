package com.nanodxf.entity;

import java.util.Map;

/**
 * SPI 接口：向 {@link EntityDispatcher} 注入自定义实体处理器。
 *
 * <p>使用 Java {@link java.util.ServiceLoader} 机制加载。
 * 在第三方 JAR 的 {@code META-INF/services/com.nanodxf.entity.EntityHandlerProvider}
 * 文件中声明实现类全限定名，即可在解析器启动时自动注册：
 *
 * <pre>{@code
 * // META-INF/services/com.nanodxf.entity.EntityHandlerProvider 内容：
 * com.example.MyEntityHandlerProvider
 *
 * // 实现示例：
 * public class MyEntityHandlerProvider implements EntityHandlerProvider {
 *     public Map<String, EntityHandler> handlers() {
 *         return Map.of(
 *             "MY_ENTITY", new MyEntityHandler(),
 *             "MY_OTHER",  new MyOtherHandler()
 *         );
 *     }
 * }
 * }</pre>
 *
 * <p>若第三方处理器与内置处理器注册的类型名称相同，第三方处理器将<b>覆盖</b>内置实现。
 * 这允许调用方替换默认解析逻辑（如定制 LWPOLYLINE 处理方式）。
 *
 * <p>注意：SPI 处理器在每次创建 {@link EntityDispatcher} 时加载一次，
 * 运行时热替换不受支持。
 */
public interface EntityHandlerProvider {

    /**
     * 返回此提供者希望注册的实体类型 → 处理器映射。
     *
     * @return 键为 DXF 实体类型字符串（大小写不敏感，如 {@code "MY_ENTITY"}），
     *         值为对应的 {@link EntityHandler} 实现；不得返回 null
     */
    Map<String, EntityHandler> handlers();
}
