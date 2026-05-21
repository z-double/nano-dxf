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

    /**
     * 返回 JTS 几何对象（JavaBean 风格别名，等价于 {@link #geometry()}）。
     * 供反射框架、IDE 自动补全等场景使用。
     */
    public Geometry getGeometry() { return geometry; }

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

    /**
     * DXF 实体类型字符串常量，与 {@link CADEntity#getType()} 返回值严格一致（始终大写）。
     *
     * <h3>用途</h3>
     * <ul>
     *   <li><b>解析侧过滤</b>：替代魔法字符串，防止拼写错误引发的静默 bug：
     *       <pre>{@code
     * result.getEntities().stream()
     *     .filter(e -> CADEntity.Types.LWPOLYLINE.equals(e.getType()))
     *     .forEach(e -> process(e.geometry()));
     *       }</pre></li>
     *   <li><b>写出侧构建</b>：与 {@link Builder} 配合使用：
     *       <pre>{@code
     * CADEntity.builder(CADEntity.Types.LINE)
     *     .layer("道路").geometry(lineGeom)
     *     .property("colorAci", 7).build();
     *       }</pre></li>
     *   <li><b>switch 分支</b>：Java 21+ 字符串模式匹配时可直接引用常量。</li>
     * </ul>
     *
     * <h3>解析器支持情况</h3>
     * 下表列出每种类型在本库中的解析输出几何，以及写出时对应的 DXF 实体：
     * <table border="1">
     *   <tr><th>常量</th><th>解析输出几何</th><th>写出实体</th></tr>
     *   <tr><td>LINE</td><td>LineString（2点）</td><td>LINE</td></tr>
     *   <tr><td>ARC</td><td>LineString（弦高离散）</td><td>ARC（需 geometry=Point + radius/startAngle/endAngle）</td></tr>
     *   <tr><td>CIRCLE</td><td>LinearRing（闭合）</td><td>CIRCLE（需 geometry=Point + radius）</td></tr>
     *   <tr><td>ELLIPSE</td><td>LineString / LinearRing</td><td>—</td></tr>
     *   <tr><td>POINT</td><td>Point（含 Z 高程）</td><td>POINT</td></tr>
     *   <tr><td>LWPOLYLINE</td><td>LineString / LinearRing / Polygon</td><td>LWPOLYLINE</td></tr>
     *   <tr><td>POLYLINE</td><td>LineString / LinearRing</td><td>—</td></tr>
     *   <tr><td>SPLINE</td><td>LineString（de Boor 离散）</td><td>—</td></tr>
     *   <tr><td>TEXT</td><td>Point（插入点）</td><td>TEXT</td></tr>
     *   <tr><td>MTEXT</td><td>Point（插入点）</td><td>MTEXT</td></tr>
     *   <tr><td>HATCH</td><td>Polygon（外环+洞）</td><td>HATCH SOLID（需 geometry=Polygon）</td></tr>
     *   <tr><td>INSERT</td><td>展开为子实体集合</td><td>INSERT（需 geometry=Point + blockName）</td></tr>
     *   <tr><td>DIMENSION</td><td>Point（标注插入点）</td><td>—</td></tr>
     * </table>
     *
     * <p>写出列中 "—" 表示当前版本 {@link com.nanodxf.output.DXFWriter} 暂不单独支持该类型，
     * 传入时几何会按 JTS 类型自动匹配（LineString → LWPOLYLINE、Point → POINT 等）。
     *
     * <p><b>特殊命名</b>：{@link #FACE3D} 的字符串值是 {@code "3DFACE"}；
     * 因 Java 标识符不能以数字开头，常量名改为 {@code FACE3D}，使用时注意区分。
     */
    public static final class Types {
        private Types() {}

        // ---- 基础几何实体 ----

        /** 直线段，两个端点。解析输出 {@code LineString}（2点）。 */
        public static final String LINE       = "LINE";

        /** 圆弧（起始角 → 终止角）。解析按弦高误差离散为 {@code LineString}。 */
        public static final String ARC        = "ARC";

        /** 整圆。解析为闭合 {@code LinearRing}。 */
        public static final String CIRCLE     = "CIRCLE";

        /** 椭圆或椭圆弧（参数方程定义）。解析按弦高误差离散。 */
        public static final String ELLIPSE    = "ELLIPSE";

        /** 测量点，通常携带 Z 高程（{@code elevation} 属性）。解析为 {@code Point}。 */
        public static final String POINT      = "POINT";

        // ---- 多段线族 ----

        /**
         * 轻量级多段线（R2000+）。支持 bulge 凸度（圆弧段）和 elevation（code 38）。
         * 解析按几何类型输出 {@code LineString}（开放）或 {@code LinearRing}（闭合）。
         * 写出时为推荐类型：{@link com.nanodxf.output.DXFWriter} 将 LineString/LinearRing 映射到此类型。
         */
        public static final String LWPOLYLINE = "LWPOLYLINE";

        /**
         * 经典多段线（R12 时代）。通过 VERTEX + SEQEND 定义顶点序列。
         * 3D 折线（code 70 bit 1=1）保留 Z 坐标。
         */
        public static final String POLYLINE   = "POLYLINE";

        /** POLYLINE 的顶点记录，不单独出现，由 {@link #POLYLINE} 解析时内部消费。 */
        public static final String VERTEX     = "VERTEX";

        /** 多段线/块定义的结束标记，不单独出现，由解析器内部消费。 */
        public static final String SEQEND     = "SEQEND";

        /** B 样条曲线，使用 de Boor 算法离散为 {@code LineString}。 */
        public static final String SPLINE     = "SPLINE";

        // ---- 文字 ----

        /**
         * 单行文字。属性 {@code text} 为文字内容，{@code height} 为字高，
         * {@code rotation} 为旋转角度（度）。解析输出插入点 {@code Point}。
         */
        public static final String TEXT       = "TEXT";

        /**
         * 多行富文本。属性 {@code text} 为 MText 清洗后内容（格式码已去除，
         * {@code \P} 换行替换为空格）。解析输出插入点 {@code Point}。
         */
        public static final String MTEXT      = "MTEXT";

        /**
         * 块属性值，随 INSERT 一起出现，包含属性标签（{@code tag}）和值（{@code text}）。
         * 解析后注入到 INSERT 展开的实体 {@code properties} 中。
         */
        public static final String ATTRIB     = "ATTRIB";

        // ---- 填充 / 面实体 ----

        /**
         * 填充区域，支持多边界路径（外环 + 洞）。
         * 解析输出 {@code Polygon}（单边界）或 {@code MultiPolygon}（多边界）。
         */
        public static final String HATCH      = "HATCH";

        /**
         * 实体填充四边形（3 或 4 顶点）。
         * 解析输出 {@code Polygon}（4 顶点按 Z 字形排列，代码内部重排为正确顺序）。
         */
        public static final String SOLID      = "SOLID";

        /**
         * 3D 平面（三角形或四边形面片）。
         * <b>字符串值为 {@code "3DFACE"}，非此常量名</b>。
         * 解析输出 5 点闭合 {@code LinearRing}。
         */
        public static final String FACE3D     = "3DFACE";

        // ---- 块定义与引用 ----

        /**
         * 块引用（实例化）。解析时递归展开块内实体，施加仿射变换（缩放→旋转→平移）。
         * 展开结果直接加入实体列表，原始 INSERT 不出现在 {@code ParseResult.getEntities()} 中。
         */
        public static final String INSERT     = "INSERT";

        /** 块定义开始标记（BLOCKS 段内部使用），解析器内部消费，不出现在输出实体中。 */
        public static final String BLOCK      = "BLOCK";

        /** 块定义结束标记（BLOCKS 段内部使用），解析器内部消费，不出现在输出实体中。 */
        public static final String ENDBLK     = "ENDBLK";

        // ---- 标注 / 视图 ----

        /**
         * 尺寸标注。当前版本仅提取标注插入点，输出 {@code Point}。
         * 具体标注文字、测量值等存于 {@code properties}。
         */
        public static final String DIMENSION  = "DIMENSION";

        /**
         * 引线标注（指引线）。解析支持取点，当前版本输出 {@code LineString} 或 {@code Point}。
         */
        public static final String LEADER     = "LEADER";

        /**
         * 视口对象（图纸空间）。解析时通过 code 67 过滤，模型空间输出中不包含此类型。
         * 仅在需要处理图纸空间的高级场景中使用。
         */
        public static final String VIEWPORT   = "VIEWPORT";
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
