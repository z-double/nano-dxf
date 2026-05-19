package com.nanodxf.entity;

import com.nanodxf.entity.handler.*;
import com.nanodxf.model.DXFContext;

import java.util.HashMap;
import java.util.Map;

/**
 * 根据实体类型字符串将 EntityBuffer 分发到对应的 {@link EntityHandler}。
 *
 * <p>内置处理器在构造方法中硬编码注册。
 * 外部扩展通过 {@link #register} 方法注入（可与 ServiceLoader SPI 配合使用，
 * 支持第三方自定义实体，如国内测绘软件的私有实体类型）。
 *
 * <p>遇到未注册的实体类型时 {@link #dispatch} 返回 null，
 * 调用方应将该类型记录到 QualityReport.skippedEntityTypes，以便后续扩展。
 */
public class EntityDispatcher {
    private final Map<String, EntityHandler> handlers = new HashMap<>();

    public EntityDispatcher() {
        // Phase 1 基础实体
        register("LINE",        new LineHandler());
        register("ARC",         new ArcHandler());
        register("CIRCLE",      new CircleHandler());
        register("POINT",       new PointHandler());
        register("TEXT",        new TextHandler());
        register("LWPOLYLINE",  new LWPolylineHandler());

        // Phase 2 复杂实体
        register("ELLIPSE",     new EllipseHandler());
        register("POLYLINE",    new PolylineHandler());
        register("SPLINE",      new SplineHandler());
        register("MTEXT",       new MTextHandler());
        register("INSERT",      new InsertHandler());
        register("ATTRIB",      new AttribHandler());
        register("HATCH",       new HatchHandler());
        register("DIMENSION",   new DimensionHandler());
        register("3DFACE",      new ThreeDFaceHandler());
        register("SOLID",       new SolidHandler());

        // 图纸布局实体，不属于测量数据，直接跳过
        register("LEADER",      EntityHandler.SKIP);
        register("MULTILEADER", EntityHandler.SKIP);
        register("VIEWPORT",    EntityHandler.SKIP);
    }

    /**
     * 注册或覆盖指定实体类型的处理器。
     *
     * @param entityType 实体类型字符串（不区分大小写，内部统一转大写）
     * @param handler    对应的解析器；传入 {@link EntityHandler#SKIP} 表示跳过
     */
    public void register(String entityType, EntityHandler handler) {
        handlers.put(entityType.toUpperCase(), handler);
    }

    /**
     * 将 buffer 分发到对应处理器并返回解析结果。
     *
     * @return 解析得到的 CADEntity；若类型未注册或 handler 返回 null，则返回 null
     */
    public CADEntity dispatch(String entityType, EntityBuffer buffer, DXFContext ctx) {
        EntityHandler handler = handlers.get(entityType.toUpperCase());
        if (handler == null) return null;
        return handler.handle(buffer, ctx);
    }

    /** 检查指定实体类型是否已注册（包括注册为 SKIP 的类型）。 */
    public boolean isKnown(String entityType) {
        return handlers.containsKey(entityType.toUpperCase());
    }
}
