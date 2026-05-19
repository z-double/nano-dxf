package com.nanodxf.entity;

import org.locationtech.jts.geom.Geometry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 解析后的 CAD 实体，表示一个不可变的几何要素。
 *
 * <p>每个实体包含：
 * <ul>
 *   <li>{@code type}   - DXF 实体类型，如 "LINE"、"CIRCLE"（始终大写）</li>
 *   <li>{@code handle} - DXF 文件中的唯一标识符（code 5）</li>
 *   <li>{@code layer}  - 所属图层名（code 8）</li>
 *   <li>{@code geometry} - JTS 几何对象；几何无效时为 null，其余属性仍保留</li>
 *   <li>{@code properties} - 扩展属性，如 elevation、featureCode、xdata、colorRgb</li>
 * </ul>
 *
 * <p>使用 {@link Builder} 创建实例；使用 {@link #withGeometry}、{@link #withProperty}、
 * {@link #withProperties} 生成带修改内容的不可变副本（写时复制）：
 * <pre>{@code
 * CADEntity entity = CADEntity.builder("LINE")
 *     .handle("1A3F").layer("道路").geometry(line)
 *     .property("elevation", 125.3).build();
 *
 * CADEntity enriched = entity.withProperty("featureCode", "31010");
 * }</pre>
 */
public class CADEntity {
    private final String type;
    private final String handle;
    private final String layer;
    private final Geometry geometry;
    private final Map<String, Object> properties;

    private CADEntity(Builder builder) {
        this.type       = builder.type;
        this.handle     = builder.handle;
        this.layer      = builder.layer;
        this.geometry   = builder.geometry;
        this.properties = Collections.unmodifiableMap(new HashMap<>(builder.properties));
    }

    /** DXF 实体类型（大写），如 "LINE"、"LWPOLYLINE"。 */
    public String getType() { return type; }

    /** DXF 实体 handle（code 5），文件内唯一标识，用于追踪错误和关联对象。 */
    public String getHandle() { return handle; }

    /** 所属图层名（code 8）。 */
    public String getLayer() { return layer; }

    /**
     * JTS 几何对象。
     * 几何无效且无法修复时为 null；此时 properties 仍有效，不丢失实体数据。
     */
    public Geometry geometry() { return geometry; }

    /** 扩展属性（不可变视图），包括 elevation、featureCode、xdata 等。 */
    public Map<String, Object> getProperties() { return properties; }

    // -------------------------------------------------------------------------
    // 写时复制方法（不可变副本）
    // -------------------------------------------------------------------------

    /** 返回几何替换为 {@code newGeometry} 的不可变副本，其他字段不变。 */
    public CADEntity withGeometry(Geometry newGeometry) {
        return new Builder(this).geometry(newGeometry).build();
    }

    /** 返回新增或覆盖单个属性的不可变副本，其他字段不变。 */
    public CADEntity withProperty(String key, Object value) {
        return new Builder(this).property(key, value).build();
    }

    /**
     * 返回批量新增属性的不可变副本（与已有属性合并，同名 key 被覆盖），其他字段不变。
     * 常用于在 EntitiesParser 中富化 XDATA、颜色等跨切面属性。
     */
    public CADEntity withProperties(Map<String, Object> additionalProps) {
        Builder b = new Builder(this);
        additionalProps.forEach(b::property);
        return b.build();
    }

    public static Builder builder(String type) {
        return new Builder(type);
    }

    public static class Builder {
        private final String type;
        private String handle = "";
        private String layer  = "0";
        private Geometry geometry;
        private final Map<String, Object> properties = new HashMap<>();

        public Builder(String type) { this.type = type; }

        /** 从已有实体复制，用于 withXxx 写时复制。 */
        private Builder(CADEntity src) {
            this.type = src.type;
            this.handle = src.handle;
            this.layer  = src.layer;
            this.geometry = src.geometry;
            this.properties.putAll(src.properties);
        }

        public Builder handle(String handle)    { this.handle = handle; return this; }
        public Builder layer(String layer)      { this.layer  = layer;  return this; }
        public Builder geometry(Geometry g)     { this.geometry = g;    return this; }
        public Builder property(String k, Object v) { this.properties.put(k, v); return this; }

        public CADEntity build() { return new CADEntity(this); }
    }
}
