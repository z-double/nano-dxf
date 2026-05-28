package com.nanodxf.entity;

import com.nanodxf.entity.handler.*;
import com.nanodxf.model.DXFContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 根据实体类型字符串将 EntityBuffer 分发到对应的 {@link EntityHandler}。
 *
 * <p>内置处理器在构造方法中硬编码注册；外部扩展可通过两种方式注入：
 * <ul>
 *   <li>{@link #register}：编程式注册，直接调用</li>
 *   <li>{@link EntityHandlerProvider} SPI：在第三方 JAR 的
 *       {@code META-INF/services/com.nanodxf.entity.EntityHandlerProvider}
 *       中声明实现类，构造时自动通过 {@link ServiceLoader} 加载</li>
 * </ul>
 *
 * <p>{@link #dispatch} 返回：
 * <ul>
 *   <li>非 null 列表 — handler 正常调用结果（可为空列表）</li>
 *   <li>null — 实体类型未注册（调用方负责记录到 skippedEntityTypes）</li>
 * </ul>
 */
public class EntityDispatcher {

    private final Map<String, EntityHandler> handlers = new HashMap<>();

    public EntityDispatcher() {
        // Phase 1 基础实体
        register("LINE",          new LineHandler());
        register("ARC",           new ArcHandler());
        register("CIRCLE",        new CircleHandler());
        register("POINT",         new PointHandler());
        register("TEXT",          new TextHandler());
        register("LWPOLYLINE",    new LWPolylineHandler());

        // Phase 2 复杂实体
        register("ELLIPSE",       new EllipseHandler());
        register("POLYLINE",      new PolylineHandler());
        register("SPLINE",        new SplineHandler());
        register("MTEXT",         new MTextHandler());
        register("INSERT",        new InsertHandler());
        register("ATTRIB",        new AttribHandler());
        register("ATTDEF",        new AttDefHandler());
        register("HATCH",         new HatchHandler());
        register("DIMENSION",     new DimensionHandler());
        register("3DFACE",        new ThreeDFaceHandler());
        register("SOLID",         new SolidHandler());

        // 引线实体
        register("LEADER",        new LeaderHandler());
        register("MULTILEADER",   new MultiLeaderHandler());  // v1.4.0 实现

        // v1.5.0 新增实体 handler
        register("MLINE",         new MlineHandler());
        register("WIPEOUT",       new WipeoutHandler());
        register("IMAGE",         new ImageHandler());
        register("TOLERANCE",     new ToleranceHandler());

        register("VIEWPORT",      EntityHandler.SKIP);

        // SPI 扩展：第三方 jar 通过 META-INF/services 注册自定义 handler
        // 若与内置类型同名则覆盖内置实现，允许定制解析逻辑
        ServiceLoader.load(EntityHandlerProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .flatMap(p -> p.handlers().entrySet().stream())
                .forEach(e -> register(e.getKey(), e.getValue()));
    }

    /** 注册或覆盖指定实体类型的处理器（不区分大小写，内部转大写）。 */
    public void register(String entityType, EntityHandler handler) {
        handlers.put(entityType.toUpperCase(), handler);
    }

    /**
     * 分发 buffer 到对应处理器。
     *
     * @return handler 返回的实体列表；若类型未注册返回 null（由调用方区分"跳过"与"未知"）
     */
    public List<CADEntity> dispatch(String entityType, EntityBuffer buffer, DXFContext ctx) {
        EntityHandler handler = handlers.get(entityType.toUpperCase());
        if (handler == null) return null; // 未注册类型
        return handler.handle(buffer, ctx);
    }

    /** 检查指定实体类型是否已注册（含注册为 SKIP 的类型）。 */
    public boolean isKnown(String entityType) {
        return handlers.containsKey(entityType.toUpperCase());
    }
}
