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
 * <p><b>版本差异</b>：
 * <ul>
 *   <li>R12（默认）— 最兼容格式，无子类标记、无 owner handle、无 BLOCKS/OBJECTS 段。
 *       AutoCAD R12+、QGIS、LibreCAD、FreeCAD 均可打开。</li>
 *   <li>R2000+    — 完整格式，含子类标记（code 100）、owner handle（code 330）、
 *       BLOCK_RECORD 表、BLOCKS/CLASSES/OBJECTS 段，支持 True Color（R2004+）。</li>
 * </ul>
 *
 * <p>支持的 JTS 几何类型（JTS → DXF）：
 * <ul>
 *   <li>{@link Point}      → POINT</li>
 *   <li>{@link LineString}（2 点）→ LINE</li>
 *   <li>{@link LineString}（多点）→ LWPOLYLINE</li>
 *   <li>{@link LinearRing} → LWPOLYLINE（闭合标志 bit0=1）</li>
 *   <li>{@link Polygon}    → 外环 + 每个洞各一条 LWPOLYLINE</li>
 *   <li>{@link GeometryCollection} → 逐元素展开</li>
 * </ul>
 *
 * <pre>{@code
 * // 默认 R12，最兼容
 * new DXFWriter().write(entities, Paths.get("output.dxf"));
 *
 * // 指定 R2000，含 True Color
 * DXFWriteConfig cfg = DXFWriteConfig.builder()
 *     .version(DXFVersion.R2000).coordinateDecimalPlaces(4).build();
 * new DXFWriter(cfg).write(entities, Paths.get("output.dxf"));
 * }</pre>
 */
public class DXFWriter {

    // -------------------------------------------------------------------------
    // 预分配固定句柄（R2000+ 路径专用，R12 路径不使用）
    // -------------------------------------------------------------------------
    private static final String H_BR_TABLE    = "1";  // BLOCK_RECORD TABLE
    private static final String H_LT_TABLE    = "2";  // LTYPE TABLE
    private static final String H_LY_TABLE    = "3";  // LAYER TABLE
    private static final String H_ST_TABLE    = "4";  // STYLE TABLE
    private static final String H_LT_CONT     = "5";  // Continuous LTYPE record
    private static final String H_ST_STD      = "6";  // Standard STYLE record
    private static final String H_MS_BR       = "7";  // *Model_Space BLOCK_RECORD
    private static final String H_PS_BR       = "8";  // *Paper_Space BLOCK_RECORD
    private static final String H_MS_BLOCK    = "9";  // *Model_Space BLOCK 实体
    private static final String H_MS_ENDBLK   = "A";  // *Model_Space ENDBLK 实体
    private static final String H_PS_BLOCK    = "B";  // *Paper_Space BLOCK 实体
    private static final String H_PS_ENDBLK   = "C";  // *Paper_Space ENDBLK 实体
    private static final String H_ROOT_DICT   = "D";  // root DICTIONARY
    // 图层记录从 0x10 开始，实体句柄从 0x100 开始（由计数器动态分配）

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
        Set<String> layers = collectLayers(entities);

        if (isR12()) {
            writeR12(w, entities, layers);
        } else {
            writeR2000(w, entities, layers);
        }
        w.flush();
    }

    // =========================================================================
    // R12 路径（简单，无子类标记，无 owner handle）
    // =========================================================================

    private void writeR12(LineWriter w, List<CADEntity> entities,
                          Set<String> layers) throws IOException {
        writeR12Header(w);
        writeR12Tables(w, layers);
        writeR12Entities(w, entities);
        pair(w, 0, "EOF");
    }

    private void writeR12Header(LineWriter w) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "HEADER");
        pair(w, 9, "$ACADVER"); pair(w, 1, "AC1009");
        pair(w, 9, "$INSUNITS"); pair(w, 70, "6");
        pair(w, 9, "$LTSCALE"); pair(w, 40, fmt(1.0));
        pair(w, 0, "ENDSEC");
    }

    private void writeR12Tables(LineWriter w, Set<String> layers) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "TABLES");
        pair(w, 0, "TABLE"); pair(w, 2, "LAYER");
        pair(w, 70, String.valueOf(layers.size()));
        for (String l : layers) {
            pair(w, 0, "LAYER");
            pair(w, 2, l);
            pair(w, 70, "0");
            pair(w, 62, "7");
            pair(w, 6, "CONTINUOUS");
        }
        pair(w, 0, "ENDTAB");
        pair(w, 0, "ENDSEC");
    }

    private void writeR12Entities(LineWriter w, List<CADEntity> entities) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "ENTITIES");
        for (CADEntity e : entities) writeEntityR12(w, e);
        pair(w, 0, "ENDSEC");
    }

    private void writeEntityR12(LineWriter w, CADEntity entity) throws IOException {
        if ("TEXT".equals(entity.getType()) && entity.geometry() instanceof Point p) {
            writeTextR12(w, p, entity); return;
        }
        if ("MTEXT".equals(entity.getType()) && entity.geometry() instanceof Point p) {
            writeMTextR12(w, p, entity); return;
        }
        if (entity.geometry() != null) writeGeomR12(w, entity.geometry(), entity);
    }

    private void writeGeomR12(LineWriter w, Geometry geom, CADEntity e) throws IOException {
        if (geom instanceof Point p) {
            writePointR12(w, p, e);
        } else if (geom instanceof LinearRing lr) {
            writeLwR12(w, lr.getCoordinates(), true, e);
        } else if (geom instanceof LineString ls) {
            Coordinate[] cs = ls.getCoordinates();
            if (cs.length == 2) writeLineR12(w, ls, e);
            else                writeLwR12(w, cs, false, e);
        } else if (geom instanceof Polygon poly) {
            writeLwR12(w, poly.getExteriorRing().getCoordinates(), true, e);
            for (int i = 0; i < poly.getNumInteriorRing(); i++)
                writeLwR12(w, poly.getInteriorRingN(i).getCoordinates(), true, e);
        } else if (geom instanceof GeometryCollection gc) {
            for (int i = 0; i < gc.getNumGeometries(); i++)
                writeGeomR12(w, gc.getGeometryN(i), e);
        }
    }

    private void writePointR12(LineWriter w, Point p, CADEntity e) throws IOException {
        pair(w, 0, "POINT");
        writeR12Common(w, e);
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
    }

    private void writeLineR12(LineWriter w, LineString ls, CADEntity e) throws IOException {
        Coordinate s = ls.getCoordinateN(0), t = ls.getCoordinateN(1);
        pair(w, 0, "LINE");
        writeR12Common(w, e);
        pair(w, 10, fmt(s.x)); pair(w, 20, fmt(s.y)); pair(w, 30, fmtZ(s.getZ()));
        pair(w, 11, fmt(t.x)); pair(w, 21, fmt(t.y)); pair(w, 31, fmtZ(t.getZ()));
    }

    private void writeLwR12(LineWriter w, Coordinate[] coords, boolean closed,
                             CADEntity e) throws IOException {
        int n = trimClosedEnd(coords, closed);
        if (n < 2) return;
        pair(w, 0, "POLYLINE");
        writeR12Common(w, e);
        pair(w, 66, "1");           // 顶点后续标志
        pair(w, 70, closed ? "1" : "0");
        double z0 = uniformZ(coords, n);
        if (!Double.isNaN(z0) && Math.abs(z0) > 1e-12) pair(w, 38, fmt(z0));
        for (int i = 0; i < n; i++) {
            pair(w, 0, "VERTEX");
            pair(w, 8, layerOf(e));
            pair(w, 10, fmt(coords[i].x));
            pair(w, 20, fmt(coords[i].y));
        }
        pair(w, 0, "SEQEND");
        pair(w, 8, layerOf(e));
    }

    private void writeTextR12(LineWriter w, Point p, CADEntity e) throws IOException {
        String text = strProp(e, "text");
        if (text.isBlank()) return;
        pair(w, 0, "TEXT");
        writeR12Common(w, e);
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        pair(w, 40, fmt(dblProp(e, "height", 2.5)));
        pair(w, 1, text);
        double rot = dblProp(e, "rotation", 0.0);
        if (Math.abs(rot) > 1e-9) pair(w, 50, fmt(rot));
    }

    private void writeMTextR12(LineWriter w, Point p, CADEntity e) throws IOException {
        // R12 无 MTEXT，降级为 TEXT（取第一行）
        String text = strProp(e, "text").replace("\\P", " ").replaceAll("\\\\[A-Za-z][^;]*;", "");
        if (text.isBlank()) return;
        pair(w, 0, "TEXT");
        writeR12Common(w, e);
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        pair(w, 40, fmt(dblProp(e, "height", 2.5)));
        pair(w, 1, text);
    }

    /** R12 实体公共字段：图层 + 颜色（无 handle，无 code 100）。 */
    private void writeR12Common(LineWriter w, CADEntity e) throws IOException {
        pair(w, 8, layerOf(e));
        Object aci = e.getProperties().get("colorAci");
        if (aci instanceof Integer v) pair(w, 62, String.valueOf(v));
    }

    // =========================================================================
    // R2000+ 路径（含子类标记、owner handle、完整符号表）
    // =========================================================================

    private void writeR2000(LineWriter w, List<CADEntity> entities,
                             Set<String> layers) throws IOException {
        // 图层句柄从 0x10 起，实体句柄从 0x100 起
        int[] lyH = {0x10};
        int[] enH = {0x100};

        writeR2000Header(w);
        pair(w, 0, "SECTION"); pair(w, 2, "CLASSES"); pair(w, 0, "ENDSEC");
        writeR2000Tables(w, layers, lyH);
        writeR2000Blocks(w);
        writeR2000Entities(w, entities, enH);
        writeR2000Objects(w);
        pair(w, 0, "EOF");
    }

    private void writeR2000Header(LineWriter w) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "HEADER");
        pair(w, 9, "$ACADVER"); pair(w, 1, config.getVersion().getVersionString());
        pair(w, 9, "$INSUNITS"); pair(w, 70, "6");
        pair(w, 9, "$LTSCALE"); pair(w, 40, fmt(1.0));
        pair(w, 0, "ENDSEC");
    }

    private void writeR2000Tables(LineWriter w, Set<String> layers, int[] lyH) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "TABLES");

        // BLOCK_RECORD 表（*Model_Space + *Paper_Space）
        pair(w, 0, "TABLE"); pair(w, 2, "BLOCK_RECORD");
        pair(w, 5, H_BR_TABLE); pair(w, 100, "AcDbSymbolTable"); pair(w, 70, "2");
        writeBlockRecord(w, H_MS_BR, "*Model_Space");
        writeBlockRecord(w, H_PS_BR, "*Paper_Space");
        pair(w, 0, "ENDTAB");

        // LTYPE 表
        pair(w, 0, "TABLE"); pair(w, 2, "LTYPE");
        pair(w, 5, H_LT_TABLE); pair(w, 100, "AcDbSymbolTable"); pair(w, 70, "1");
        pair(w, 0, "LTYPE"); pair(w, 5, H_LT_CONT);
        pair(w, 330, H_LT_TABLE);
        pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbLinetypeTableRecord");
        pair(w, 2, "Continuous"); pair(w, 70, "0");
        pair(w, 3, "Solid line"); pair(w, 72, "65"); pair(w, 73, "0"); pair(w, 40, fmt(0.0));
        pair(w, 0, "ENDTAB");

        // LAYER 表
        pair(w, 0, "TABLE"); pair(w, 2, "LAYER");
        pair(w, 5, H_LY_TABLE); pair(w, 100, "AcDbSymbolTable");
        pair(w, 70, String.valueOf(layers.size()));
        for (String l : layers) {
            pair(w, 0, "LAYER"); pair(w, 5, hex(lyH[0]++));
            pair(w, 330, H_LY_TABLE);
            pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbLayerTableRecord");
            pair(w, 2, l); pair(w, 70, "0"); pair(w, 62, "7"); pair(w, 6, "Continuous");
        }
        pair(w, 0, "ENDTAB");

        // STYLE 表
        pair(w, 0, "TABLE"); pair(w, 2, "STYLE");
        pair(w, 5, H_ST_TABLE); pair(w, 100, "AcDbSymbolTable"); pair(w, 70, "1");
        pair(w, 0, "STYLE"); pair(w, 5, H_ST_STD);
        pair(w, 330, H_ST_TABLE);
        pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbTextStyleTableRecord");
        pair(w, 2, "Standard"); pair(w, 70, "0");
        pair(w, 40, fmt(0.0)); pair(w, 41, fmt(1.0)); pair(w, 50, fmt(0.0));
        pair(w, 71, "0"); pair(w, 42, fmt(2.5)); pair(w, 3, "txt"); pair(w, 4, "");
        pair(w, 0, "ENDTAB");

        pair(w, 0, "ENDSEC");
    }

    private void writeBlockRecord(LineWriter w, String h, String name) throws IOException {
        pair(w, 0, "BLOCK_RECORD"); pair(w, 5, h);
        pair(w, 330, H_BR_TABLE);
        pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbBlockTableRecord");
        pair(w, 2, name); pair(w, 70, "0");
    }

    private void writeR2000Blocks(LineWriter w) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "BLOCKS");
        writeBlockDef(w, H_MS_BLOCK, H_MS_ENDBLK, H_MS_BR, "*Model_Space");
        writeBlockDef(w, H_PS_BLOCK, H_PS_ENDBLK, H_PS_BR, "*Paper_Space");
        pair(w, 0, "ENDSEC");
    }

    private void writeBlockDef(LineWriter w, String hBlock, String hEndblk,
                                String hBR, String name) throws IOException {
        pair(w, 0, "BLOCK"); pair(w, 5, hBlock);
        pair(w, 330, hBR);
        pair(w, 100, "AcDbEntity"); pair(w, 8, "0");
        pair(w, 100, "AcDbBlockBegin");
        pair(w, 2, name); pair(w, 70, "0");
        pair(w, 10, fmt(0.0)); pair(w, 20, fmt(0.0)); pair(w, 30, fmt(0.0));
        pair(w, 3, name); pair(w, 1, "");

        pair(w, 0, "ENDBLK"); pair(w, 5, hEndblk);
        pair(w, 330, hBR);
        pair(w, 100, "AcDbEntity"); pair(w, 8, "0");
        pair(w, 100, "AcDbBlockEnd");
    }

    private void writeR2000Entities(LineWriter w, List<CADEntity> entities,
                                     int[] enH) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "ENTITIES");
        for (CADEntity e : entities) writeEntityR2000(w, e, enH);
        pair(w, 0, "ENDSEC");
    }

    private void writeEntityR2000(LineWriter w, CADEntity entity, int[] enH) throws IOException {
        if ("TEXT".equals(entity.getType()) && entity.geometry() instanceof Point p) {
            writeTextR2000(w, p, entity, enH); return;
        }
        if ("MTEXT".equals(entity.getType()) && entity.geometry() instanceof Point p) {
            writeMTextR2000(w, p, entity, enH); return;
        }
        if (entity.geometry() != null) writeGeomR2000(w, entity.geometry(), entity, enH);
    }

    private void writeGeomR2000(LineWriter w, Geometry geom, CADEntity e,
                                 int[] enH) throws IOException {
        if (geom instanceof Point p) {
            writePointR2000(w, p, e, enH);
        } else if (geom instanceof LinearRing lr) {
            writeLwR2000(w, lr.getCoordinates(), true, e, enH);
        } else if (geom instanceof LineString ls) {
            Coordinate[] cs = ls.getCoordinates();
            if (cs.length == 2) writeLineR2000(w, ls, e, enH);
            else                writeLwR2000(w, cs, false, e, enH);
        } else if (geom instanceof Polygon poly) {
            writeLwR2000(w, poly.getExteriorRing().getCoordinates(), true, e, enH);
            for (int i = 0; i < poly.getNumInteriorRing(); i++)
                writeLwR2000(w, poly.getInteriorRingN(i).getCoordinates(), true, e, enH);
        } else if (geom instanceof GeometryCollection gc) {
            for (int i = 0; i < gc.getNumGeometries(); i++)
                writeGeomR2000(w, gc.getGeometryN(i), e, enH);
        }
    }

    private void writePointR2000(LineWriter w, Point p, CADEntity e, int[] h) throws IOException {
        pair(w, 0, "POINT");
        writeR2000Common(w, e, h);
        pair(w, 100, "AcDbPoint");
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
    }

    private void writeLineR2000(LineWriter w, LineString ls, CADEntity e, int[] h) throws IOException {
        Coordinate s = ls.getCoordinateN(0), t = ls.getCoordinateN(1);
        pair(w, 0, "LINE");
        writeR2000Common(w, e, h);
        pair(w, 100, "AcDbLine");
        pair(w, 10, fmt(s.x)); pair(w, 20, fmt(s.y)); pair(w, 30, fmtZ(s.getZ()));
        pair(w, 11, fmt(t.x)); pair(w, 21, fmt(t.y)); pair(w, 31, fmtZ(t.getZ()));
    }

    private void writeLwR2000(LineWriter w, Coordinate[] coords, boolean closed,
                               CADEntity e, int[] h) throws IOException {
        int n = trimClosedEnd(coords, closed);
        if (n < 2) return;
        pair(w, 0, "LWPOLYLINE");
        writeR2000Common(w, e, h);
        pair(w, 100, "AcDbPolyline");
        pair(w, 90, String.valueOf(n));
        pair(w, 70, closed ? "1" : "0");
        double z0 = uniformZ(coords, n);
        if (!Double.isNaN(z0) && Math.abs(z0) > 1e-12) pair(w, 38, fmt(z0));
        for (int i = 0; i < n; i++) {
            pair(w, 10, fmt(coords[i].x)); pair(w, 20, fmt(coords[i].y));
        }
    }

    private void writeTextR2000(LineWriter w, Point p, CADEntity e, int[] h) throws IOException {
        String text = strProp(e, "text");
        if (text.isBlank()) return;
        pair(w, 0, "TEXT");
        writeR2000Common(w, e, h);
        pair(w, 100, "AcDbText");
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        pair(w, 40, fmt(dblProp(e, "height", 2.5)));
        pair(w, 1, text);
        double rot = dblProp(e, "rotation", 0.0);
        if (Math.abs(rot) > 1e-9) pair(w, 50, fmt(rot));
        pair(w, 7, strPropOrDefault(e, "style", "Standard"));
        pair(w, 100, "AcDbText"); // TEXT 需要两个 AcDbText 子类标记
    }

    private void writeMTextR2000(LineWriter w, Point p, CADEntity e, int[] h) throws IOException {
        String text = strProp(e, "text");
        if (text.isBlank()) return;
        pair(w, 0, "MTEXT");
        writeR2000Common(w, e, h);
        pair(w, 100, "AcDbMText");
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        pair(w, 40, fmt(dblProp(e, "height", 2.5)));
        pair(w, 41, fmt(0.0));
        pair(w, 71, "1");
        pair(w, 72, "5");
        pair(w, 1, text);
    }

    /** R2000+ 实体公共头：handle + owner(330) + AcDbEntity + 图层 + 颜色。 */
    private void writeR2000Common(LineWriter w, CADEntity e, int[] h) throws IOException {
        pair(w, 5, hex(h[0]++));
        pair(w, 330, H_MS_BR);      // owner = *Model_Space BLOCK_RECORD
        pair(w, 100, "AcDbEntity");
        pair(w, 8, layerOf(e));
        Object rgb = e.getProperties().get("colorRgb");
        Object aci = e.getProperties().get("colorAci");
        if (rgb instanceof int[] arr && !config.getVersion().before(DXFVersion.R2004)) {
            pair(w, 420, String.valueOf((arr[0] << 16) | (arr[1] << 8) | arr[2]));
        } else if (aci instanceof Integer v) {
            pair(w, 62, String.valueOf(v));
        }
    }

    private void writeR2000Objects(LineWriter w) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "OBJECTS");
        pair(w, 0, "DICTIONARY"); pair(w, 5, H_ROOT_DICT);
        pair(w, 100, "AcDbDictionary"); pair(w, 281, "1");
        pair(w, 0, "ENDSEC");
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private boolean isR12() { return config.getVersion() == DXFVersion.R12; }

    private Set<String> collectLayers(List<CADEntity> entities) {
        Set<String> layers = new LinkedHashSet<>();
        layers.add("0");
        for (CADEntity e : entities) {
            String l = e.getLayer();
            if (l != null && !l.isEmpty()) layers.add(l);
        }
        return layers;
    }

    private static String layerOf(CADEntity e) {
        return e.getLayer() != null && !e.getLayer().isEmpty() ? e.getLayer() : "0";
    }

    /** 闭合环去掉重复末尾点，返回有效顶点数。 */
    private static int trimClosedEnd(Coordinate[] coords, boolean closed) {
        if (closed && coords.length > 1
                && coords[0].equals2D(coords[coords.length - 1])) {
            return coords.length - 1;
        }
        return coords.length;
    }

    /**
     * 若前 n 个顶点 Z 值相同（且非 NaN），返回该 Z 值；否则返回 NaN。
     * 用于决定是否输出 LWPOLYLINE/POLYLINE 的 code 38 elevation。
     */
    private static double uniformZ(Coordinate[] coords, int n) {
        double z0 = coords[0].getZ();
        if (Double.isNaN(z0)) return Double.NaN;
        for (int i = 1; i < n; i++) {
            if (Double.isNaN(coords[i].getZ())
                    || Math.abs(coords[i].getZ() - z0) > 1e-9) return Double.NaN;
        }
        return z0;
    }

    private String fmt(double v) {
        return String.format("%." + config.getCoordinateDecimalPlaces() + "f", v);
    }

    private String fmtZ(double z) { return Double.isNaN(z) ? fmt(0.0) : fmt(z); }

    private static String hex(int n) { return Integer.toHexString(n).toUpperCase(); }

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
        w.writeLine(fmtCode(code)); w.writeLine(value);
    }

    private static String fmtCode(int code) {
        if (code < 10)  return "  " + code;
        if (code < 100) return " " + code;
        return String.valueOf(code);
    }

    private static class LineWriter {
        private final Writer w;
        LineWriter(Writer w) { this.w = w; }
        void writeLine(String s) throws IOException { w.write(s); w.write("\r\n"); }
        void flush() throws IOException { w.flush(); }
    }
}
