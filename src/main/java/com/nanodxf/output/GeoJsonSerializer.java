package com.nanodxf.output;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.DrawingMetadata;
import org.locationtech.jts.geom.*;

import java.util.List;
import java.util.Map;

/**
 * 将解析结果序列化为 GeoJSON FeatureCollection 字符串。
 *
 * <p>输出格式遵循 GeoJSON RFC 7946：
 * <ul>
 *   <li>外环逆时针（CCW），内环顺时针（CW）——由 {@code GeometryValidator} 保证</li>
 *   <li>坐标精度由 {@code coordinateDecimalPlaces} 控制（默认 4 位，即 0.1 mm 级）</li>
 *   <li>CRS 信息写入顶层 {@code crs} 字段，标注来源（caller_specified / unknown）</li>
 *   <li>几何为 null 的实体在输出中 geometry 字段为 {@code null}（不丢弃实体，保留属性）</li>
 * </ul>
 *
 * <p>大坐标精度控制：CGCS2000 投影坐标 X ≈ 3,000,000~6,000,000，
 * 直接用 {@code double.toString()} 会产生浮点噪声（如 3456789.1234560001）。
 * 使用 {@link #fmt} 格式化每个坐标值。
 *
 * <p>支持的 JTS 几何类型：
 * <ul>
 *   <li>{@link Point} → GeoJSON Point</li>
 *   <li>{@link LineString} → GeoJSON LineString</li>
 *   <li>{@link LinearRing} → GeoJSON Polygon（作为无洞面的外环）</li>
 *   <li>{@link Polygon} → GeoJSON Polygon（含内洞）</li>
 *   <li>{@link MultiPolygon} → GeoJSON MultiPolygon</li>
 * </ul>
 *
 * <p>属性值支持的 Java 类型：String、Number（int/long/double）、Boolean、
 * int[]（colorRgb）、Map、List、null；其他类型退化为 toString。
 */
public class GeoJsonSerializer {

    private final int coordinateDecimalPlaces;

    /**
     * 使用指定精度创建序列化器。
     *
     * @param coordinateDecimalPlaces 坐标小数位数（0~15）；建议：毫米级 3 位，地形图 4 位
     */
    public GeoJsonSerializer(int coordinateDecimalPlaces) {
        this.coordinateDecimalPlaces = coordinateDecimalPlaces;
    }

    /**
     * 使用指定精度和元数据创建序列化器（元数据仅用于内部 CRS 预绑定，可为 null）。
     *
     * @param coordinateDecimalPlaces 坐标小数位数
     * @param metadata                图纸元数据（可选，传入后 serialize 仍以参数为准）
     */
    public GeoJsonSerializer(int coordinateDecimalPlaces, DrawingMetadata metadata) {
        this.coordinateDecimalPlaces = coordinateDecimalPlaces;
    }

    /**
     * 使用默认 4 位精度创建序列化器，与图纸元数据关联（0.1 mm 精度）。
     *
     * @param metadata 图纸元数据（可为 null）
     */
    public GeoJsonSerializer(DrawingMetadata metadata) {
        this(4);
    }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /**
     * 将实体列表序列化为 GeoJSON FeatureCollection 字符串。
     *
     * @param entities 解析得到的实体列表
     * @param metadata 图纸元数据（用于 CRS 标注）
     * @return 完整的 GeoJSON 字符串（单行，无缩进）
     */
    public String serialize(List<CADEntity> entities, DrawingMetadata metadata) {
        StringBuilder sb = new StringBuilder(entities.size() * 300);
        sb.append("{\"type\":\"FeatureCollection\"");
        appendCrs(sb, metadata);
        sb.append(",\"features\":[");
        boolean first = true;
        for (CADEntity entity : entities) {
            if (!first) sb.append(',');
            first = false;
            appendFeature(sb, entity);
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 格式化单个坐标值为指定精度的字符串。
     * 大坐标（如 CGCS2000）需要此方法避免浮点噪声。
     */
    public String serializeCoordinate(double value) {
        return fmt(value);
    }

    // -------------------------------------------------------------------------
    // Feature 序列化
    // -------------------------------------------------------------------------

    private void appendCrs(StringBuilder sb, DrawingMetadata metadata) {
        if (metadata == null || metadata.getCrs() == null) return;
        sb.append(",\"crs\":{")
          .append("\"source\":\"").append(escapeJson(metadata.getCrsSource())).append("\",")
          .append("\"value\":\"").append(escapeJson(metadata.getCrs())).append("\"")
          .append('}');
    }

    private void appendFeature(StringBuilder sb, CADEntity entity) {
        sb.append("{\"type\":\"Feature\",\"geometry\":");
        appendGeometry(sb, entity.geometry());
        sb.append(",\"properties\":");
        appendProperties(sb, entity);
        sb.append('}');
    }

    // -------------------------------------------------------------------------
    // 几何序列化
    // -------------------------------------------------------------------------

    private void appendGeometry(StringBuilder sb, Geometry geom) {
        if (geom == null || geom.isEmpty()) { sb.append("null"); return; }

        if (geom instanceof Point p) {
            sb.append("{\"type\":\"Point\",\"coordinates\":");
            appendCoord(sb, p.getCoordinate());
            sb.append('}');

        } else if (geom instanceof LinearRing lr) {
            // LinearRing → Polygon（无洞，作为外环直接输出）
            sb.append("{\"type\":\"Polygon\",\"coordinates\":[");
            appendCoordArray(sb, lr.getCoordinates());
            sb.append("]}");

        } else if (geom instanceof LineString ls) {
            sb.append("{\"type\":\"LineString\",\"coordinates\":");
            appendCoordArray(sb, ls.getCoordinates());
            sb.append('}');

        } else if (geom instanceof Polygon poly) {
            sb.append("{\"type\":\"Polygon\",\"coordinates\":[");
            appendCoordArray(sb, poly.getExteriorRing().getCoordinates());
            for (int i = 0; i < poly.getNumInteriorRing(); i++) {
                sb.append(',');
                appendCoordArray(sb, poly.getInteriorRingN(i).getCoordinates());
            }
            sb.append("]}");

        } else if (geom instanceof MultiPolygon mp) {
            sb.append("{\"type\":\"MultiPolygon\",\"coordinates\":[");
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                if (i > 0) sb.append(',');
                Polygon p = (Polygon) mp.getGeometryN(i);
                sb.append('[');
                appendCoordArray(sb, p.getExteriorRing().getCoordinates());
                for (int j = 0; j < p.getNumInteriorRing(); j++) {
                    sb.append(',');
                    appendCoordArray(sb, p.getInteriorRingN(j).getCoordinates());
                }
                sb.append(']');
            }
            sb.append("]}");

        } else {
            sb.append("null"); // 不支持的几何类型
        }
    }

    /** 序列化单个坐标点 [x, y] 或 [x, y, z]。Z 为 NaN 时省略。 */
    private void appendCoord(StringBuilder sb, Coordinate c) {
        sb.append('[').append(fmt(c.x)).append(',').append(fmt(c.y));
        double z = c.getZ();
        if (!Double.isNaN(z)) sb.append(',').append(fmt(z));
        sb.append(']');
    }

    /** 序列化坐标数组 [[x,y,z], ...]。 */
    private void appendCoordArray(StringBuilder sb, Coordinate[] coords) {
        sb.append('[');
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) sb.append(',');
            appendCoord(sb, coords[i]);
        }
        sb.append(']');
    }

    // -------------------------------------------------------------------------
    // 属性序列化
    // -------------------------------------------------------------------------

    private void appendProperties(StringBuilder sb, CADEntity entity) {
        sb.append("{\"type\":\"").append(escapeJson(entity.getType())).append('"')
          .append(",\"handle\":\"").append(escapeJson(entity.getHandle())).append('"')
          .append(",\"layer\":\"").append(escapeJson(entity.getLayer())).append('"');
        entity.getProperties().forEach((k, v) -> {
            // xdata 结构复杂，序列化为 null 占位（避免递归失控）
            if ("xdata".equals(k)) { sb.append(",\"xdata\":null"); return; }
            sb.append(",\"").append(escapeJson(k)).append("\":");
            appendValue(sb, v);
        });
        sb.append('}');
    }

    @SuppressWarnings("unchecked")
    private void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"').append(escapeJson(s)).append('"');
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) sb.append("null");
            else sb.append(d);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof int[] arr) {
            // colorRgb：[R, G, B]
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escapeJson(e.getKey().toString())).append("\":");
                appendValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                appendValue(sb, item);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escapeJson(value.toString())).append('"');
        }
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    /** 坐标格式化，指定小数位，避免浮点噪声。 */
    private String fmt(double v) {
        return String.format("%." + coordinateDecimalPlaces + "f", v);
    }

    /** JSON 字符串转义（处理 \、"、换行、制表等控制字符）。 */
    public static String escapeJson(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
