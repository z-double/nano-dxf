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
 *   <li>{@code properties} - 扩展属性，如 elevation、featureCode、xdata</li>
 * </ul>
 *
 * <p>使用 {@link Builder} 创建实例，使用 {@link #withGeometry} 生成带新几何的副本：
 * <pre>{@code
 * CADEntity entity = CADEntity.builder("LINE")
 *     .handle("1A3F")
 *     .layer("道路")
 *     .geometry(line)
 *     .property("elevation", 125.3)
 *     .build();
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

    /** DXF 实体类型，如 "LINE"、"LWPOLYLINE"（大写）。 */
    public String getType() { return type; }

    /** DXF 实体 handle（code 5），文件内唯一标识，可用于追踪错误。 */
    public String getHandle() { return handle; }

    /** 所属图层名（code 8）。 */
    public String getLayer() { return layer; }

    /**
     * JTS 几何对象。
     * 当几何无效且无法修复时为 null，此时 properties 仍有效（不丢弃实体数据）。
     */
    public Geometry geometry() { return geometry; }

    /** 扩展属性，包括 elevation、featureCode、xdata、objectData 等。 */
    public Map<String, Object> getProperties() { return properties; }

    /**
     * 返回一个几何替换为 {@code newGeometry} 的不可变副本，其他字段不变。
     * 通常用于几何修复后替换原始几何，或在校验失败后置 null。
     */
    public CADEntity withGeometry(Geometry newGeometry) {
        return new Builder(this).geometry(newGeometry).build();
    }

    public static Builder builder(String type) {
        return new Builder(type);
    }

    public static class Builder {
        private final String type;
        private String handle = "";
        private String layer = "0";
        private Geometry geometry;
        private final Map<String, Object> properties = new HashMap<>();

        public Builder(String type) { this.type = type; }

        /** 从已有实体复制，用于 withGeometry 等"变体"构造。 */
        private Builder(CADEntity src) {
            this.type = src.type;
            this.handle = src.handle;
            this.layer = src.layer;
            this.geometry = src.geometry;
            this.properties.putAll(src.properties);
        }

        public Builder handle(String handle) { this.handle = handle; return this; }
        public Builder layer(String layer) { this.layer = layer; return this; }
        public Builder geometry(Geometry geometry) { this.geometry = geometry; return this; }
        public Builder property(String key, Object value) { this.properties.put(key, value); return this; }

        public CADEntity build() { return new CADEntity(this); }
    }
}
