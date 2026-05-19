package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.DrawingMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DXF 解析结果，由 {@link CADParser#parse} 返回。
 *
 * <p>包含四部分数据：
 * <ul>
 *   <li>{@link #getEntities()} — 模型空间实体列表（INSERT 已递归展开）</li>
 *   <li>{@link #getErrors()}   — 分级错误列表（WARN/INFO，不含 FATAL）</li>
 *   <li>{@link #getMetadata()} — 图纸元数据（版本、单位、CRS 等）</li>
 *   <li>{@link #getStats()}    — 解析统计（耗时、实体数、错误数）</li>
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

    private ParseResult(Builder builder) {
        this.entities = Collections.unmodifiableList(new ArrayList<>(builder.entities));
        this.errors   = Collections.unmodifiableList(new ArrayList<>(builder.errors));
        this.metadata = builder.metadata;
        this.stats    = builder.stats;
    }

    /** 返回模型空间实体列表（不可变），INSERT 块已递归展开为实际几何实体。 */
    public List<CADEntity> getEntities() { return entities; }

    /** 返回错误列表（不可变）。可按 {@link ParseErrorLevel} 过滤查看不同严重程度的问题。 */
    public List<ParseError> getErrors()  { return errors; }

    /** 返回图纸元数据（版本、单位制、CRS、等高距等）。 */
    public DrawingMetadata getMetadata() { return metadata; }

    /** 返回解析统计（耗时、实体数、错误数）。 */
    public ParseStats getStats()         { return stats; }

    public static Builder builder() { return new Builder(); }

    /** 内部构建器，由 {@link CADParser} 在解析完成后填充并调用 {@link #build()}。 */
    public static class Builder {
        private final List<CADEntity> entities = new ArrayList<>();
        private final List<ParseError> errors  = new ArrayList<>();
        private DrawingMetadata metadata;
        private ParseStats stats;

        public Builder addEntity(CADEntity e)    { entities.add(e); return this; }
        public Builder addError(ParseError e)    { errors.add(e);   return this; }
        public Builder metadata(DrawingMetadata m) { this.metadata = m; return this; }
        public Builder stats(ParseStats s)       { this.stats = s;  return this; }
        public ParseResult build()               { return new ParseResult(this); }
    }
}
