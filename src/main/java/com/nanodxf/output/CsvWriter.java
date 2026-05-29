package com.nanodxf.output;

import com.nanodxf.EntityProperty;
import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 将实体列表写出为点云 CSV 文件。
 *
 * <p>每个实体输出一行；几何质心作为坐标来源（Point 实体直接取坐标，
 * 其他实体取 {@link Geometry#getCentroid()}）。几何为 null 的实体跳过。
 *
 * <pre>{@code
 * CsvWriteConfig cfg = CsvWriteConfig.builder()
 *     .fields(CsvField.X, CsvField.Y, CsvField.Z,
 *             CsvField.LAYER, CsvField.FEATURE_CODE)
 *     .delimiter(',')
 *     .build();
 * CsvWriter.write(result.getEntities(), Path.of("output.csv"), cfg);
 * }</pre>
 */
public final class CsvWriter {

    private CsvWriter() {}

    /**
     * 使用默认配置写出 CSV。
     *
     * @param entities 实体列表
     * @param path     输出路径
     */
    public static void write(List<CADEntity> entities, Path path) throws IOException {
        write(entities, path, CsvWriteConfig.defaults());
    }

    /**
     * 使用指定配置写出 CSV。
     *
     * @param entities 实体列表
     * @param path     输出路径
     * @param config   写出配置
     */
    public static void write(List<CADEntity> entities, Path path,
                             CsvWriteConfig config) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(path, config.getCharset())) {
            write(entities, bw, config);
        }
    }

    /**
     * 写出 CSV 到任意 Writer（可用于字符串缓冲）。
     */
    public static void write(List<CADEntity> entities, Writer out,
                             CsvWriteConfig config) throws IOException {
        List<CsvField> fields = config.getFields();
        char sep = config.getDelimiter();
        String nd  = config.getNoDataValue();

        if (config.isWriteHeader()) {
            writeRow(out, fields.stream().map(Enum::name).toList(), sep);
        }

        for (CADEntity e : entities) {
            Geometry g = e.geometry();
            if (g == null) continue;

            Point centroid = (g instanceof Point) ? (Point) g : g.getCentroid();
            if (centroid == null || centroid.isEmpty()) continue;

            double x = centroid.getX();
            double y = centroid.getY();
            double z = Double.isNaN(centroid.getCoordinate().getZ())
                    ? Double.NaN : centroid.getCoordinate().getZ();

            Map<String, Object> props = e.getProperties();
            List<String> values = fields.stream().map(f -> switch (f) {
                case HANDLE       -> nvl(e.getHandle(), nd);
                case LAYER        -> nvl(e.getLayer(), nd);
                case TYPE         -> nvl(e.getType(), nd);
                case X            -> fmt(x);
                case Y            -> fmt(y);
                case Z            -> Double.isNaN(z) ? nd : fmt(z);
                case ELEVATION    -> fmtProp(props.get(EntityProperty.ELEVATION), nd);
                case FEATURE_CODE -> fmtProp(props.get(EntityProperty.FEATURE_CODE), nd);
                case COLOR        -> fmtProp(props.get(EntityProperty.COLOR_ACI), nd);
            }).toList();
            writeRow(out, values, sep);
        }
    }

    // -------------------------------------------------------------------------

    private static void writeRow(Writer out, List<String> cols, char sep) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(sep);
            String v = cols.get(i);
            if (v != null && (v.indexOf(sep) >= 0 || v.indexOf('"') >= 0
                    || v.indexOf('\n') >= 0)) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v != null ? v : "");
            }
        }
        sb.append('\n');
        out.write(sb.toString());
    }

    private static String fmt(double v) {
        return Double.toString(v);
    }

    private static String nvl(String s, String nd) {
        return s != null ? s : nd;
    }

    private static String fmtProp(Object v, String nd) {
        return v != null ? v.toString() : nd;
    }
}
