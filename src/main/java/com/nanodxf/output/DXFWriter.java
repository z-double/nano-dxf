package com.nanodxf.output;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.DXFVersion;
import org.locationtech.jts.geom.*;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 将 {@link CADEntity} 列表序列化为 DXF ASCII 文件。
 *
 * <p>支持输出的几何类型（JTS → DXF 实体）：
 * <ul>
 *   <li>{@link Point}      → POINT</li>
 *   <li>{@link LineString}（2 点）→ LINE</li>
 *   <li>{@link LineString}（多点）→ LWPOLYLINE</li>
 *   <li>{@link LinearRing} → LWPOLYLINE（闭合标志 bit0=1）</li>
 *   <li>{@link Polygon}    → 外环 LWPOLYLINE + 每个洞各一条 LWPOLYLINE</li>
 *   <li>{@link GeometryCollection}（含 MultiPolygon/MultiLineString）→ 逐元素展开</li>
 * </ul>
 *
 * <p>TEXT / MTEXT 实体按原类型输出，{@code properties["text"]} 为空时跳过。
 *
 * <p>颜色输出规则：
 * <ul>
 *   <li>R2004+ 且有 {@code colorRgb} → True Color（code 420）</li>
 *   <li>有 {@code colorAci} → code 62</li>
 *   <li>其余 → 不输出颜色代码（BYLAYER）</li>
 * </ul>
 *
 * <p>输出文件结构：HEADER（版本/单位）→ TABLES（LAYER 表）→ ENTITIES → EOF
 *
 * <pre>{@code
 * DXFWriteConfig config = DXFWriteConfig.builder()
 *     .version(DXFVersion.R2000)
 *     .coordinateDecimalPlaces(4)
 *     .build();
 *
 * new DXFWriter(config).write(entities, Paths.get("output.dxf"));
 * }</pre>
 */
public class DXFWriter {

    private final DXFWriteConfig config;

    public DXFWriter() {
        this(DXFWriteConfig.defaults());
    }

    public DXFWriter(DXFWriteConfig config) {
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /**
     * 将实体列表写入 DXF 文件。
     *
     * @param entities 实体列表（geometry 为 null 的实体自动跳过）
     * @param path     输出文件路径（自动按 {@link DXFWriteConfig#getEncoding()} 编码）
     */
    public void write(List<CADEntity> entities, Path path) throws IOException {
        Charset cs = Charset.forName(config.getEncoding());
        try (Writer w = new OutputStreamWriter(
                new BufferedOutputStream(Files.newOutputStream(path)), cs)) {
            write(entities, w);
        }
    }

    /**
     * 将实体列表写入 Writer（常用于测试或内存输出）。
     *
     * @param entities 实体列表
     * @param out      目标 Writer
     */
    public void write(List<CADEntity> entities, Writer out) throws IOException {
        // 用包装 PrintWriter 统一行尾为 \r\n（DXF 规范推荐；现代工具也接受 \n）
        LineWriter w = new LineWriter(out);
        int[] handle = {1}; // 简单顺序分配 handle，避免冲突

        writeHeader(w, entities);
        writeTables(w, collectLayers(entities), handle);
        writeEntitiesSection(w, entities, handle);
        pair(w, 0, "EOF");
        w.flush();
    }

    // -------------------------------------------------------------------------
    // HEADER section
    // -------------------------------------------------------------------------

    private void writeHeader(LineWriter w, List<CADEntity> entities) throws IOException {
        pair(w, 0, "SECTION");
        pair(w, 2, "HEADER");

        pair(w, 9, "$ACADVER");
        pair(w, 1, config.getVersion().getVersionString());

        pair(w, 9, "$INSUNITS");
        pair(w, 70, "6"); // 6 = 米

        pair(w, 9, "$LTSCALE");
        pair(w, 40, fmt(1.0));

        pair(w, 0, "ENDSEC");
    }

    // -------------------------------------------------------------------------
    // TABLES section
    // -------------------------------------------------------------------------

    private void writeTables(LineWriter w, Set<String> layers,
                              int[] handle) throws IOException {
        pair(w, 0, "SECTION");
        pair(w, 2, "TABLES");

        // LAYER 表
        pair(w, 0, "TABLE");
        pair(w, 2, "LAYER");
        pair(w, 5, hex(handle[0]++));
        pair(w, 70, String.valueOf(layers.size()));

        for (String layer : layers) {
            pair(w, 0, "LAYER");
            pair(w, 5, hex(handle[0]++));
            pair(w, 2, layer);
            pair(w, 70, "0");      // 可见
            pair(w, 62, "7");      // 颜色 7（白）
            pair(w, 6, "Continuous");
        }

        pair(w, 0, "ENDTAB");
        pair(w, 0, "ENDSEC");
    }

    // -------------------------------------------------------------------------
    // ENTITIES section
    // -------------------------------------------------------------------------

    private void writeEntitiesSection(LineWriter w, List<CADEntity> entities,
                                       int[] handle) throws IOException {
        pair(w, 0, "SECTION");
        pair(w, 2, "ENTITIES");
        for (CADEntity entity : entities) {
            writeEntity(w, entity, handle);
        }
        pair(w, 0, "ENDSEC");
    }

    private void writeEntity(LineWriter w, CADEntity entity,
                              int[] handle) throws IOException {
        // TEXT / MTEXT 优先按类型输出
        if ("TEXT".equals(entity.getType()) && entity.geometry() instanceof Point p) {
            writeText(w, p, entity, handle); return;
        }
        if ("MTEXT".equals(entity.getType()) && entity.geometry() instanceof Point p) {
            writeMText(w, p, entity, handle); return;
        }
        // 其余按几何类型分发
        if (entity.geometry() != null) {
            writeGeometry(w, entity.geometry(), entity, handle);
        }
    }

    private void writeGeometry(LineWriter w, Geometry geom, CADEntity entity,
                                int[] handle) throws IOException {
        if (geom instanceof Point p) {
            writePoint(w, p, entity, handle);
        } else if (geom instanceof LinearRing lr) {
            writeLwPolyline(w, lr.getCoordinates(), true, entity, handle);
        } else if (geom instanceof LineString ls) {
            Coordinate[] coords = ls.getCoordinates();
            if (coords.length == 2) writeLine(w, ls, entity, handle);
            else                    writeLwPolyline(w, coords, false, entity, handle);
        } else if (geom instanceof Polygon poly) {
            // 外环
            writeLwPolyline(w, poly.getExteriorRing().getCoordinates(), true, entity, handle);
            // 洞：各自输出一条 LWPOLYLINE
            for (int i = 0; i < poly.getNumInteriorRing(); i++) {
                writeLwPolyline(w,
                    poly.getInteriorRingN(i).getCoordinates(), true, entity, handle);
            }
        } else if (geom instanceof GeometryCollection gc) {
            for (int i = 0; i < gc.getNumGeometries(); i++) {
                writeGeometry(w, gc.getGeometryN(i), entity, handle);
            }
        }
    }

    // -------------------------------------------------------------------------
    // 各实体类型
    // -------------------------------------------------------------------------

    private void writePoint(LineWriter w, Point p, CADEntity entity,
                             int[] handle) throws IOException {
        pair(w, 0, "POINT");
        writeCommon(w, entity, handle);
        pair(w, 10, fmt(p.getX()));
        pair(w, 20, fmt(p.getY()));
        pair(w, 30, fmtZ(p.getCoordinate().getZ()));
    }

    private void writeLine(LineWriter w, LineString ls, CADEntity entity,
                            int[] handle) throws IOException {
        Coordinate s = ls.getCoordinateN(0);
        Coordinate e = ls.getCoordinateN(1);
        pair(w, 0, "LINE");
        writeCommon(w, entity, handle);
        pair(w, 10, fmt(s.x));   pair(w, 20, fmt(s.y));   pair(w, 30, fmtZ(s.getZ()));
        pair(w, 11, fmt(e.x));   pair(w, 21, fmt(e.y));   pair(w, 31, fmtZ(e.getZ()));
    }

    private void writeLwPolyline(LineWriter w, Coordinate[] coords, boolean closed,
                                  CADEntity entity, int[] handle) throws IOException {
        // 闭合环首尾重复点时去掉末尾重复点
        int n = (closed && coords.length > 1
                && coords[0].equals2D(coords[coords.length - 1]))
                ? coords.length - 1 : coords.length;
        if (n < 2) return;

        pair(w, 0, "LWPOLYLINE");
        writeCommon(w, entity, handle);
        pair(w, 90, String.valueOf(n));
        pair(w, 70, closed ? "1" : "0");

        // Elevation（code 38）：仅当所有顶点 Z 相同且非零时输出
        double z0 = coords[0].getZ();
        boolean uniformZ = !Double.isNaN(z0);
        if (uniformZ) {
            for (int i = 1; i < n; i++) {
                if (Double.isNaN(coords[i].getZ())
                        || Math.abs(coords[i].getZ() - z0) > 1e-9) {
                    uniformZ = false; break;
                }
            }
        }
        if (uniformZ && Math.abs(z0) > 1e-12) {
            pair(w, 38, fmt(z0));
        }

        for (int i = 0; i < n; i++) {
            pair(w, 10, fmt(coords[i].x));
            pair(w, 20, fmt(coords[i].y));
        }
    }

    private void writeText(LineWriter w, Point p, CADEntity entity,
                            int[] handle) throws IOException {
        String text = strProp(entity, "text");
        if (text.isBlank()) return;
        pair(w, 0, "TEXT");
        writeCommon(w, entity, handle);
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        pair(w, 40, fmt(dblProp(entity, "height", 2.5)));
        pair(w, 1, text);
        double rot = dblProp(entity, "rotation", 0.0);
        if (Math.abs(rot) > 1e-9) pair(w, 50, fmt(rot));
        pair(w, 7, strPropOrDefault(entity, "style", "Standard"));
    }

    private void writeMText(LineWriter w, Point p, CADEntity entity,
                             int[] handle) throws IOException {
        String text = strProp(entity, "text");
        if (text.isBlank()) return;
        pair(w, 0, "MTEXT");
        writeCommon(w, entity, handle);
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        pair(w, 40, fmt(dblProp(entity, "height", 2.5)));
        pair(w, 1, text);
        pair(w, 71, "1"); // 附着点：左上
        pair(w, 72, "1"); // 书写方向：从左到右
    }

    // -------------------------------------------------------------------------
    // 公共头字段：handle + 图层 + 颜色
    // -------------------------------------------------------------------------

    private void writeCommon(LineWriter w, CADEntity entity,
                              int[] handle) throws IOException {
        pair(w, 5, hex(handle[0]++));
        pair(w, 8, entity.getLayer() != null ? entity.getLayer() : "0");
        writeColor(w, entity);
    }

    private void writeColor(LineWriter w, CADEntity entity) throws IOException {
        Object rgb = entity.getProperties().get("colorRgb");
        Object aci = entity.getProperties().get("colorAci");
        // True Color：仅 R2004+ 支持
        if (rgb instanceof int[] arr && !config.getVersion().before(DXFVersion.R2004)) {
            pair(w, 420, String.valueOf((arr[0] << 16) | (arr[1] << 8) | arr[2]));
        } else if (aci instanceof Integer aciVal) {
            pair(w, 62, String.valueOf(aciVal));
        }
        // 其余：不输出（实体颜色 = BYLAYER）
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    private Set<String> collectLayers(List<CADEntity> entities) {
        Set<String> layers = new LinkedHashSet<>();
        layers.add("0"); // 默认图层始终存在
        for (CADEntity e : entities) {
            String l = e.getLayer();
            if (l != null && !l.isEmpty()) layers.add(l);
        }
        return layers;
    }

    private String fmt(double v) {
        return String.format("%." + config.getCoordinateDecimalPlaces() + "f", v);
    }

    private String fmtZ(double z) {
        return Double.isNaN(z) ? fmt(0.0) : fmt(z);
    }

    private static String hex(int n) {
        return Integer.toHexString(n).toUpperCase();
    }

    private static String strProp(CADEntity e, String key) {
        Object v = e.getProperties().get(key);
        return v instanceof String s ? s : "";
    }

    private static String strPropOrDefault(CADEntity e, String key, String def) {
        Object v = e.getProperties().get(key);
        return v instanceof String s ? s : def;
    }

    private static double dblProp(CADEntity e, String key, double def) {
        Object v = e.getProperties().get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    /**
     * 写出一个 group code 对，格式：
     * <pre>
     *   code 行（右对齐，宽度 ≤ 3 字符）
     *   value 行
     * </pre>
     */
    private static void pair(LineWriter w, int code, String value) throws IOException {
        w.writeLine(formatCode(code));
        w.writeLine(value);
    }

    /** DXF group code 行格式：0-9 → "  N"，10-99 → " NN"，≥100 → "NNN"。 */
    private static String formatCode(int code) {
        if (code < 10)  return "  " + code;
        if (code < 100) return " " + code;
        return String.valueOf(code);
    }

    // -------------------------------------------------------------------------
    // 轻量行写入器（统一使用 \r\n，跨平台）
    // -------------------------------------------------------------------------

    private static class LineWriter {
        private final Writer w;
        LineWriter(Writer w) { this.w = w; }

        void writeLine(String s) throws IOException {
            w.write(s);
            w.write("\r\n");
        }

        void flush() throws IOException { w.flush(); }
    }
}
