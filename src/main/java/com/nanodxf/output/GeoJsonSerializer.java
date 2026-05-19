package com.nanodxf.output;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.DrawingMetadata;

import java.util.List;

/**
 * 将解析结果序列化为 GeoJSON FeatureCollection 字符串。
 *
 * <p>输出格式遵循 GeoJSON RFC 7946：
 * <ul>
 *   <li>外环逆时针（CCW），内环顺时针（CW）——由 GeometryValidator 保证</li>
 *   <li>坐标精度由 {@code coordinateDecimalPlaces} 控制（默认 4 位，0.1mm 精度）</li>
 *   <li>CRS 信息写入顶层 {@code crs} 字段，标注来源（caller_specified / unknown）</li>
 * </ul>
 *
 * <p>大坐标精度控制：CGCS2000 投影坐标 X 值约 3,000,000~6,000,000，
 * 不做精度控制会引入浮点噪声（如 3456789.1234560001）。
 * 使用 {@link #serializeCoordinate} 格式化每个坐标值。
 *
 * <p>TODO Phase 3：完整实现，包含 CRS 标注和要素属性序列化。
 */
public class GeoJsonSerializer {

    private final int coordinateDecimalPlaces;

    /**
     * @param coordinateDecimalPlaces 坐标保留小数位数（0~15）；
     *                                 毫米级精度建议 3，厘米级 2，地形图可用 4
     */
    public GeoJsonSerializer(int coordinateDecimalPlaces) {
        this.coordinateDecimalPlaces = coordinateDecimalPlaces;
    }

    /**
     * 序列化实体列表为 GeoJSON FeatureCollection。
     *
     * @param entities 实体列表（geometry 为 null 的实体会被跳过几何，属性仍输出）
     * @param metadata 图纸元数据（用于 CRS 标注）
     * @return GeoJSON 字符串
     */
    public String serialize(List<CADEntity> entities, DrawingMetadata metadata) {
        // TODO Phase 3：完整实现
        throw new UnsupportedOperationException("GeoJsonSerializer not yet implemented (Phase 3)");
    }

    /**
     * 将单个坐标值格式化为指定精度的字符串，避免浮点噪声。
     *
     * @param value 坐标值
     * @return 如 "3456789.1235"（coordinateDecimalPlaces=4 时）
     */
    public String serializeCoordinate(double value) {
        return String.format("%." + coordinateDecimalPlaces + "f", value);
    }
}
