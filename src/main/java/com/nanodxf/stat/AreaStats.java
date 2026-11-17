package com.nanodxf.stat;

import com.nanodxf.EntityProperty;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.geometry.UnitConverter;
import com.nanodxf.model.DrawingMetadata;
import com.nanodxf.xdata.FeatureCodeRegistry;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygonal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按地物编码的面积统计（宗地 / 图斑面积报表）。
 *
 * <p>与 {@link LayerStats}（按图层）互补：本工具按 {@code featureCode} 分组，
 * 汇总图斑数量与总面积，并换算亩 / 公顷，用于地类面积汇总、宗地报表等场景。
 *
 * <p>面积来源：{@link org.locationtech.jts.geom.Polygon} / {@link org.locationtech.jts.geom.MultiPolygon}、
 * 闭合 {@link org.locationtech.jts.geom.LinearRing}、闭合 {@link LineString}（≥4 点，如闭合 LWPOLYLINE）、
 * 以及上述几何组成的 {@link GeometryCollection}（如 HATCH）。
 *
 * <pre>{@code
 * Map<String, AreaStatRow> stats = AreaStats.compute(
 *         result.getEntities(), result.getMetadata()); // 面积换算为平方米
 * System.out.println(AreaStats.summary(stats));
 * AreaStats.writeCsv(stats, Path.of("area_report.csv"));
 * }</pre>
 */
public final class AreaStats {

    /** 无编码实体的分组键。 */
    public static final String NO_CODE = "(无编码)";

    private AreaStats() {}

    /**
     * 按地物编码统计，面积保留坐标原始单位²。
     *
     * @return 编码 → 统计行（按面积降序排列的不可变 Map）
     */
    public static Map<String, AreaStatRow> compute(List<CADEntity> entities) {
        return compute(entities, null);
    }

    /**
     * 按地物编码统计；{@code metadata} 非 null 时按 {@code $INSUNITS} 将面积换算为平方米。
     *
     * @param entities 实体列表
     * @param metadata 图纸元数据（可为 null，表示不换算单位）
     * @return 编码 → 统计行（按面积降序排列的不可变 Map）
     */
    public static Map<String, AreaStatRow> compute(List<CADEntity> entities,
                                                    DrawingMetadata metadata) {
        Map<String, AreaStatRow.Accumulator> acc = new LinkedHashMap<>();
        for (CADEntity e : entities) {
            double area = areaOf(e.geometry());
            if (area <= 0) continue;

            Object codeProp = e.getProperties().get(EntityProperty.FEATURE_CODE);
            String code = codeProp != null && !codeProp.toString().isBlank()
                    ? codeProp.toString() : NO_CODE;

            Object typeProp = e.getProperties().get(EntityProperty.FEATURE_TYPE);
            String type = typeProp != null ? typeProp.toString()
                    : FeatureCodeRegistry.lookup(code).map(FeatureCodeRegistry.FeatureCodeInfo::name).orElse("");

            acc.computeIfAbsent(code, AreaStatRow.Accumulator::new).add(area, type);
        }

        double areaFactor = 1.0;
        if (metadata != null) {
            double f = new UnitConverter().scaleFactor(metadata.getInsunits());
            if (f > 0 && f != 1.0) areaFactor = f * f;
        }

        final double af = areaFactor;
        Map<String, AreaStatRow> result = new LinkedHashMap<>();
        acc.values().stream()
                .map(a -> a.build(af))
                .sorted(Comparator.comparingDouble(AreaStatRow::getArea).reversed())
                .forEach(row -> result.put(row.getFeatureCode(), row));
        return Collections.unmodifiableMap(result);
    }

    /** 格式化为可打印的文本表格（编码 / 名称 / 图斑数 / 面积 / 亩 / 公顷）。 */
    public static String summary(Map<String, AreaStatRow> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-12s %-20s %6s %16s %12s %12s%n",
                "编码", "名称", "图斑数", "面积(m²)", "亩", "公顷"));
        sb.append("-".repeat(84)).append('\n');
        stats.values().forEach(row -> sb.append(row.toTableRow()).append('\n'));
        sb.append("-".repeat(84)).append('\n');
        int totalCount = stats.values().stream().mapToInt(AreaStatRow::getCount).sum();
        double totalArea = stats.values().stream().mapToDouble(AreaStatRow::getArea).sum();
        sb.append(String.format(Locale.ROOT, "%-12s %-20s %6d %16.4f %12.4f %12.4f%n",
                "合计", "", totalCount, totalArea, totalArea * 0.0015, totalArea / 10000.0));
        return sb.toString();
    }

    /**
     * 写出 CSV 报表（UTF-8，含表头）：
     * {@code featureCode,featureType,count,area_m2,area_mu,area_ha}。
     */
    public static void writeCsv(Map<String, AreaStatRow> stats, Path path) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            bw.write("featureCode,featureType,count,area_m2,area_mu,area_ha\n");
            for (AreaStatRow row : stats.values()) {
                bw.write(csvCell(row.getFeatureCode()));
                bw.write(',');
                bw.write(csvCell(row.getFeatureType()));
                bw.write(String.format(Locale.ROOT, ",%d,%.4f,%.4f,%.4f%n",
                        row.getCount(), row.getArea(), row.getMu(), row.getHectares()));
            }
        }
    }

    // -------------------------------------------------------------------------

    /** 提取几何的面积贡献；非面类 / 未闭合返回 0。 */
    private static double areaOf(Geometry g) {
        if (g == null) return 0;
        if (g instanceof Polygonal) return g.getArea();
        // LinearRing 继承 LineString.getArea()=0，须按闭合线构造多边形求面积
        if (g instanceof LineString ls && ls.isClosed() && ls.getNumPoints() >= 4) {
            return GeometryBuilder.factory().createPolygon(ls.getCoordinates()).getArea();
        }
        if (g instanceof GeometryCollection gc) {
            double sum = 0;
            for (int i = 0; i < gc.getNumGeometries(); i++) sum += areaOf(gc.getGeometryN(i));
            return sum;
        }
        return 0;
    }

    private static String csvCell(String v) {
        if (v == null) return "";
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}