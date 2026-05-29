package com.nanodxf.stat;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.geometry.UnitConverter;
import com.nanodxf.model.DrawingMetadata;

import java.util.*;

/**
 * 图层统计量算工具。
 *
 * <p>按图层汇总实体数量、总线长、总面积，用于地形图成果统计、面积报表生成等场景。
 *
 * <pre>{@code
 * // 基本统计（坐标原始单位）
 * Map<String, LayerStatRow> stats = LayerStats.compute(result.getEntities());
 * System.out.println(LayerStats.summary(stats));
 *
 * // 含单位换算（坐标→米）
 * Map<String, LayerStatRow> stats = LayerStats.compute(
 *     result.getEntities(), result.getMetadata());
 * }</pre>
 */
public final class LayerStats {

    private LayerStats() {}

    /**
     * 按图层统计，长度/面积保留坐标原始单位。
     *
     * @param entities 实体列表
     * @return 图层名 → 统计行（按实体数量降序排列的 LinkedHashMap）
     */
    public static Map<String, LayerStatRow> compute(List<CADEntity> entities) {
        return compute(entities, null);
    }

    /**
     * 按图层统计，若 {@code metadata} 非 null 则根据 {@code $INSUNITS} 将长度换算为米、
     * 面积换算为平方米。
     *
     * @param entities 实体列表
     * @param metadata 图纸元数据（可为 null，表示不换算单位）
     * @return 图层名 → 统计行（按实体数量降序排列的 LinkedHashMap）
     */
    public static Map<String, LayerStatRow> compute(List<CADEntity> entities,
                                                     DrawingMetadata metadata) {
        Map<String, LayerStatRow.Accumulator> acc = new LinkedHashMap<>();
        for (CADEntity e : entities) {
            String layer = e.getLayer() != null ? e.getLayer() : "0";
            acc.computeIfAbsent(layer, LayerStatRow.Accumulator::new).add(e);
        }

        double lengthFactor = 1.0, areaFactor = 1.0;
        if (metadata != null) {
            double f = new UnitConverter().scaleFactor(metadata.getInsunits());
            if (f > 0 && f != 1.0) {
                lengthFactor = f;
                areaFactor   = f * f;
            }
        }

        final double lf = lengthFactor, af = areaFactor;
        Map<String, LayerStatRow> result = new LinkedHashMap<>();
        acc.entrySet().stream()
                .map(e -> e.getValue().build(lf, af))
                .sorted(Comparator.comparingInt(LayerStatRow::getCount).reversed())
                .forEach(row -> result.put(row.getLayer(), row));

        return Collections.unmodifiableMap(result);
    }

    /**
     * 将统计结果格式化为可打印的文本表格。
     *
     * <pre>
     * 图层                      实体数         总长度（m）     总面积（m²）
     * -----------------------------------------------------------------------
     * 等高线                       842      125430.2188             0.0000
     * 建筑物                       318           0.0000        48902.3750
     * ...
     * </pre>
     */
    public static String summary(Map<String, LayerStatRow> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-24s %6s %16s %16s%n",
                "图层", "实体数", "总长度", "总面积"));
        sb.append("-".repeat(66)).append('\n');
        stats.values().forEach(row -> sb.append(row.toTableRow()).append('\n'));
        int total = stats.values().stream().mapToInt(LayerStatRow::getCount).sum();
        sb.append("-".repeat(66)).append('\n');
        sb.append(String.format("%-24s %6d%n", "合计", total));
        return sb.toString();
    }
}
