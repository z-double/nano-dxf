package com.nanodxf.geometry;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;

/**
 * JTS 几何有效性检查与修复。
 *
 * <p>CAD 数据质量不稳定，直接入库 PostGIS 经常报错，必须在序列化前修复。
 *
 * <p>修复流程：
 * <ol>
 *   <li>使用 DP 简化（tolerance=0）清除重复顶点</li>
 *   <li>调用 {@link GeometryFixer#fix}（JTS 1.19+）修复自相交、近似未闭合等问题</li>
 *   <li>修复后仍无效：返回 null，实体的 geometry 置为 null（属性保留不丢失）</li>
 * </ol>
 *
 * <p>绕行方向（winding order）需单独处理，GeoJSON RFC 7946 要求：
 * 外环逆时针（CCW），内环顺时针（CW）。见 {@link #normalizeWindingOrder}。
 */
public class GeometryValidator {

    /**
     * 检查并尝试修复几何有效性。
     *
     * @return 修复后的有效几何，或 null（无法修复时）
     */
    public static Geometry validate(Geometry geom) {
        if (geom == null) return null;

        // 步骤 1：清除重复顶点（DP 简化 tolerance=0 只移除共线/重复点，不改变形状）
        geom = DouglasPeuckerSimplifier.simplify(geom, 0);

        if (!geom.isValid()) {
            // 步骤 2：调用 JTS GeometryFixer 修复（处理自相交、零长度段等）
            geom = GeometryFixer.fix(geom);
            if (!geom.isValid()) {
                return null; // 修复失败，由调用方记录 WARN
            }
        }
        return geom;
    }

    /**
     * 统一绕行方向以符合 GeoJSON RFC 7946。
     *
     * @param ring    待处理的环
     * @param isOuter true=外环（要求 CCW），false=内环/洞（要求 CW）
     * @return 方向正确的环（若方向已正确则原样返回，否则取反）
     */
    public static org.locationtech.jts.geom.LinearRing normalizeWindingOrder(
            org.locationtech.jts.geom.LinearRing ring, boolean isOuter) {
        boolean isCCW = org.locationtech.jts.algorithm.Orientation.isCCW(
                ring.getCoordinateSequence());
        // JTS 1.19+ 的 reverse() 返回与接收者相同的具体类型（协变返回）
        if (isOuter && !isCCW) return (org.locationtech.jts.geom.LinearRing) ring.reverse();
        if (!isOuter && isCCW) return (org.locationtech.jts.geom.LinearRing) ring.reverse();
        return ring;
    }
}
