package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.layer.CADLayer;
import com.nanodxf.layer.LineTypeDef;
import com.nanodxf.layer.TextStyle;
import com.nanodxf.model.DrawingMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DXF 解析结果，由 {@link CADParser#parse} 返回。
 *
 * <p>包含以下数据：
 * <ul>
 *   <li>{@link #getEntities()}    — 模型空间实体列表（INSERT 已递归展开）</li>
 *   <li>{@link #getErrors()}      — 分级错误列表（WARN/INFO，不含 FATAL）</li>
 *   <li>{@link #getMetadata()}    — 图纸元数据（版本、单位、CRS 等）</li>
 *   <li>{@link #getStats()}       — 解析统计（耗时、实体数、错误数）</li>
 *   <li>{@link #getLayers()}      — 图层表（来自 TABLES 段）</li>
 *   <li>{@link #getTextStyles()}  — 文字样式表</li>
 *   <li>{@link #getLineTypes()}   — 线型定义表</li>
 * </ul>
 *
 * <p>所有集合均不可变，通过 {@link Builder} 在解析完成后一次性构建。
 */
public class ParseResult {

    /** 模型空间实体（不可变），已展开所有 INSERT 块引用。 */
    private final List<CADEntity> entities;

    /** 解析错误列表（不可变），包含 WARN 和 INFO 级别，FATAL 会直接抛出异常不进入此列表。 */
    private final List<ParseError> errors;

    /** 图纸元数据，来自 HEADER 段和调用方 ParseConfig。 */
    private final DrawingMetadata metadata;

    /** 解析统计摘要。 */
    private final ParseStats stats;

    /** 图层表（不可变），key=图层名，来自 TABLES 段。 */
    private final Map<String, CADLayer> layers;

    /** 文字样式表（不可变），key=样式名。 */
    private final Map<String, TextStyle> textStyles;

    /** 线型定义表（不可变），key=线型名。 */
    private final Map<String, LineTypeDef> lineTypes;

    /** 空间索引（懒建，首次调用 {@link #index()} 时初始化）。 */
    private final AtomicReference<EntityIndex> indexRef = new AtomicReference<>();

    private ParseResult(Builder builder) {
        this.entities   = Collections.unmodifiableList(new ArrayList<>(builder.entities));
        this.errors     = Collections.unmodifiableList(new ArrayList<>(builder.errors));
        this.metadata   = builder.metadata;
        this.stats      = builder.stats;
        this.layers     = Collections.unmodifiableMap(new LinkedHashMap<>(builder.layers));
        this.textStyles = Collections.unmodifiableMap(new LinkedHashMap<>(builder.textStyles));
        this.lineTypes  = Collections.unmodifiableMap(new LinkedHashMap<>(builder.lineTypes));
    }

    /** 返回模型空间实体列表（不可变），INSERT 块已递归展开为实际几何实体。 */
    public List<CADEntity> getEntities() { return entities; }

    /** 返回错误列表（不可变）。可按 {@link ParseErrorLevel} 过滤查看不同严重程度的问题。 */
    public List<ParseError> getErrors()  { return errors; }

    /** 返回图纸元数据（版本、单位制、CRS、等高距等）。 */
    public DrawingMetadata getMetadata() { return metadata; }

    /** 返回解析统计（耗时、实体数、错误数）。 */
    public ParseStats getStats()         { return stats; }

    /** 返回图层表（不可变），key=图层名。包含颜色号、可见性、线型名等属性。 */
    public Map<String, CADLayer> getLayers()     { return layers; }

    /** 返回文字样式表（不可变），key=样式名。包含字体文件、字高、宽度系数。 */
    public Map<String, TextStyle> getTextStyles() { return textStyles; }

    /** 返回线型定义表（不可变），key=线型名。 */
    public Map<String, LineTypeDef> getLineTypes() { return lineTypes; }

    /**
     * 返回基于 JTS STRtree 的实体空间索引（懒建，线程安全）。
     *
     * <p>首次调用触发建树，耗时 O(n log n)；后续调用直接返回已有索引。
     * 索引支持包围盒查询 ({@link EntityIndex#query})、
     * 图层过滤 ({@link EntityIndex#byLayer})、
     * 类型过滤 ({@link EntityIndex#byType})。
     */
    public EntityIndex index() {
        EntityIndex existing = indexRef.get();
        if (existing != null) return existing;
        EntityIndex created = new EntityIndex(entities);
        indexRef.compareAndSet(null, created);
        return indexRef.get();
    }

    public static Builder builder() { return new Builder(); }

    /** 内部构建器，由 {@link CADParser} 在解析完成后填充并调用 {@link #build()}。 */
    public static class Builder {
        private final List<CADEntity> entities = new ArrayList<>();
        private final List<ParseError> errors  = new ArrayList<>();
        private final Map<String, CADLayer> layers     = new LinkedHashMap<>();
        private final Map<String, TextStyle> textStyles = new LinkedHashMap<>();
        private final Map<String, LineTypeDef> lineTypes = new LinkedHashMap<>();
        private DrawingMetadata metadata;
        private ParseStats stats;

        public Builder addEntity(CADEntity e)    { entities.add(e); return this; }
        public Builder addError(ParseError e)    { errors.add(e);   return this; }
        public Builder metadata(DrawingMetadata m) { this.metadata = m; return this; }
        public Builder stats(ParseStats s)       { this.stats = s;  return this; }
        public Builder layers(Map<String, CADLayer> m)       { this.layers.putAll(m);       return this; }
        public Builder textStyles(Map<String, TextStyle> m)  { this.textStyles.putAll(m);   return this; }
        public Builder lineTypes(Map<String, LineTypeDef> m) { this.lineTypes.putAll(m);    return this; }
        public ParseResult build()               { return new ParseResult(this); }
    }
}
