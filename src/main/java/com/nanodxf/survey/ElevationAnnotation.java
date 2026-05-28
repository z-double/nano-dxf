package com.nanodxf.survey;

import com.nanodxf.EntityIndex;
import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高程注记配对工具。
 *
 * <p>CASS 等测绘软件在高程点（POINT 实体）旁放置 TEXT/MTEXT 注记，内容为高程值
 * （如 {@code "25.30"}、{@code "H=25.30"}、{@code "h=25.300"}、{@code "▽25.30"}）。
 * 本工具通过空间近邻查询将 POINT 实体与附近文字注记配对，提取高程数值。
 *
 * <p>配对规则：
 * <ol>
 *   <li>在 POINT 实体 {@code searchRadius} 范围内查找 TEXT / MTEXT 实体</li>
 *   <li>对文字内容按预定义正则模式匹配，提取第一个匹配到的高程值</li>
 *   <li>若多个文字匹配，取距离最近的一个</li>
 *   <li>若 POINT 已有 {@code elevation} 属性且文字匹配失败，则退用 elevation 属性</li>
 * </ol>
 *
 * <p>典型用法：
 * <pre>{@code
 * Map<CADEntity, Double> linked = ElevationAnnotation.link(result.getEntities(), 2.0);
 * linked.forEach((pt, elev) -> System.out.printf("%s  H=%.3f%n", pt.getHandle(), elev));
 * }</pre>
 */
public final class ElevationAnnotation {

    // 匹配模式（按优先级）
    private static final List<Pattern> PATTERNS = List.of(
            // H=25.30 / h=25.30 / H25.30（可选等号）
            Pattern.compile("(?i)[Hh]\\s*=?\\s*([+-]?\\d{1,6}\\.?\\d{0,4})"),
            // ▽25.30 / △25.30（测量符号）
            Pattern.compile("[▽△]\\s*([+-]?\\d{1,6}\\.?\\d{0,4})"),
            // 纯数值（如 "25.3000"，要求含小数点）
            Pattern.compile("^\\s*([+-]?\\d{1,6}\\.\\d{1,4})\\s*$")
    );

    private static final Set<String> TEXT_TYPES = Set.of("TEXT", "MTEXT");

    private ElevationAnnotation() {}

    /**
     * 将 POINT 实体与附近文字注记配对，返回 (POINT 实体 → 高程值) 映射。
     *
     * <p>内部自动建立 {@link EntityIndex} 空间索引；若已有索引请使用
     * {@link #link(List, EntityIndex, double)}。
     *
     * @param entities     全量实体列表（同时含 POINT 和 TEXT/MTEXT）
     * @param searchRadius 搜索半径（与坐标单位一致，建议 1~5）
     * @return key=POINT 实体，value=高程值；无法匹配的 POINT 不出现在 map 中
     */
    public static Map<CADEntity, Double> link(List<CADEntity> entities, double searchRadius) {
        return link(entities, new EntityIndex(entities), searchRadius);
    }

    /**
     * 复用已有 {@link EntityIndex} 进行配对，避免重复建树。
     *
     * @param points       仅含 POINT 类型的实体列表
     * @param index        全量实体的空间索引
     * @param searchRadius 搜索半径
     * @return (POINT → 高程值) 映射
     */
    public static Map<CADEntity, Double> link(List<CADEntity> points,
                                               EntityIndex index,
                                               double searchRadius) {
        Map<CADEntity, Double> result = new LinkedHashMap<>();
        for (CADEntity pt : points) {
            if (!"POINT".equals(pt.getType())) continue;
            Geometry geom = pt.geometry();
            if (!(geom instanceof Point p)) continue;

            double found = searchNearby(p, index, searchRadius);
            if (!Double.isNaN(found)) {
                result.put(pt, found);
            } else {
                // 退用 elevation 属性
                Object elevObj = pt.getProperties().get("elevation");
                if (elevObj instanceof Number n && n.doubleValue() != 0.0) {
                    result.put(pt, n.doubleValue());
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    // -------------------------------------------------------------------------

    private static double searchNearby(Point pt, EntityIndex index, double r) {
        Envelope env = new Envelope(
                pt.getX() - r, pt.getX() + r,
                pt.getY() - r, pt.getY() + r);

        List<CADEntity> candidates = index.query(env).stream()
                .filter(e -> TEXT_TYPES.contains(e.getType()))
                .toList();

        double bestElev = Double.NaN;
        double bestDist = Double.MAX_VALUE;

        for (CADEntity text : candidates) {
            Geometry tg = text.geometry();
            if (tg == null) continue;
            double dist = pt.distance(tg);
            if (dist > r) continue;

            String content = (String) text.getProperties().get("text");
            if (content == null || content.isBlank()) continue;

            Double elev = parseElevation(content.trim());
            if (elev != null && dist < bestDist) {
                bestDist = dist;
                bestElev = elev;
            }
        }
        return bestElev;
    }

    public static Double parseElevation(String text) {
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                try {
                    return Double.parseDouble(m.group(1));
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }
}
