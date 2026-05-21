package com.nanodxf.output;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.CADBlock;
import com.nanodxf.model.DXFVersion;
import com.nanodxf.xdata.XDataEntry;
import org.locationtech.jts.geom.*;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 将 {@link CADEntity} 列表序列化为 DXF ASCII 文件。
 *
 * <p><b>版本差异</b>：
 * <ul>
 *   <li>R12（AC1009）— 最兼容格式，无子类标记、无 owner handle、无 BLOCKS/OBJECTS 段。
 *       AutoCAD R12+、QGIS、LibreCAD、FreeCAD 均可打开。</li>
 *   <li>R2007（AC1021）— 完整格式，含子类标记（code 100）、owner handle（code 330）、
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
 * <p>v1.2.0 新增实体类型（通过 type + properties 约定）：
 * <ul>
 *   <li>type=ARC,    geometry=Point(圆心),    properties={radius,startAngle,endAngle} → DXF ARC</li>
 *   <li>type=CIRCLE, geometry=Point(圆心),    properties={radius}                     → DXF CIRCLE</li>
 *   <li>type=HATCH,  geometry=Polygon,        properties={hatchPattern(默认SOLID)}    → DXF HATCH（R2007 only）</li>
 *   <li>type=INSERT, geometry=Point(插入点),  properties={blockName,scaleX,scaleY,scaleZ,rotation} → DXF INSERT</li>
 * </ul>
 *
 * <pre>{@code
 * // R12，最兼容
 * new DXFWriter().write(entities, Paths.get("output.dxf"));
 *
 * // R2007，含块定义
 * List<CADBlock> blocks = List.of(myBlock);
 * DXFWriteConfig cfg = DXFWriteConfig.builder().version(DXFVersion.R2007).build();
 * new DXFWriter(cfg).write(blocks, entities, Paths.get("output.dxf"));
 * }</pre>
 */
public class DXFWriter {

    // -------------------------------------------------------------------------
    // 预分配固定句柄（与参考文件 城建.dxf 一致，R12 路径不使用）
    // -------------------------------------------------------------------------
    private static final String H_BR_TABLE    = "1";   // BLOCK_RECORD TABLE
    private static final String H_LT_TABLE    = "2";   // LTYPE TABLE
    private static final String H_LY_TABLE    = "3";   // LAYER TABLE
    private static final String H_ST_TABLE    = "4";   // STYLE TABLE
    private static final String H_LT_CONT     = "5";   // Continuous LTYPE record
    private static final String H_ST_STD      = "6";   // Standard STYLE record
    private static final String H_MS_BR       = "1F";  // *Model_Space BLOCK_RECORD
    private static final String H_PS_BR       = "1B";  // *Paper_Space BLOCK_RECORD
    private static final String H_MS_BLOCK    = "20";  // *Model_Space BLOCK 实体
    private static final String H_MS_ENDBLK   = "21";  // *Model_Space ENDBLK 实体
    private static final String H_PS_BLOCK    = "1C";  // *Paper_Space BLOCK 实体
    private static final String H_PS_ENDBLK   = "1D";  // *Paper_Space ENDBLK 实体
    private static final String H_ROOT_DICT   = "C";   // root DICTIONARY
    private static final String H_LAYOUT_DICT = "1A";  // ACAD_LAYOUT 子字典
    private static final String H_LAYOUT_MS   = "22";  // *Model_Space LAYOUT 对象
    private static final String H_LAYOUT_PS   = "1E";  // *Paper_Space LAYOUT 对象
    private static final String H_VP_ACTIVE   = "E3";  // *Active VPORT 记录
    private static final String H_LT_BYBLOCK  = "E4";  // ByBlock LTYPE
    private static final String H_LT_BYLAYER  = "E5";  // ByLayer LTYPE
    private static final String H_VW_TABLE    = "E6";  // VIEW 表（空）
    private static final String H_UC_TABLE    = "E7";  // UCS 表（空）
    private static final String H_DS_TABLE    = "E8";  // DIMSTYLE 表
    private static final String H_DS_STD      = "E9";  // Standard DIMSTYLE 记录
    // 动态分配：图层记录 0x10+，实体 0x100+，用户块 BR 0x200+，用户块实体 0x300+

    private static final int H_USER_BR_BASE  = 0x200;  // 用户块 BLOCK_RECORD 起始
    private static final int H_USER_BLK_BASE = 0x210;  // 用户块 BLOCK/ENDBLK 句柄起始（每块占 2 个）
    private static final int H_USER_ENT_BASE = 0x300;  // 用户块内实体句柄起始

    private final DXFWriteConfig config;
    /** 坐标格式串，预计算避免 fmt() 每次拼接字符串（Task #8）。 */
    private final String fmtPattern;

    public DXFWriter() { this(DXFWriteConfig.defaults()); }
    public DXFWriter(DXFWriteConfig config) {
        this.config = config;
        this.fmtPattern = "%." + config.getCoordinateDecimalPlaces() + "f";
    }

    // =========================================================================
    // 公开 API
    // =========================================================================

    /** 写出实体列表（无块定义）。 */
    public void write(List<CADEntity> entities, Path path) throws IOException {
        write(List.of(), entities, path);
    }

    /** 写出实体列表（无块定义）到 Writer。 */
    public void write(List<CADEntity> entities, Writer out) throws IOException {
        write(List.of(), entities, out);
    }

    /** 写出块定义 + 实体列表到文件。块定义写入 BLOCKS 段，实体中的 INSERT 可引用块名。 */
    public void write(List<CADBlock> blocks, List<CADEntity> entities, Path path) throws IOException {
        Charset cs = Charset.forName(config.getEncoding());
        try (Writer w = new OutputStreamWriter(
                new BufferedOutputStream(Files.newOutputStream(path)), cs)) {
            write(blocks, entities, w);
        }
    }

    /** 写出块定义 + 实体列表到 Writer。 */
    public void write(List<CADBlock> blocks, List<CADEntity> entities, Writer out) throws IOException {
        Objects.requireNonNull(blocks,   "blocks must not be null");
        Objects.requireNonNull(entities, "entities must not be null");
        Objects.requireNonNull(out,      "out must not be null");
        LineWriter w = new LineWriter(out);
        Map<String, LayerInfo> layerInfo = collectLayerInfo(entities, blocks);

        if (isR12()) {
            writeR12(w, blocks, entities, layerInfo);
        } else {
            writeR2000(w, blocks, entities, layerInfo);
        }
        w.flush();
    }

    // =========================================================================
    // R12 路径（简单，无子类标记，无 owner handle）
    // =========================================================================

    private void writeR12(LineWriter w, List<CADBlock> blocks,
                           List<CADEntity> entities, Map<String, LayerInfo> layerInfo) throws IOException {
        Set<String> linetypes = collectLinetypes(layerInfo);
        writeR12Header(w);
        writeR12Tables(w, layerInfo, linetypes);
        if (!blocks.isEmpty()) writeR12Blocks(w, blocks); // BLOCKS 段必须在 ENTITIES 段之前
        writeR12Entities(w, entities);
        pair(w, 0, "EOF");
    }

    private void writeR12Blocks(LineWriter w, List<CADBlock> blocks) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "BLOCKS");
        for (CADBlock b : blocks) writeR12BlockDef(w, b);
        pair(w, 0, "ENDSEC");
    }

    private void writeR12Header(LineWriter w) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "HEADER");
        pair(w, 9, "$ACADVER"); pair(w, 1, "AC1009");
        pair(w, 9, "$INSUNITS"); pair(w, 70, "6");
        pair(w, 9, "$LTSCALE"); pair(w, 40, fmt(1.0));
        pair(w, 0, "ENDSEC");
    }

    private void writeR12Tables(LineWriter w, Map<String, LayerInfo> layerInfo,
                                 Set<String> linetypes) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "TABLES");

        // LTYPE 表
        pair(w, 0, "TABLE"); pair(w, 2, "LTYPE");
        pair(w, 70, String.valueOf(linetypes.size()));
        for (String lt : linetypes) {
            pair(w, 0, "LTYPE");
            pair(w, 2, lt);
            pair(w, 70, "0");
            pair(w, 3, "Continuous".equalsIgnoreCase(lt) ? "Solid line" : "");
            pair(w, 72, "65"); pair(w, 73, "0"); pair(w, 40, fmt(0.0));
        }
        pair(w, 0, "ENDTAB");

        // LAYER 表
        pair(w, 0, "TABLE"); pair(w, 2, "LAYER");
        pair(w, 70, String.valueOf(layerInfo.size()));
        for (Map.Entry<String, LayerInfo> e : layerInfo.entrySet()) {
            LayerInfo li = e.getValue();
            pair(w, 0, "LAYER");
            pair(w, 2, e.getKey());
            pair(w, 70, "0");
            pair(w, 62, String.valueOf(li.color()));
            pair(w, 6, li.lineType());
        }
        pair(w, 0, "ENDTAB");

        pair(w, 0, "ENDSEC");
    }

    private void writeR12Entities(LineWriter w, List<CADEntity> entities) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "ENTITIES");
        for (CADEntity e : entities) writeEntityR12(w, e);
        pair(w, 0, "ENDSEC");
    }

    private void writeR12BlockDef(LineWriter w, CADBlock block) throws IOException {
        pair(w, 0, "BLOCK");
        pair(w, 8, "0");
        pair(w, 2, block.getName()); pair(w, 70, "0");
        pair(w, 10, fmt(block.getInsertX())); pair(w, 20, fmt(block.getInsertY())); pair(w, 30, fmt(block.getInsertZ()));
        pair(w, 3, block.getName()); pair(w, 1, "");
        for (CADEntity e : block.entities()) writeEntityR12(w, e);
        pair(w, 0, "ENDBLK");
        pair(w, 8, "0");
    }

    private void writeEntityR12(LineWriter w, CADEntity entity) throws IOException {
        String type = entity.getType();
        Geometry geom = entity.geometry();
        boolean wrote = true;

        if ("TEXT".equals(type) && geom instanceof Point p)        writeTextR12(w, p, entity);
        else if ("MTEXT".equals(type) && geom instanceof Point p)  writeMTextR12(w, p, entity);
        else if ("ARC".equals(type) && geom instanceof Point p)    writeArcR12(w, p, entity);
        else if ("CIRCLE".equals(type) && geom instanceof Point p) writeCircleR12(w, p, entity);
        else if ("INSERT".equals(type) && geom instanceof Point p) writeInsertR12(w, p, entity);
        else if ("ELLIPSE".equals(type) && geom instanceof Point p) writeEllipseR12(w, p, entity);
        else if ("SOLID".equals(type) && (geom instanceof Polygon || geom instanceof LinearRing))
            writeSolidR12(w, geom, entity);
        else if ("3DFACE".equals(type) && (geom instanceof LinearRing || geom instanceof Polygon))
            writeThreeDFaceR12(w, geom, entity);
        else if ("SPLINE".equals(type) && geom instanceof LineString ls) writeSplineR12(w, ls, entity);
        else if ("HATCH".equals(type)) {
            // R12 不支持 HATCH，降级为外环 LWPOLYLINE（仅边界，无填充）
            if (geom instanceof Polygon poly)
                writeLwR12(w, poly.getExteriorRing().getCoordinates(), true, entity);
            else if (geom instanceof MultiPolygon mp)
                for (int i = 0; i < mp.getNumGeometries(); i++)
                    writeLwR12(w, ((Polygon) mp.getGeometryN(i)).getExteriorRing().getCoordinates(), true, entity);
            wrote = false; // POLYLINE 结构复杂，跳过 XDATA（R12 HATCH 降级不携带 XDATA）
        }
        else if (geom != null) writeGeomR12(w, geom, entity);
        else wrote = false;

        if (wrote) writeXData(w, entity);
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
        pair(w, 66, "1");
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

    private void writeArcR12(LineWriter w, Point center, CADEntity e) throws IOException {
        double radius = dblProp(e, "radius", 0.0);
        if (radius <= 0) return;
        double sa = dblProp(e, "startAngle", 0.0);
        double ea = dblProp(e, "endAngle", 360.0);
        // 零长度弧（起终角相同）跳过；startAngle=0/endAngle=360 属合法整圆弧，不跳过
        if (Math.abs(ea - sa) < 1e-9) return;
        pair(w, 0, "ARC");
        writeR12Common(w, e);
        pair(w, 10, fmt(center.getX())); pair(w, 20, fmt(center.getY()));
        pair(w, 30, fmtZ(center.getCoordinate().getZ()));
        pair(w, 40, fmt(radius));
        pair(w, 50, fmt(sa));
        pair(w, 51, fmt(ea));
    }

    private void writeCircleR12(LineWriter w, Point center, CADEntity e) throws IOException {
        double radius = dblProp(e, "radius", 0.0);
        if (radius <= 0) return;
        pair(w, 0, "CIRCLE");
        writeR12Common(w, e);
        pair(w, 10, fmt(center.getX())); pair(w, 20, fmt(center.getY()));
        pair(w, 30, fmtZ(center.getCoordinate().getZ()));
        pair(w, 40, fmt(radius));
    }

    private void writeInsertR12(LineWriter w, Point p, CADEntity e) throws IOException {
        String blockName = strProp(e, "blockName");
        if (blockName.isBlank()) return;
        pair(w, 0, "INSERT");
        writeR12Common(w, e);
        pair(w, 2, blockName);
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        double sx = dblProp(e, "scaleX", 1.0), sy = dblProp(e, "scaleY", 1.0), sz = dblProp(e, "scaleZ", 1.0);
        if (Math.abs(sx - 1.0) > 1e-9) pair(w, 41, fmt(sx));
        if (Math.abs(sy - 1.0) > 1e-9) pair(w, 42, fmt(sy));
        if (Math.abs(sz - 1.0) > 1e-9) pair(w, 43, fmt(sz));
        double rot = dblProp(e, "rotation", 0.0);
        if (Math.abs(rot) > 1e-9) pair(w, 50, fmt(rot));
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

    private void writeEllipseR12(LineWriter w, Point center, CADEntity e) throws IOException {
        double mx = dblProp(e, "majorAxisX", 1.0);
        double my = dblProp(e, "majorAxisY", 0.0);
        double ratio = dblProp(e, "axisRatio", 1.0);
        if (Math.sqrt(mx * mx + my * my) < 1e-12 || ratio <= 0) return;
        pair(w, 0, "ELLIPSE");
        writeR12Common(w, e);
        pair(w, 10, fmt(center.getX())); pair(w, 20, fmt(center.getY()));
        pair(w, 30, fmtZ(center.getCoordinate().getZ()));
        pair(w, 11, fmt(mx)); pair(w, 21, fmt(my)); pair(w, 31, fmt(0.0));
        pair(w, 40, fmt(ratio));
        pair(w, 41, fmt(dblProp(e, "startAngle", 0.0)));
        pair(w, 42, fmt(dblProp(e, "endAngle", 2 * Math.PI)));
    }

    private void writeSolidR12(LineWriter w, Geometry geom, CADEntity e) throws IOException {
        Coordinate[] ring = (geom instanceof Polygon p)
                ? p.getExteriorRing().getCoordinates()
                : ((LinearRing) geom).getCoordinates();
        int n = trimClosedEnd(ring, true);
        if (n < 3) return;
        // DXF SOLID 第三/四顶点是蝴蝶结顺序（v2 和 v3 互换）
        Coordinate v0 = ring[0], v1 = ring[1], v2 = ring[2];
        Coordinate v3 = n >= 4 ? ring[3] : ring[2]; // 三角形时 v3=v2
        pair(w, 0, "SOLID");
        writeR12Common(w, e);
        pair(w, 10, fmt(v0.x)); pair(w, 20, fmt(v0.y)); pair(w, 30, fmtZ(v0.getZ()));
        pair(w, 11, fmt(v1.x)); pair(w, 21, fmt(v1.y)); pair(w, 31, fmtZ(v1.getZ()));
        // 蝴蝶结：写出时 code 12=v3，code 13=v2（与 SolidHandler 读取时互换一致）
        pair(w, 12, fmt(v3.x)); pair(w, 22, fmt(v3.y)); pair(w, 32, fmtZ(v3.getZ()));
        pair(w, 13, fmt(v2.x)); pair(w, 23, fmt(v2.y)); pair(w, 33, fmtZ(v2.getZ()));
    }

    private void writeThreeDFaceR12(LineWriter w, Geometry geom, CADEntity e) throws IOException {
        Coordinate[] ring = (geom instanceof Polygon p)
                ? p.getExteriorRing().getCoordinates()
                : ((LinearRing) geom).getCoordinates();
        int n = trimClosedEnd(ring, true);
        if (n < 3) return;
        Coordinate v0 = ring[0], v1 = ring[1], v2 = ring[2];
        Coordinate v3 = n >= 4 ? ring[3] : ring[2];
        pair(w, 0, "3DFACE");
        writeR12Common(w, e);
        pair(w, 10, fmt(v0.x)); pair(w, 20, fmt(v0.y)); pair(w, 30, fmtZ(v0.getZ()));
        pair(w, 11, fmt(v1.x)); pair(w, 21, fmt(v1.y)); pair(w, 31, fmtZ(v1.getZ()));
        pair(w, 12, fmt(v2.x)); pair(w, 22, fmt(v2.y)); pair(w, 32, fmtZ(v2.getZ()));
        pair(w, 13, fmt(v3.x)); pair(w, 23, fmt(v3.y)); pair(w, 33, fmtZ(v3.getZ()));
        pair(w, 70, "0"); // 边可见性：全可见
    }

    private void writeSplineR12(LineWriter w, LineString ls, CADEntity e) throws IOException {
        @SuppressWarnings("unchecked")
        List<double[]> ctrlPts = (List<double[]>) e.getProperties().get("controlPoints");
        if (ctrlPts != null && ctrlPts.size() >= 2) {
            int degree = 3;
            double[] knots = generateClampedKnots(ctrlPts.size(), degree);
            pair(w, 0, "SPLINE");
            writeR12Common(w, e);
            writeSplineBody(w, degree, knots, ctrlPts, false);
        } else {
            // 无控制点 → 降级为 LWPOLYLINE
            Coordinate[] cs = ls.getCoordinates();
            if (cs.length >= 2) writeLwR12(w, cs, false, e);
        }
    }

    /** 写出 SPLINE 实体体（除 code 0 和公共头之外的字段），r2007=true 时插入 AcDbSpline 子类标记。 */
    private void writeSplineBody(LineWriter w, int degree, double[] knots,
                                  List<double[]> ctrlPts, boolean r2007) throws IOException {
        if (r2007) pair(w, 100, "AcDbSpline");
        pair(w, 70, "8");              // flags: planar
        pair(w, 71, String.valueOf(degree));
        pair(w, 72, String.valueOf(knots.length));
        pair(w, 73, String.valueOf(ctrlPts.size()));
        pair(w, 74, "0");              // no fit points
        pair(w, 42, fmt(1e-10));       // knot tolerance
        pair(w, 43, fmt(1e-10));       // control point tolerance
        for (double k : knots) pair(w, 40, fmt(k));
        for (double[] pt : ctrlPts) {
            pair(w, 10, fmt(pt[0])); pair(w, 20, fmt(pt[1]));
            pair(w, 30, pt.length > 2 ? fmt(pt[2]) : fmt(0.0));
        }
    }

    /** 生成 n 个控制点、degree 次的 clamped uniform 节点向量。 */
    private static double[] generateClampedKnots(int n, int degree) {
        int total = n + degree + 1;
        double[] knots = new double[total];
        // 前 degree+1 个为 0，后 degree+1 个为 1，中间均匀分布
        int internal = n - degree - 1;
        for (int i = degree + 1; i < degree + 1 + internal; i++) {
            knots[i] = (double)(i - degree) / (n - degree);
        }
        for (int i = total - degree - 1; i < total; i++) knots[i] = 1.0;
        return knots;
    }

    /** R12 实体公共字段：图层 + 颜色（XDATA 由各实体方法在末尾单独写出）。 */
    private void writeR12Common(LineWriter w, CADEntity e) throws IOException {
        pair(w, 8, layerOf(e));
        Object aci = e.getProperties().get("colorAci");
        if (aci instanceof Integer v) pair(w, 62, String.valueOf(v));
    }

    // =========================================================================
    // R2000+ 路径（含子类标记、owner handle、完整符号表）
    // =========================================================================

    private void writeR2000(LineWriter w, List<CADBlock> blocks,
                             List<CADEntity> entities, Map<String, LayerInfo> layerInfo) throws IOException {
        int[] lyH  = {0x10};
        int[] enH  = {0x100};

        Set<String> linetypes = collectLinetypes(layerInfo);
        double[] extent = computeExtent(entities, blocks);

        writeR2000Header(w, extent);
        pair(w, 0, "SECTION"); pair(w, 2, "CLASSES"); pair(w, 0, "ENDSEC");
        writeR2000Tables(w, layerInfo, linetypes, blocks, lyH);
        writeR2000Blocks(w, blocks);
        writeR2000Entities(w, entities, enH);
        writeR2000Objects(w);
        pair(w, 0, "EOF");
    }

    private void writeR2000Header(LineWriter w, double[] extent) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "HEADER");
        pair(w, 9, "$ACADVER");    pair(w, 1, config.getVersion().getVersionString());
        if (!config.getVersion().before(DXFVersion.R2007)) {
            pair(w, 9, "$ACADMAINTVER"); pair(w, 70, "50");
        }
        pair(w, 9, "$DWGCODEPAGE");
        pair(w, 3, "GBK".equalsIgnoreCase(config.getEncoding()) ? "ANSI_936" : "ANSI_1252");
        pair(w, 9, "$INSUNITS");   pair(w, 70, "6");
        pair(w, 9, "$LTSCALE");    pair(w, 40, fmt(1.0));
        pair(w, 9, "$EXTMIN");
        pair(w, 10, fmt(extent[0])); pair(w, 20, fmt(extent[1])); pair(w, 30, fmt(extent[2]));
        pair(w, 9, "$EXTMAX");
        pair(w, 10, fmt(extent[3])); pair(w, 20, fmt(extent[4])); pair(w, 30, fmt(extent[5]));
        pair(w, 9, "$HANDSEED");
        pair(w, 5, "2000");  // 0x2000，覆盖用户块句柄范围
        pair(w, 0, "ENDSEC");
    }

    private void writeR2000Tables(LineWriter w, Map<String, LayerInfo> layerInfo,
                                   Set<String> linetypes, List<CADBlock> blocks,
                                   int[] lyH) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "TABLES");

        // VPORT 表（含 *Active 记录）
        pair(w, 0, "TABLE"); pair(w, 2, "VPORT");
        pair(w, 5, "E0"); pair(w, 330, "0");
        pair(w, 100, "AcDbSymbolTable"); pair(w, 70, "1");
        pair(w, 0, "VPORT"); pair(w, 5, H_VP_ACTIVE);
        pair(w, 330, "E0");
        pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbViewportTableRecord");
        pair(w, 2, "*Active"); pair(w, 70, "0");
        pair(w, 10, fmt(0.0)); pair(w, 20, fmt(0.0));
        pair(w, 11, fmt(1.0)); pair(w, 21, fmt(1.0));
        pair(w, 12, fmt(0.0)); pair(w, 22, fmt(0.0));
        pair(w, 13, fmt(0.0)); pair(w, 23, fmt(0.0));
        pair(w, 14, fmt(0.5)); pair(w, 24, fmt(0.5));
        pair(w, 15, fmt(0.5)); pair(w, 25, fmt(0.5));
        pair(w, 16, fmt(0.0)); pair(w, 26, fmt(0.0)); pair(w, 36, fmt(1.0));
        pair(w, 17, fmt(0.0)); pair(w, 27, fmt(0.0)); pair(w, 37, fmt(0.0));
        pair(w, 40, fmt(1.0)); pair(w, 41, fmt(1.0)); pair(w, 42, fmt(50.0));
        pair(w, 43, fmt(0.0)); pair(w, 44, fmt(0.0));
        pair(w, 50, fmt(0.0)); pair(w, 51, fmt(0.0));
        pair(w, 71, "0"); pair(w, 72, "1000"); pair(w, 73, "1");
        pair(w, 74, "3"); pair(w, 75, "0"); pair(w, 76, "0");
        pair(w, 77, "0"); pair(w, 78, "0");
        pair(w, 0, "ENDTAB");

        // LTYPE 表（ByBlock + ByLayer + Continuous + 用户线型）
        int ltCount = 2 + linetypes.size(); // ByBlock + ByLayer + user linetypes(含Continuous)
        pair(w, 0, "TABLE"); pair(w, 2, "LTYPE");
        pair(w, 5, H_LT_TABLE); pair(w, 330, "0");
        pair(w, 100, "AcDbSymbolTable"); pair(w, 70, String.valueOf(ltCount));
        pair(w, 0, "LTYPE"); pair(w, 5, H_LT_BYBLOCK);
        pair(w, 330, H_LT_TABLE);
        pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbLinetypeTableRecord");
        pair(w, 2, "ByBlock"); pair(w, 70, "0");
        pair(w, 3, ""); pair(w, 72, "65"); pair(w, 73, "0"); pair(w, 40, fmt(0.0));
        pair(w, 0, "LTYPE"); pair(w, 5, H_LT_BYLAYER);
        pair(w, 330, H_LT_TABLE);
        pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbLinetypeTableRecord");
        pair(w, 2, "ByLayer"); pair(w, 70, "0");
        pair(w, 3, ""); pair(w, 72, "65"); pair(w, 73, "0"); pair(w, 40, fmt(0.0));
        // 用户线型（包含 Continuous 以及图层中引用的其他线型）
        int[] ltH = {0xA0};
        for (String lt : linetypes) {
            String h = "Continuous".equalsIgnoreCase(lt) ? H_LT_CONT : hex(ltH[0]++);
            pair(w, 0, "LTYPE"); pair(w, 5, h);
            pair(w, 330, H_LT_TABLE);
            pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbLinetypeTableRecord");
            pair(w, 2, lt); pair(w, 70, "0");
            pair(w, 3, "Continuous".equalsIgnoreCase(lt) ? "Solid line" : "");
            pair(w, 72, "65"); pair(w, 73, "0"); pair(w, 40, fmt(0.0));
        }
        pair(w, 0, "ENDTAB");

        // LAYER 表
        pair(w, 0, "TABLE"); pair(w, 2, "LAYER");
        pair(w, 5, H_LY_TABLE); pair(w, 330, "0");
        pair(w, 100, "AcDbSymbolTable");
        pair(w, 70, String.valueOf(layerInfo.size()));
        for (Map.Entry<String, LayerInfo> entry : layerInfo.entrySet()) {
            LayerInfo li = entry.getValue();
            pair(w, 0, "LAYER"); pair(w, 5, hex(lyH[0]++));
            pair(w, 330, H_LY_TABLE);
            pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbLayerTableRecord");
            pair(w, 2, entry.getKey()); pair(w, 70, "0");
            pair(w, 62, String.valueOf(li.color()));
            pair(w, 6, li.lineType());
            pair(w, 370, String.valueOf(li.lineWeight()));
        }
        pair(w, 0, "ENDTAB");

        // STYLE 表
        pair(w, 0, "TABLE"); pair(w, 2, "STYLE");
        pair(w, 5, H_ST_TABLE); pair(w, 330, "0");
        pair(w, 100, "AcDbSymbolTable"); pair(w, 70, "1");
        pair(w, 0, "STYLE"); pair(w, 5, H_ST_STD);
        pair(w, 330, H_ST_TABLE);
        pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbTextStyleTableRecord");
        pair(w, 2, "Standard"); pair(w, 70, "0");
        pair(w, 40, fmt(0.0)); pair(w, 41, fmt(1.0)); pair(w, 50, fmt(0.0));
        pair(w, 71, "0"); pair(w, 42, fmt(2.5)); pair(w, 3, "txt"); pair(w, 4, "");
        pair(w, 0, "ENDTAB");

        // VIEW 表（空）
        pair(w, 0, "TABLE"); pair(w, 2, "VIEW");
        pair(w, 5, H_VW_TABLE); pair(w, 330, "0");
        pair(w, 100, "AcDbSymbolTable"); pair(w, 70, "0");
        pair(w, 0, "ENDTAB");

        // UCS 表（空）
        pair(w, 0, "TABLE"); pair(w, 2, "UCS");
        pair(w, 5, H_UC_TABLE); pair(w, 330, "0");
        pair(w, 100, "AcDbSymbolTable"); pair(w, 70, "0");
        pair(w, 0, "ENDTAB");

        // APPID 表
        pair(w, 0, "TABLE"); pair(w, 2, "APPID");
        pair(w, 5, "E1"); pair(w, 330, "0");
        pair(w, 100, "AcDbSymbolTable"); pair(w, 70, "1");
        pair(w, 0, "APPID"); pair(w, 5, "E2"); pair(w, 330, "E1");
        pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbRegAppTableRecord");
        pair(w, 2, "ACAD"); pair(w, 70, "0");
        pair(w, 0, "ENDTAB");

        // DIMSTYLE 表（句柄用 code 105 而非 5）
        pair(w, 0, "TABLE"); pair(w, 2, "DIMSTYLE");
        pair(w, 5, H_DS_TABLE); pair(w, 330, "0");
        pair(w, 100, "AcDbSymbolTable"); pair(w, 70, "1");
        pair(w, 100, "AcDbDimStyleTable"); pair(w, 71, "0");
        pair(w, 0, "DIMSTYLE"); pair(w, 105, H_DS_STD);
        pair(w, 330, H_DS_TABLE);
        pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbDimStyleTableRecord");
        pair(w, 2, "Standard"); pair(w, 70, "0");
        pair(w, 340, H_ST_STD);
        pair(w, 0, "ENDTAB");

        // BLOCK_RECORD 表 ← 必须最后
        int totalBR = 2 + blocks.size(); // *Model_Space + *Paper_Space + user blocks
        pair(w, 0, "TABLE"); pair(w, 2, "BLOCK_RECORD");
        pair(w, 5, H_BR_TABLE); pair(w, 330, "0");
        pair(w, 100, "AcDbSymbolTable"); pair(w, 70, String.valueOf(totalBR));
        writeBlockRecord(w, H_MS_BR, "*Model_Space");
        writeBlockRecord(w, H_PS_BR, "*Paper_Space");
        int[] brH = {H_USER_BR_BASE};
        for (CADBlock b : blocks) {
            writeUserBlockRecord(w, hex(brH[0]++), b.getName());
        }
        pair(w, 0, "ENDTAB");

        pair(w, 0, "ENDSEC");
    }

    private void writeBlockRecord(LineWriter w, String h, String name) throws IOException {
        String layoutH = H_MS_BR.equals(h) ? H_LAYOUT_MS : H_LAYOUT_PS;
        pair(w, 0, "BLOCK_RECORD"); pair(w, 5, h);
        pair(w, 330, H_BR_TABLE);
        pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbBlockTableRecord");
        pair(w, 2, name);
        pair(w, 340, layoutH);
        pair(w, 70, "0"); pair(w, 280, "1"); pair(w, 281, "0");
    }

    private void writeUserBlockRecord(LineWriter w, String h, String name) throws IOException {
        pair(w, 0, "BLOCK_RECORD"); pair(w, 5, h);
        pair(w, 330, H_BR_TABLE);
        pair(w, 100, "AcDbSymbolTableRecord"); pair(w, 100, "AcDbBlockTableRecord");
        pair(w, 2, name);
        pair(w, 70, "0"); pair(w, 280, "1"); pair(w, 281, "0");
    }

    private void writeR2000Blocks(LineWriter w, List<CADBlock> blocks) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "BLOCKS");
        writeBlockDef(w, H_MS_BLOCK, H_MS_ENDBLK, H_MS_BR, "*Model_Space");
        writeBlockDef(w, H_PS_BLOCK, H_PS_ENDBLK, H_PS_BR, "*Paper_Space");

        // 用户块：handle 从 H_USER_BLK_BASE 起，每块 BLOCK+ENDBLK 占 2 个句柄
        int[] blkH  = {H_USER_BLK_BASE};
        int[] brH   = {H_USER_BR_BASE};
        int[] entH  = {H_USER_ENT_BASE};
        for (CADBlock b : blocks) {
            String hBR     = hex(brH[0]++);
            String hBlock  = hex(blkH[0]++);
            String hEndblk = hex(blkH[0]++);
            writeUserBlockDef(w, hBlock, hEndblk, hBR, b, entH);
        }

        pair(w, 0, "ENDSEC");
    }

    private void writeBlockDef(LineWriter w, String hBlock, String hEndblk,
                                String hBR, String name) throws IOException {
        boolean isPaper = name.contains("Paper_Space");
        pair(w, 0, "BLOCK"); pair(w, 5, hBlock);
        pair(w, 330, hBR);
        pair(w, 100, "AcDbEntity");
        if (isPaper) pair(w, 67, "1");
        pair(w, 8, "0");
        pair(w, 100, "AcDbBlockBegin");
        pair(w, 2, name); pair(w, 70, "0");
        pair(w, 10, fmt(0.0)); pair(w, 20, fmt(0.0)); pair(w, 30, fmt(0.0));
        pair(w, 3, name); pair(w, 1, "");

        pair(w, 0, "ENDBLK"); pair(w, 5, hEndblk);
        pair(w, 330, hBR);
        pair(w, 100, "AcDbEntity");
        if (isPaper) pair(w, 67, "1");
        pair(w, 8, "0");
        pair(w, 100, "AcDbBlockEnd");
    }

    private void writeUserBlockDef(LineWriter w, String hBlock, String hEndblk,
                                    String hBR, CADBlock block, int[] entH) throws IOException {
        pair(w, 0, "BLOCK"); pair(w, 5, hBlock);
        pair(w, 330, hBR);
        pair(w, 100, "AcDbEntity"); pair(w, 8, "0");
        pair(w, 100, "AcDbBlockBegin");
        pair(w, 2, block.getName()); pair(w, 70, "0");
        pair(w, 10, fmt(block.getInsertX())); pair(w, 20, fmt(block.getInsertY())); pair(w, 30, fmt(block.getInsertZ()));
        pair(w, 3, block.getName()); pair(w, 1, "");

        // 块内实体（owner 指向本块的 BLOCK_RECORD）
        for (CADEntity e : block.entities()) writeEntityR2000WithOwner(w, e, hBR, entH);

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
        writeEntityR2000WithOwner(w, entity, H_MS_BR, enH);
    }

    private void writeEntityR2000WithOwner(LineWriter w, CADEntity entity,
                                            String ownerBR, int[] enH) throws IOException {
        String type = entity.getType();
        Geometry geom = entity.geometry();

        if ("TEXT".equals(type) && geom instanceof Point p)        writeTextR2000(w, p, entity, ownerBR, enH);
        else if ("MTEXT".equals(type) && geom instanceof Point p)  writeMTextR2000(w, p, entity, ownerBR, enH);
        else if ("ARC".equals(type) && geom instanceof Point p)    writeArcR2000(w, p, entity, ownerBR, enH);
        else if ("CIRCLE".equals(type) && geom instanceof Point p) writeCircleR2000(w, p, entity, ownerBR, enH);
        else if ("INSERT".equals(type) && geom instanceof Point p) writeInsertR2000(w, p, entity, ownerBR, enH);
        else if ("ELLIPSE".equals(type) && geom instanceof Point p) writeEllipseR2000(w, p, entity, ownerBR, enH);
        else if ("SOLID".equals(type) && (geom instanceof Polygon || geom instanceof LinearRing))
            writeSolidR2000(w, geom, entity, ownerBR, enH);
        else if ("3DFACE".equals(type) && (geom instanceof LinearRing || geom instanceof Polygon))
            writeThreeDFaceR2000(w, geom, entity, ownerBR, enH);
        else if ("SPLINE".equals(type) && geom instanceof LineString ls) writeSplineR2000(w, ls, entity, ownerBR, enH);
        else if ("HATCH".equals(type)) {
            // HATCH 可能对应多个多边形，各自写出后 XDATA 不追加（HATCH 不携带地物编码）
            if (geom instanceof Polygon poly)
                writeHatchR2000(w, poly, entity, ownerBR, enH);
            else if (geom instanceof MultiPolygon mp)
                for (int i = 0; i < mp.getNumGeometries(); i++)
                    writeHatchR2000(w, (Polygon) mp.getGeometryN(i), entity, ownerBR, enH);
            return; // HATCH 不追加 XDATA
        }
        else if (geom != null) writeGeomR2000(w, geom, entity, ownerBR, enH);
        else return;

        // XDATA 必须在实体所有 group code 之后写出（DXF 规范要求）
        writeXData(w, entity);
    }

    private void writeGeomR2000(LineWriter w, Geometry geom, CADEntity e,
                                 String ownerBR, int[] enH) throws IOException {
        if (geom instanceof Point p) {
            writePointR2000(w, p, e, ownerBR, enH);
        } else if (geom instanceof LinearRing lr) {
            writeLwR2000(w, lr.getCoordinates(), true, e, ownerBR, enH);
        } else if (geom instanceof LineString ls) {
            Coordinate[] cs = ls.getCoordinates();
            if (cs.length == 2) writeLineR2000(w, ls, e, ownerBR, enH);
            else                writeLwR2000(w, cs, false, e, ownerBR, enH);
        } else if (geom instanceof Polygon poly) {
            writeLwR2000(w, poly.getExteriorRing().getCoordinates(), true, e, ownerBR, enH);
            for (int i = 0; i < poly.getNumInteriorRing(); i++)
                writeLwR2000(w, poly.getInteriorRingN(i).getCoordinates(), true, e, ownerBR, enH);
        } else if (geom instanceof GeometryCollection gc) {
            for (int i = 0; i < gc.getNumGeometries(); i++)
                writeGeomR2000(w, gc.getGeometryN(i), e, ownerBR, enH);
        }
    }

    private void writePointR2000(LineWriter w, Point p, CADEntity e,
                                  String ownerBR, int[] h) throws IOException {
        pair(w, 0, "POINT");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbPoint");
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
    }

    private void writeLineR2000(LineWriter w, LineString ls, CADEntity e,
                                 String ownerBR, int[] h) throws IOException {
        Coordinate s = ls.getCoordinateN(0), t = ls.getCoordinateN(1);
        pair(w, 0, "LINE");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbLine");
        pair(w, 10, fmt(s.x)); pair(w, 20, fmt(s.y)); pair(w, 30, fmtZ(s.getZ()));
        pair(w, 11, fmt(t.x)); pair(w, 21, fmt(t.y)); pair(w, 31, fmtZ(t.getZ()));
    }

    private void writeLwR2000(LineWriter w, Coordinate[] coords, boolean closed,
                               CADEntity e, String ownerBR, int[] h) throws IOException {
        int n = trimClosedEnd(coords, closed);
        if (n < 2) return;
        pair(w, 0, "LWPOLYLINE");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbPolyline");
        pair(w, 90, String.valueOf(n));
        pair(w, 70, closed ? "1" : "0");
        pair(w, 43, fmt(0.0));
        double z0 = uniformZ(coords, n);
        if (!Double.isNaN(z0) && Math.abs(z0) > 1e-12) pair(w, 38, fmt(z0));
        for (int i = 0; i < n; i++) {
            pair(w, 10, fmt(coords[i].x)); pair(w, 20, fmt(coords[i].y));
        }
    }

    private void writeArcR2000(LineWriter w, Point center, CADEntity e,
                                String ownerBR, int[] h) throws IOException {
        double radius = dblProp(e, "radius", 0.0);
        if (radius <= 0) return;
        pair(w, 0, "ARC");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbCircle");
        pair(w, 10, fmt(center.getX())); pair(w, 20, fmt(center.getY()));
        pair(w, 30, fmtZ(center.getCoordinate().getZ()));
        pair(w, 40, fmt(radius));
        pair(w, 100, "AcDbArc");
        pair(w, 50, fmt(dblProp(e, "startAngle", 0.0)));
        pair(w, 51, fmt(dblProp(e, "endAngle", 360.0)));
    }

    private void writeCircleR2000(LineWriter w, Point center, CADEntity e,
                                   String ownerBR, int[] h) throws IOException {
        double radius = dblProp(e, "radius", 0.0);
        if (radius <= 0) return;
        pair(w, 0, "CIRCLE");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbCircle");
        pair(w, 10, fmt(center.getX())); pair(w, 20, fmt(center.getY()));
        pair(w, 30, fmtZ(center.getCoordinate().getZ()));
        pair(w, 40, fmt(radius));
    }

    private void writeHatchR2000(LineWriter w, Polygon poly, CADEntity e,
                                  String ownerBR, int[] h) throws IOException {
        String pattern = strPropOrDefault(e, "hatchPattern", "SOLID");

        // 预过滤无效环（避免 code 91 路径总数与实际写出数量不匹配导致结构损坏）
        Coordinate[] outerCoords = poly.getExteriorRing().getCoordinates();
        if (trimClosedEnd(outerCoords, true) < 2) return;

        List<Coordinate[]> validHoles = new ArrayList<>();
        for (int i = 0; i < poly.getNumInteriorRing(); i++) {
            Coordinate[] hc = poly.getInteriorRingN(i).getCoordinates();
            if (trimClosedEnd(hc, true) >= 2) validHoles.add(hc);
        }
        int numPaths = 1 + validHoles.size();

        pair(w, 0, "HATCH");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbHatch");
        pair(w, 10, fmt(0.0)); pair(w, 20, fmt(0.0)); pair(w, 30, fmt(0.0));
        pair(w, 210, fmt(0.0)); pair(w, 220, fmt(0.0)); pair(w, 230, fmt(1.0));
        pair(w, 2, pattern);
        // solid fill flag：SOLID 图案为 1，其他图案为 0
        pair(w, 70, "SOLID".equalsIgnoreCase(pattern) ? "1" : "0");
        pair(w, 71, "0");   // not associative
        pair(w, 91, String.valueOf(numPaths));

        // 外环路径（path type = 1: External，edge 格式）
        writeHatchBoundaryPath(w, outerCoords, 1);
        // 内环（洞）路径（path type = 0: Default/inner，bit 0 未置，HatchHandler 识别为洞）
        for (Coordinate[] hc : validHoles)
            writeHatchBoundaryPath(w, hc, 0);

        Point interior = poly.getInteriorPoint();
        pair(w, 75, "1");   // hatch style: normal
        pair(w, 76, "1");   // pattern type: predefined
        pair(w, 98, "1");   // seed point count
        pair(w, 10, fmt(interior.getX())); pair(w, 20, fmt(interior.getY()));
    }

    /**
     * 写出 HATCH 边界路径（edge-line 格式，与 HatchHandler 解析格式一致）。
     *
     * <p>DXF 有两种边界路径格式：
     * <ul>
     *   <li>Polyline 格式（pathType bit 2=4 置位）：92/72(hasBulge)/73(isClosed)/93(n)/10/20</li>
     *   <li>Edge 格式（bit 2 不置位）：92/93(edges)/[72=1/10/20/11/21 per edge]</li>
     * </ul>
     * HatchHandler 解析的是 edge 格式，故写出必须使用 edge 格式。
     */
    private void writeHatchBoundaryPath(LineWriter w, Coordinate[] coords,
                                         int pathType) throws IOException {
        int n = trimClosedEnd(coords, true);
        if (n < 2) return;
        // edge 格式：n 个顶点 = n 条直线边段（首尾相接，闭合）
        pair(w, 92, String.valueOf(pathType));
        pair(w, 93, String.valueOf(n));   // 边段数 = 顶点数
        for (int i = 0; i < n; i++) {
            int next = (i + 1) % n;
            pair(w, 72, "1");   // edge type = line (直线边段)
            pair(w, 10, fmt(coords[i].x));    pair(w, 20, fmt(coords[i].y));
            pair(w, 11, fmt(coords[next].x)); pair(w, 21, fmt(coords[next].y));
        }
        pair(w, 97, "0");   // source objects count (non-associative)
    }

    private void writeInsertR2000(LineWriter w, Point p, CADEntity e,
                                   String ownerBR, int[] h) throws IOException {
        String blockName = strProp(e, "blockName");
        if (blockName.isBlank()) return;
        pair(w, 0, "INSERT");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbBlockReference");
        pair(w, 2, blockName);
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        double sx = dblProp(e, "scaleX", 1.0), sy = dblProp(e, "scaleY", 1.0), sz = dblProp(e, "scaleZ", 1.0);
        // 与 R12 路径保持一致：只写非默认值（1.0）
        if (Math.abs(sx - 1.0) > 1e-9) pair(w, 41, fmt(sx));
        if (Math.abs(sy - 1.0) > 1e-9) pair(w, 42, fmt(sy));
        if (Math.abs(sz - 1.0) > 1e-9) pair(w, 43, fmt(sz));
        double rot = dblProp(e, "rotation", 0.0);
        if (Math.abs(rot) > 1e-9) pair(w, 50, fmt(rot));
    }

    private void writeTextR2000(LineWriter w, Point p, CADEntity e,
                                 String ownerBR, int[] h) throws IOException {
        String text = strProp(e, "text");
        if (text.isBlank()) return;
        pair(w, 0, "TEXT");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbText");
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        pair(w, 40, fmt(dblProp(e, "height", 2.5)));
        pair(w, 1, text);
        double rot = dblProp(e, "rotation", 0.0);
        if (Math.abs(rot) > 1e-9) pair(w, 50, fmt(rot));
        pair(w, 7, strPropOrDefault(e, "style", "Standard"));
        pair(w, 100, "AcDbText");
    }

    private void writeMTextR2000(LineWriter w, Point p, CADEntity e,
                                  String ownerBR, int[] h) throws IOException {
        String text = strProp(e, "text");
        if (text.isBlank()) return;
        pair(w, 0, "MTEXT");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbMText");
        pair(w, 10, fmt(p.getX())); pair(w, 20, fmt(p.getY())); pair(w, 30, fmtZ(p.getCoordinate().getZ()));
        pair(w, 40, fmt(dblProp(e, "height", 2.5)));
        pair(w, 41, fmt(0.0));
        pair(w, 71, "1"); pair(w, 72, "5");
        pair(w, 1, text);
    }

    private void writeEllipseR2000(LineWriter w, Point center, CADEntity e,
                                    String ownerBR, int[] h) throws IOException {
        double mx = dblProp(e, "majorAxisX", 1.0);
        double my = dblProp(e, "majorAxisY", 0.0);
        double ratio = dblProp(e, "axisRatio", 1.0);
        if (Math.sqrt(mx * mx + my * my) < 1e-12 || ratio <= 0) return;
        pair(w, 0, "ELLIPSE");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbEllipse");
        pair(w, 10, fmt(center.getX())); pair(w, 20, fmt(center.getY()));
        pair(w, 30, fmtZ(center.getCoordinate().getZ()));
        pair(w, 11, fmt(mx)); pair(w, 21, fmt(my)); pair(w, 31, fmt(0.0));
        pair(w, 40, fmt(ratio));
        pair(w, 41, fmt(dblProp(e, "startAngle", 0.0)));
        pair(w, 42, fmt(dblProp(e, "endAngle", 2 * Math.PI)));
    }

    private void writeSolidR2000(LineWriter w, Geometry geom, CADEntity e,
                                  String ownerBR, int[] h) throws IOException {
        Coordinate[] ring = (geom instanceof Polygon p)
                ? p.getExteriorRing().getCoordinates()
                : ((LinearRing) geom).getCoordinates();
        int n = trimClosedEnd(ring, true);
        if (n < 3) return;
        Coordinate v0 = ring[0], v1 = ring[1], v2 = ring[2];
        Coordinate v3 = n >= 4 ? ring[3] : ring[2];
        pair(w, 0, "SOLID");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbTrace");
        pair(w, 10, fmt(v0.x)); pair(w, 20, fmt(v0.y)); pair(w, 30, fmtZ(v0.getZ()));
        pair(w, 11, fmt(v1.x)); pair(w, 21, fmt(v1.y)); pair(w, 31, fmtZ(v1.getZ()));
        pair(w, 12, fmt(v3.x)); pair(w, 22, fmt(v3.y)); pair(w, 32, fmtZ(v3.getZ()));
        pair(w, 13, fmt(v2.x)); pair(w, 23, fmt(v2.y)); pair(w, 33, fmtZ(v2.getZ()));
    }

    private void writeThreeDFaceR2000(LineWriter w, Geometry geom, CADEntity e,
                                       String ownerBR, int[] h) throws IOException {
        Coordinate[] ring = (geom instanceof Polygon p)
                ? p.getExteriorRing().getCoordinates()
                : ((LinearRing) geom).getCoordinates();
        int n = trimClosedEnd(ring, true);
        if (n < 3) return;
        Coordinate v0 = ring[0], v1 = ring[1], v2 = ring[2];
        Coordinate v3 = n >= 4 ? ring[3] : ring[2];
        pair(w, 0, "3DFACE");
        writeR2000Common(w, e, ownerBR, h);
        pair(w, 100, "AcDbFace");
        pair(w, 10, fmt(v0.x)); pair(w, 20, fmt(v0.y)); pair(w, 30, fmtZ(v0.getZ()));
        pair(w, 11, fmt(v1.x)); pair(w, 21, fmt(v1.y)); pair(w, 31, fmtZ(v1.getZ()));
        pair(w, 12, fmt(v2.x)); pair(w, 22, fmt(v2.y)); pair(w, 32, fmtZ(v2.getZ()));
        pair(w, 13, fmt(v3.x)); pair(w, 23, fmt(v3.y)); pair(w, 33, fmtZ(v3.getZ()));
        pair(w, 70, "0");
    }

    private void writeSplineR2000(LineWriter w, LineString ls, CADEntity e,
                                   String ownerBR, int[] h) throws IOException {
        @SuppressWarnings("unchecked")
        List<double[]> ctrlPts = (List<double[]>) e.getProperties().get("controlPoints");
        if (ctrlPts != null && ctrlPts.size() >= 2) {
            int degree = 3;
            double[] knots = generateClampedKnots(ctrlPts.size(), degree);
            pair(w, 0, "SPLINE");
            writeR2000Common(w, e, ownerBR, h);
            writeSplineBody(w, degree, knots, ctrlPts, true);
        } else {
            Coordinate[] cs = ls.getCoordinates();
            if (cs.length >= 2) writeLwR2000(w, cs, false, e, ownerBR, h);
        }
    }

    /** R2007+ 实体公共头：handle + owner + AcDbEntity + 图层 + 颜色（XDATA 由各实体方法在末尾单独写出）。 */
    private void writeR2000Common(LineWriter w, CADEntity e, String ownerBR, int[] h) throws IOException {
        pair(w, 5, hex(h[0]++));
        pair(w, 330, ownerBR);
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

    /** 向实体末尾追加 XDATA（若 xdata property 存在）。 */
    private void writeXData(LineWriter w, CADEntity e) throws IOException {
        Object raw = e.getProperties().get("xdata");
        if (!(raw instanceof Map<?, ?> xdataMap)) return;
        for (Map.Entry<?, ?> entry : xdataMap.entrySet()) {
            if (!(entry.getKey() instanceof String appName)) continue;
            pair(w, 1001, appName);
            Object entries = entry.getValue();
            if (entries instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof XDataEntry xe) {
                        pair(w, xe.code(), xe.value());
                    }
                }
            }
        }
    }

    private void writeR2000Objects(LineWriter w) throws IOException {
        pair(w, 0, "SECTION"); pair(w, 2, "OBJECTS");

        pair(w, 0, "DICTIONARY"); pair(w, 5, H_ROOT_DICT);
        pair(w, 330, "0");
        pair(w, 100, "AcDbDictionary"); pair(w, 281, "1");
        pair(w, 3, "ACAD_LAYOUT"); pair(w, 350, H_LAYOUT_DICT);

        pair(w, 0, "DICTIONARY"); pair(w, 5, H_LAYOUT_DICT);
        pair(w, 102, "{ACAD_REACTORS"); pair(w, 330, H_ROOT_DICT); pair(w, 102, "}");
        pair(w, 330, H_ROOT_DICT);
        pair(w, 100, "AcDbDictionary"); pair(w, 281, "1");
        pair(w, 3, "Layout1"); pair(w, 350, H_LAYOUT_PS);
        pair(w, 3, "Model");   pair(w, 350, H_LAYOUT_MS);

        writeLayout(w, H_LAYOUT_PS, "Layout1", 1, H_PS_BR);
        writeLayout(w, H_LAYOUT_MS, "Model",   0, H_MS_BR);

        pair(w, 0, "ENDSEC");
    }

    private void writeLayout(LineWriter w, String h, String name,
                              int tabOrder, String blockRecH) throws IOException {
        pair(w, 0, "LAYOUT"); pair(w, 5, h);
        pair(w, 102, "{ACAD_REACTORS"); pair(w, 330, H_LAYOUT_DICT); pair(w, 102, "}");
        pair(w, 330, H_LAYOUT_DICT);
        pair(w, 100, "AcDbPlotSettings");
        pair(w, 1, ""); pair(w, 2, "none_device"); pair(w, 4, ""); pair(w, 6, "");
        pair(w, 40, fmt(0.0)); pair(w, 41, fmt(0.0)); pair(w, 42, fmt(0.0)); pair(w, 43, fmt(0.0));
        pair(w, 44, fmt(0.0)); pair(w, 45, fmt(0.0)); pair(w, 46, fmt(0.0)); pair(w, 47, fmt(0.0));
        pair(w, 48, fmt(0.0)); pair(w, 49, fmt(0.0));
        pair(w, 140, fmt(0.0)); pair(w, 141, fmt(0.0)); pair(w, 142, fmt(1.0)); pair(w, 143, fmt(1.0));
        pair(w, 70, tabOrder == 0 ? "1712" : "688");
        pair(w, 72, "0"); pair(w, 73, "0");
        pair(w, 74, tabOrder == 0 ? "0" : "5");
        pair(w, 7, ""); pair(w, 75, tabOrder == 0 ? "0" : "16");
        pair(w, 147, fmt(1.0)); pair(w, 76, "0"); pair(w, 77, "2"); pair(w, 78, "300");
        pair(w, 148, fmt(0.0)); pair(w, 149, fmt(0.0));

        pair(w, 100, "AcDbLayout");
        pair(w, 1, name); pair(w, 70, "1"); pair(w, 71, String.valueOf(tabOrder));
        pair(w, 10, fmt(0.0)); pair(w, 20, fmt(0.0));
        pair(w, 11, fmt(12.0)); pair(w, 21, fmt(9.0));
        pair(w, 12, fmt(0.0)); pair(w, 22, fmt(0.0)); pair(w, 32, fmt(0.0));
        pair(w, 14, fmt(1e20)); pair(w, 24, fmt(1e20)); pair(w, 34, fmt(1e20));
        pair(w, 15, fmt(-1e20)); pair(w, 25, fmt(-1e20)); pair(w, 35, fmt(-1e20));
        pair(w, 146, fmt(0.0));
        pair(w, 13, fmt(0.0)); pair(w, 23, fmt(0.0)); pair(w, 33, fmt(0.0));
        pair(w, 16, fmt(1.0)); pair(w, 26, fmt(0.0)); pair(w, 36, fmt(0.0));
        pair(w, 17, fmt(0.0)); pair(w, 27, fmt(1.0)); pair(w, 37, fmt(0.0));
        pair(w, 76, "0");
        pair(w, 330, blockRecH);
        if (tabOrder == 0) pair(w, 331, H_VP_ACTIVE);
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    /** 收集图层属性（颜色、线型、线宽），包含主实体和块内实体。 */
    private Map<String, LayerInfo> collectLayerInfo(List<CADEntity> entities, List<CADBlock> blocks) {
        Map<String, LayerInfo> map = new LinkedHashMap<>();
        map.put("0", new LayerInfo(7, "Continuous", -3));
        for (CADEntity e : entities) collectEntityLayer(map, e);
        for (CADBlock b : blocks)
            for (CADEntity e : b.entities()) collectEntityLayer(map, e);
        return map;
    }

    private void collectEntityLayer(Map<String, LayerInfo> map, CADEntity e) {
        String layer = layerOf(e);
        if (map.containsKey(layer)) return;
        Object aci = e.getProperties().get("colorAci");
        Object lt  = e.getProperties().get("lineType");
        Object lw  = e.getProperties().get("lineWeight");
        int color       = aci instanceof Integer v ? v : 7;
        String lineType = lt instanceof String s ? s : "Continuous";
        int lineWeight  = lw instanceof Integer v ? v : -3;
        map.put(layer, new LayerInfo(color, lineType, lineWeight));
    }

    /** 收集所有需要写入 LTYPE 表的线型名（有序，Continuous 总在第一位）。 */
    private Set<String> collectLinetypes(Map<String, LayerInfo> layerInfo) {
        Set<String> set = new LinkedHashSet<>();
        set.add("Continuous"); // 始终首位
        for (LayerInfo li : layerInfo.values()) {
            if (!"Continuous".equalsIgnoreCase(li.lineType())) set.add(li.lineType());
        }
        return set;
    }

    /** 计算所有实体（主实体 + 块内实体）的包围盒 [minX, minY, minZ, maxX, maxY, maxZ]。 */
    private double[] computeExtent(List<CADEntity> entities, List<CADBlock> blocks) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        boolean hasGeom = false;

        List<CADEntity> all = new ArrayList<>(entities);
        for (CADBlock b : blocks) all.addAll(b.entities());

        for (CADEntity e : all) {
            if (e.geometry() == null) continue;
            hasGeom = true;
            for (Coordinate c : e.geometry().getCoordinates()) {
                if (c.x < minX) minX = c.x; if (c.x > maxX) maxX = c.x;
                if (c.y < minY) minY = c.y; if (c.y > maxY) maxY = c.y;
                if (!Double.isNaN(c.getZ())) {
                    if (c.getZ() < minZ) minZ = c.getZ();
                    if (c.getZ() > maxZ) maxZ = c.getZ();
                }
            }
        }

        if (!hasGeom || Double.isInfinite(minX)) return new double[]{0.0, 0.0, 0.0, 1.0, 1.0, 0.0};
        if (Double.isInfinite(minZ)) { minZ = 0.0; maxZ = 0.0; }
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private boolean isR12() { return config.getVersion() == DXFVersion.R12; }

    private static String layerOf(CADEntity e) {
        return e.getLayer() != null && !e.getLayer().isEmpty() ? e.getLayer() : "0";
    }

    private static int trimClosedEnd(Coordinate[] coords, boolean closed) {
        if (closed && coords.length > 1 && coords[0].equals2D(coords[coords.length - 1]))
            return coords.length - 1;
        return coords.length;
    }

    private static double uniformZ(Coordinate[] coords, int n) {
        double z0 = coords[0].getZ();
        if (Double.isNaN(z0)) return Double.NaN;
        for (int i = 1; i < n; i++) {
            if (Double.isNaN(coords[i].getZ()) || Math.abs(coords[i].getZ() - z0) > 1e-9) return Double.NaN;
        }
        return z0;
    }

    private String fmt(double v) {
        if (Math.abs(v) >= 1e15 || (v != 0.0 && Math.abs(v) < 1e-9))
            return String.format("%.15E", v);
        return String.format(fmtPattern, v); // 使用预计算的格式串
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

    // =========================================================================
    // 内部类型
    // =========================================================================

    /** 图层属性（颜色、线型、线宽）。 */
    private record LayerInfo(int color, String lineType, int lineWeight) {}

    private static class LineWriter {
        private final Writer w;
        LineWriter(Writer w) { this.w = w; }
        void writeLine(String s) throws IOException { w.write(s); w.write("\r\n"); }
        void flush() throws IOException { w.flush(); }
    }
}
