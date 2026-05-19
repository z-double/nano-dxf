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
 * <p>输出文件结构（R2000 标准）：
 * HEADER → CLASSES（空）→ TABLES（LTYPE + LAYER + STYLE）→
 * BLOCKS（*Model_Space / *Paper_Space）→ ENTITIES → OBJECTS（空）→ EOF
 *
 * <p>支持输出的几何类型（JTS → DXF）：
 * <ul>
 *   <li>{@link Point}      → POINT</li>
 *   <li>{@link LineString}（2 点）→ LINE</li>
 *   <li>{@link LineString}（多点）→ LWPOLYLINE</li>
 *   <li>{@link LinearRing} → LWPOLYLINE（闭合标志 bit0=1）</li>
 *   <li>{@link Polygon}    → 外环 LWPOLYLINE + 每个洞各一条 LWPOLYLINE</li>
 *   <li>{@link GeometryCollection} → 逐元素展开</li>
 * </ul>
 *
 * <p>颜色：R2004+ 且有 {@code colorRgb} → True Color（code 420）；
 * 有 {@code colorAci} → code 62；否则 BYLAYER。
 *
 * <pre>{@code
 * new DXFWriter().write(entities, Paths.get("output.dxf"));
 * }</pre>
 */
public class DXFWriter {

    private final DXFWriteConfig config;

    public DXFWriter() { this(DXFWriteConfig.defaults()); }

    public DXFWriter(DXFWriteConfig config) { this.config = config; }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    public void write(List<CADEntity> entities, Path path) throws IOException {
        Charset cs = Charset.forName(config.getEncoding());
        try (Writer w = new OutputStreamWriter(
                new BufferedOutputStream(Files.newOutputStream(path)), cs)) {
            write(entities, w);
        }
    }

    public void write(List<CADEntity> entities, Writer out) throws IOException {
        LineWriter w = new LineWriter(out);
        int[] h = {1}; // handle 计数器

        Set<String> layers = collectLayers(entities);

        writeHeader(w);
        writeClasses(w);
        writeTables(w, layers, h);
        writeBlocks(w, h);
        writeEntitiesSection(w, entities, h);
        writeObjects(w, h);
        pair(w, 0, "EOF");
        w.flush();
    }

    // -------------------------------------------------------------------------
    // HEADER
    // -------------------------------------------------------------------------

    private void writeHeader(LineWriter w) throws IOException {
        pair(w, 0, "SECTION");
        pair(w, 2, "HEADER");

        pair(w, 9, "$ACADVER");
        pair(w, 1, config.getVersion().getVersionString());

        pair(w, 9, "$INSUNITS");
        pair(w, 70, "6");           // 6 = 米

        pair(w, 9, "$LTSCALE");
        pair(w, 40, fmt(1.0));

        pair(w, 9, "$EXTMIN");
        pair(w, 10, fmt(0.0)); pair(w, 20, fmt(0.0)); pair(w, 30, fmt(0.0));

        pair(w, 9, "$EXTMAX");
        pair(w, 10, fmt(0.0)); pair(w, 20, fmt(0.0)); pair(w, 30, fmt(0.0));

        pair(w, 0, "ENDSEC");
    }

    // -------------------------------------------------------------------------
    // CLASSES（空段，R2000 必须存在）
    // -------------------------------------------------------------------------

    private void writeClasses(LineWriter w) throws IOException {
        pair(w, 0, "SECTION");
        pair(w, 2, "CLASSES");
        pair(w, 0, "ENDSEC");
    }

    // -------------------------------------------------------------------------
    // TABLES：LTYPE + LAYER + STYLE
    // -------------------------------------------------------------------------

    private void writeTables(LineWriter w, Set<String> layers, int[] h) throws IOException {
        pair(w, 0, "SECTION");
        pair(w, 2, "TABLES");

        writeLtypeTable(w, h);
        writeLayerTable(w, layers, h);
        writeStyleTable(w, h);

        pair(w, 0, "ENDSEC");
    }

    /** LTYPE 表：仅注册 Continuous。 */
    private void writeLtypeTable(LineWriter w, int[] h) throws IOException {
        pair(w, 0, "TABLE");  pair(w, 2, "LTYPE");
        pair(w, 5, hex(h[0]++));
        pair(w, 100, "AcDbSymbolTable");
        pair(w, 70, "1");

        pair(w, 0, "LTYPE");
        pair(w, 5, hex(h[0]++));
        pair(w, 100, "AcDbSymbolTableRecord");
        pair(w, 100, "AcDbLinetypeTableRecord");
        pair(w, 2, "Continuous");
        pair(w, 70, "0");
        pair(w, 3, "Solid line");
        pair(w, 72, "65");
        pair(w, 73, "0");
        pair(w, 40, fmt(0.0));

        pair(w, 0, "ENDTAB");
    }

    /** LAYER 表：为每个图层写一条记录。 */
    private void writeLayerTable(LineWriter w, Set<String> layers, int[] h) throws IOException {
        pair(w, 0, "TABLE");  pair(w, 2, "LAYER");
        pair(w, 5, hex(h[0]++));
        pair(w, 100, "AcDbSymbolTable");
        pair(w, 70, String.valueOf(layers.size()));

        for (String layer : layers) {
            pair(w, 0, "LAYER");
            pair(w, 5, hex(h[0]++));
            pair(w, 100, "AcDbSymbolTableRecord");
            pair(w, 100, "AcDbLayerTableRecord");
            pair(w, 2, layer);
            pair(w, 70, "0");   // 可见
            pair(w, 62, "7");   // 颜色 7（白/黑）
            pair(w, 6, "Continuous");
        }

        pair(w, 0, "ENDTAB");
    }

    /** STYLE 表：仅注册 Standard 样式。 */
    private void writeStyleTable(LineWriter w, int[] h) throws IOException {
        pair(w, 0, "TABLE");  pair(w, 2, "STYLE");
        pair(w, 5, hex(h[0]++));
        pair(w, 100, "AcDbSymbolTable");
        pair(w, 70, "1");

        pair(w, 0, "STYLE");
        pair(w, 5, hex(h[0]++));
        pair(w, 100, "AcDbSymbolTableRecord");
        pair(w, 100, "AcDbTextStyleTableRecord");
        pair(w, 2, "Standard");
        pair(w, 70, "0");
        pair(w, 40, fmt(0.0));   // 固定字高（0=不固定）
        pair(w, 41, fmt(1.0));   // 宽度系数
        pair(w, 50, fmt(0.0));   // 倾斜角
        pair(w, 71, "0");
        pair(w, 42, fmt(2.5));   // 最后使用的字高
        pair(w, 3, "txt");       // 字体文件

        pair(w, 0, "ENDTAB");
    }

    // -------------------------------------------------------------------------
    // BLOCKS：*Model_Space + *Paper_Space（R2000 必须存在）
    // -------------------------------------------------------------------------

    private void writeBlocks(LineWriter w, int[] h) throws IOException {
        pair(w, 0, "SECTION");
        pair(w, 2, "BLOCKS");
        writeBlockDef(w, "*Model_Space", h);
        writeBlockDef(w, "*Paper_Space", h);
        pair(w, 0, "ENDSEC");
    }

    private void writeBlockDef(LineWriter w, String name, int[] h) throws IOException {
        pair(w, 0, "BLOCK");
        pair(w, 5, hex(h[0]++));
        pair(w, 100, "AcDbEntity");
        pair(w, 8, "0");
        pair(w, 100, "AcDbBlockBegin");
        pair(w, 2, name);
        pair(w, 70, "0");
        pair(w, 10, fmt(0.0)); pair(w, 20, fmt(0.0)); pair(w, 30, fmt(0.0));
        pair(w, 3, name);
        pair(w, 1, "");

        pair(w, 0, "ENDBLK");
        pair(w, 5, hex(h[0]++));
        pair(w, 100, "AcDbEntity");
        pair(w, 8, "0");
        pair(w, 100, "AcDbBlockEnd");
    }

    // -------------------------------------------------------------------------
    // ENTITIES section
    // -------------------------------------------------------------------------

    private void writeEntitiesSection(LineWriter w, List<CADEntity> entities,
                                       int[] h) throws IOException {
        pair(w, 0, "SECTION");
        pair(w, 2, "ENTITIES");
        for (CADEntity entity : entities) writeEntity(w, entity, h);
        pair(w, 0, "ENDSEC");
    }

    private void writeEntity(LineWriter w, CADEntity entity, int[] h) throws IOException {
        if ("TEXT".equals(entity.getType()) && entity.geometry() instanceof Point p) {
            writeText(w, p, entity, h); return;
        }
        if ("MTEXT".equals(entity.getType()) && entity.geometry() instanceof Point p) {
            writeMText(w, p, entity, h); return;
        }
        if (entity.geometry() != null) writeGeometry(w, entity.geometry(), entity, h);
    }

    private void writeGeometry(LineWriter w, Geometry geom, CADEntity entity,
                                int[] h) throws IOException {
        if (geom instanceof Point p) {
            writePoint(w, p, entity, h);
        } else if (geom instanceof LinearRing lr) {
            writeLwPolyline(w, lr.getCoordinates(), true, entity, h);
        } else if (geom instanceof LineString ls) {
            Coordinate[] coords = ls.getCoordinates();
            if (coords.length == 2) writeLine(w, ls, entity, h);
            else                    writeLwPolyline(w, coords, false, entity, h);
        } else if (geom instanceof Polygon poly) {
            writeLwPolyline(w, poly.getExteriorRing().getCoordinates(), true, entity, h);
            for (int i = 0; i < poly.getNumInteriorRing(); i++) {
                writeLwPolyline(w, poly.getInteriorRingN(i).getCoordinates(), true, entity, h);
            }
        } else if (geom instanceof GeometryCollection gc) {
            for (int i = 0; i < gc.getNumGeometries(); i++) {
                writeGeometry(w, gc.getGeometryN(i), entity, h);
            }
        }
    }

    // -------------------------------------------------------------------------
    // 各实体类型
    // -------------------------------------------------------------------------

    private void writePoint(LineWriter w, Point p, CADEntity entity,
                             int[] h) throws IOException {
        pair(w, 0, "POINT");
        writeEntityHeader(w, entity, h);
        pair(w, 100, "AcDbPoint");
        pair(w, 10, fmt(p.getX()));
        pair(w, 20, fmt(p.getY()));
        pair(w, 30, fmtZ(p.getCoordinate().getZ()));
    }

    private void writeLine(LineWriter w, LineString ls, CADEntity entity,
                            int[] h) throws IOException {
        Coordinate s = ls.getCoordinateN(0);
        Coordinate e = ls.getCoordinateN(1);
        pair(w, 0, "LINE");
        writeEntityHeader(w, entity, h);
        pair(w, 100, "AcDbLine");
        pair(w, 10, fmt(s.x)); pair(w, 20, fmt(s.y)); pair(w, 30, fmtZ(s.getZ()));
        pair(w, 11, fmt(e.x)); pair(w, 21, fmt(e.y)); pair(w, 31, fmtZ(e.getZ()));
    }

    private void writeLwPolyline(LineWriter w, Coordinate[] coords, boolean closed,
                                  CADEntity entity, int[] h) throws IOException {
        // 闭合环首尾重复点去掉末尾
        int n = (closed && coords.length > 1
                && coords[0].equals2D(coords[coords.length - 1]))
                ? coords.length - 1 : coords.length;
        if (n < 2) return;

        pair(w, 0, "LWPOLYLINE");
        writeEntityHeader(w, entity, h);
        pair(w, 100, "AcDbPolyline");
        pair(w, 90, String.valueOf(n));
        pair(w, 70, closed ? "1" : "0");

        // Elevation：Z 值一致且非零时输出 code 38
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
        if (uniformZ && Math.abs(z0) > 1e-12) pair(w, 38, fmt(z0));

        for (int i = 0; i < n; i++) {
            pair(w, 10, fmt(coords[i].x));
            pair(w, 20, fmt(coords[i].y));
        }
    }

    private void writeText(LineWriter w, Point p, CADEntity entity,
                            int[] h) throws IOException {
        String text = strProp(entity, "text");
        if (text.isBlank()) return;
        pair(w, 0, "TEXT");
        writeEntityHeader(w, entity, h);
        pair(w, 100, "AcDbText");
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        pair(w, 40, fmt(dblProp(entity, "height", 2.5)));
        pair(w, 1, text);
        double rot = dblProp(entity, "rotation", 0.0);
        if (Math.abs(rot) > 1e-9) pair(w, 50, fmt(rot));
        pair(w, 7, strPropOrDefault(entity, "style", "Standard"));
        pair(w, 100, "AcDbText"); // TEXT 需要两个子类标记
    }

    private void writeMText(LineWriter w, Point p, CADEntity entity,
                             int[] h) throws IOException {
        String text = strProp(entity, "text");
        if (text.isBlank()) return;
        pair(w, 0, "MTEXT");
        writeEntityHeader(w, entity, h);
        pair(w, 100, "AcDbMText");
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        pair(w, 40, fmt(dblProp(entity, "height", 2.5)));
        pair(w, 41, fmt(0.0));   // 参考矩形宽度（0=无限制）
        pair(w, 71, "1");        // 附着点：左上
        pair(w, 72, "5");        // 文字方向：从左到右
        pair(w, 1, text);
    }

    // -------------------------------------------------------------------------
    // 实体公共头：handle + AcDbEntity + 图层 + 颜色
    // -------------------------------------------------------------------------

    private void writeEntityHeader(LineWriter w, CADEntity entity,
                                    int[] h) throws IOException {
        pair(w, 5, hex(h[0]++));
        pair(w, 100, "AcDbEntity");
        pair(w, 8, entity.getLayer() != null ? entity.getLayer() : "0");
        writeColor(w, entity);
    }

    private void writeColor(LineWriter w, CADEntity entity) throws IOException {
        Object rgb = entity.getProperties().get("colorRgb");
        Object aci = entity.getProperties().get("colorAci");
        if (rgb instanceof int[] arr && !config.getVersion().before(DXFVersion.R2004)) {
            pair(w, 420, String.valueOf((arr[0] << 16) | (arr[1] << 8) | arr[2]));
        } else if (aci instanceof Integer v) {
            pair(w, 62, String.valueOf(v));
        }
    }

    // -------------------------------------------------------------------------
    // OBJECTS（空段，R2000 规范要求存在）
    // -------------------------------------------------------------------------

    private void writeObjects(LineWriter w, int[] h) throws IOException {
        pair(w, 0, "SECTION");
        pair(w, 2, "OBJECTS");
        // 最小 root dictionary
        pair(w, 0, "DICTIONARY");
        pair(w, 5, hex(h[0]++));
        pair(w, 100, "AcDbDictionary");
        pair(w, 281, "1");
        pair(w, 0, "ENDSEC");
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    private Set<String> collectLayers(List<CADEntity> entities) {
        Set<String> layers = new LinkedHashSet<>();
        layers.add("0");
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

    private static void pair(LineWriter w, int code, String value) throws IOException {
        w.writeLine(fmtCode(code));
        w.writeLine(value);
    }

    /** group code 行格式：0-9 → "  N"，10-99 → " NN"，≥100 → "NNN"。 */
    private static String fmtCode(int code) {
        if (code < 10)  return "  " + code;
        if (code < 100) return " " + code;
        return String.valueOf(code);
    }

    // -------------------------------------------------------------------------
    // 行写入器（跨平台统一 \r\n）
    // -------------------------------------------------------------------------

    private static class LineWriter {
        private final Writer w;
        LineWriter(Writer w) { this.w = w; }
        void writeLine(String s) throws IOException { w.write(s); w.write("\r\n"); }
        void flush() throws IOException { w.flush(); }
    }
}
