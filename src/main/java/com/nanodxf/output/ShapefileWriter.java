package com.nanodxf.output;

import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/**
 * 将 {@link CADEntity} 列表序列化为 Shapefile 格式（纯 Java 实现，无额外依赖）。
 *
 * <p>输出文件组：
 * <ul>
 *   <li>{@code .shp} — 几何数据（点 / 折线 / 多边形）</li>
 *   <li>{@code .shx} — 几何索引</li>
 *   <li>{@code .dbf} — 属性数据（DBF III 格式）</li>
 *   <li>{@code .prj} — 坐标系说明（仅当 config.crs != null 时生成）</li>
 * </ul>
 *
 * <p>几何类型映射（按实体几何主体类型决定 Shapefile 类型，混合集合按主体类型处理）：
 * <ul>
 *   <li>{@link Point} → Shape Type 1（POINT）</li>
 *   <li>{@link LineString} / {@link MultiLineString} → Shape Type 3（POLYLINE）</li>
 *   <li>{@link Polygon} / {@link MultiPolygon} → Shape Type 5（POLYGON）</li>
 *   <li>几何为 null 或类型不匹配 → Null Shape（不丢弃记录，DBF 行保留）</li>
 * </ul>
 *
 * <p>DBF 属性字段：LAYER(C64)、ETYPE(C16)、TEXT(C254)、FEAT_CODE(C32)、
 * FEAT_TYPE(C64)、COLOR(N4)、ELEVATION(N10.4)
 *
 * <pre>{@code
 * ShapefileWriteConfig cfg = ShapefileWriteConfig.builder()
 *     .crs("EPSG:4545")
 *     .encoding("GBK")
 *     .build();
 * new ShapefileWriter(cfg).write(entities, Paths.get("output.shp"));
 * }</pre>
 */
public class ShapefileWriter {

    // Shape types
    private static final int SHP_NULL    = 0;
    private static final int SHP_POINT   = 1;
    private static final int SHP_POLYLINE = 3;
    private static final int SHP_POLYGON  = 5;

    // DBF field definitions: [name, type, length, decimal]
    private static final Object[][] DBF_FIELDS = {
        {"LAYER",     'C', 64,  0},
        {"ETYPE",     'C', 16,  0},
        {"TEXT",      'C', 254, 0},
        {"FEAT_CODE", 'C', 32,  0},
        {"FEAT_TYPE", 'C', 64,  0},
        {"COLOR",     'N', 4,   0},
        {"ELEVATION", 'N', 10,  4},
    };

    private final ShapefileWriteConfig config;

    public ShapefileWriter() { this(ShapefileWriteConfig.defaults()); }
    public ShapefileWriter(ShapefileWriteConfig config) { this.config = config; }

    /**
     * 写出实体列表到 Shapefile 文件组。
     * 输出 {@code path} 同目录下：{@code name.shp}、{@code name.shx}、{@code name.dbf}、{@code name.prj}。
     *
     * @param entities 实体列表（几何为 null 的实体以 Null Shape 写出，DBF 行保留）
     * @param path     输出路径（扩展名应为 {@code .shp}，也可无扩展名）
     */
    public void write(List<CADEntity> entities, Path path) throws IOException {
        Objects.requireNonNull(entities, "entities must not be null");
        Objects.requireNonNull(path,     "path must not be null");

        Path base = stripExtension(path);
        int shapeType = detectDominantShapeType(entities);

        // 收集每个记录的 SHP 内容（bytes）
        List<byte[]> shpRecords = new ArrayList<>(entities.size());
        for (CADEntity e : entities) {
            shpRecords.add(buildShpRecord(e.geometry(), shapeType));
        }

        writeSHP(base.resolveSibling(base.getFileName() + ".shp"), shapeType, shpRecords);
        writeSHX(base.resolveSibling(base.getFileName() + ".shx"), shapeType, shpRecords);
        writeDBF(base.resolveSibling(base.getFileName() + ".dbf"), entities);
        if (config.getCrs() != null) {
            writePRJ(base.resolveSibling(base.getFileName() + ".prj"), config.getCrs());
        }
    }

    // -------------------------------------------------------------------------
    // Shape type detection
    // -------------------------------------------------------------------------

    private int detectDominantShapeType(List<CADEntity> entities) {
        int points = 0, lines = 0, polys = 0;
        for (CADEntity e : entities) {
            Geometry g = e.geometry();
            if (g == null) continue;
            if (g instanceof Point || g instanceof MultiPoint) points++;
            else if (g instanceof LineString || g instanceof MultiLineString ||
                     g instanceof LinearRing) lines++;
            else if (g instanceof Polygon || g instanceof MultiPolygon) polys++;
            else if (g instanceof GeometryCollection gc) {
                // 展开集合，按主体类型计数
                for (int i = 0; i < gc.getNumGeometries(); i++) {
                    Geometry sub = gc.getGeometryN(i);
                    if (sub instanceof Point) points++;
                    else if (sub instanceof LineString || sub instanceof LinearRing) lines++;
                    else if (sub instanceof Polygon) polys++;
                }
            }
        }
        if (polys >= lines && polys >= points) return SHP_POLYGON;
        if (lines >= points) return SHP_POLYLINE;
        return points > 0 ? SHP_POINT : SHP_NULL;
    }

    // -------------------------------------------------------------------------
    // SHP record builder
    // -------------------------------------------------------------------------

    private byte[] buildShpRecord(Geometry geom, int shapeType) {
        if (geom == null) return encodeNull();
        return switch (shapeType) {
            case SHP_POINT   -> encodePoint(geom);
            case SHP_POLYLINE -> encodePolyline(geom);
            case SHP_POLYGON  -> encodePolygon(geom);
            default           -> encodeNull();
        };
    }

    private byte[] encodeNull() {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(SHP_NULL);
        return b.array();
    }

    private byte[] encodePoint(Geometry geom) {
        Point p = null;
        if (geom instanceof Point pt) p = pt;
        else if (geom instanceof GeometryCollection gc && gc.getNumGeometries() > 0
                 && gc.getGeometryN(0) instanceof Point pt) p = pt;
        if (p == null) return encodeNull();

        ByteBuffer b = ByteBuffer.allocate(4 + 16).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(SHP_POINT);
        b.putDouble(p.getX());
        b.putDouble(p.getY());
        return b.array();
    }

    private byte[] encodePolyline(Geometry geom) {
        List<Coordinate[]> parts = new ArrayList<>();
        collectLineParts(geom, parts);
        if (parts.isEmpty()) return encodeNull();
        return encodeMultiPart(SHP_POLYLINE, parts);
    }

    private void collectLineParts(Geometry geom, List<Coordinate[]> parts) {
        if (geom instanceof LineString ls) {
            if (ls.getNumPoints() >= 2) parts.add(ls.getCoordinates());
        } else if (geom instanceof LinearRing lr) {
            parts.add(lr.getCoordinates());
        } else if (geom instanceof MultiLineString mls) {
            for (int i = 0; i < mls.getNumGeometries(); i++)
                collectLineParts(mls.getGeometryN(i), parts);
        } else if (geom instanceof GeometryCollection gc) {
            for (int i = 0; i < gc.getNumGeometries(); i++)
                collectLineParts(gc.getGeometryN(i), parts);
        }
    }

    private byte[] encodePolygon(Geometry geom) {
        List<Coordinate[]> parts = new ArrayList<>();
        collectPolygonParts(geom, parts);
        if (parts.isEmpty()) return encodeNull();
        return encodeMultiPart(SHP_POLYGON, parts);
    }

    private void collectPolygonParts(Geometry geom, List<Coordinate[]> parts) {
        if (geom instanceof Polygon poly) {
            // Shapefile: exterior CCW, holes CW
            parts.add(ensureWinding(poly.getExteriorRing().getCoordinates(), true));
            for (int i = 0; i < poly.getNumInteriorRing(); i++)
                parts.add(ensureWinding(poly.getInteriorRingN(i).getCoordinates(), false));
        } else if (geom instanceof MultiPolygon mp) {
            for (int i = 0; i < mp.getNumGeometries(); i++)
                collectPolygonParts(mp.getGeometryN(i), parts);
        } else if (geom instanceof GeometryCollection gc) {
            for (int i = 0; i < gc.getNumGeometries(); i++)
                collectPolygonParts(gc.getGeometryN(i), parts);
        }
    }

    /**
     * 确保坐标序列为指定环绕方向（ccw=true 为逆时针，ccw=false 为顺时针）。
     * Shapefile 规范：外环 CCW，内环（洞）CW。
     */
    private Coordinate[] ensureWinding(Coordinate[] coords, boolean ccw) {
        boolean isCcw = isCounterClockwise(coords);
        if (isCcw == ccw) return coords;
        // 反转（不含重复的首尾点）
        Coordinate[] rev = Arrays.copyOf(coords, coords.length);
        for (int i = 0; i < rev.length / 2; i++) {
            Coordinate tmp = rev[i];
            rev[i] = rev[rev.length - 1 - i];
            rev[rev.length - 1 - i] = tmp;
        }
        return rev;
    }

    /** 简单有符号面积法判断环绕方向（CCW → signed area > 0）。 */
    private boolean isCounterClockwise(Coordinate[] coords) {
        double area = 0;
        int n = coords.length;
        for (int i = 0; i < n - 1; i++) {
            area += (coords[i].x * coords[i + 1].y) - (coords[i + 1].x * coords[i].y);
        }
        return area > 0;
    }

    private byte[] encodeMultiPart(int shapeType, List<Coordinate[]> parts) {
        int numParts  = parts.size();
        int numPoints = parts.stream().mapToInt(c -> c.length).sum();
        // content = 4 + 32 + 4 + 4 + 4*numParts + 16*numPoints bytes
        int contentBytes = 4 + 32 + 4 + 4 + 4 * numParts + 16 * numPoints;
        ByteBuffer b = ByteBuffer.allocate(contentBytes).order(ByteOrder.LITTLE_ENDIAN);

        b.putInt(shapeType);

        // Bounding box
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Coordinate[] cs : parts) {
            for (Coordinate c : cs) {
                if (c.x < minX) minX = c.x; if (c.x > maxX) maxX = c.x;
                if (c.y < minY) minY = c.y; if (c.y > maxY) maxY = c.y;
            }
        }
        b.putDouble(minX); b.putDouble(minY); b.putDouble(maxX); b.putDouble(maxY);

        b.putInt(numParts);
        b.putInt(numPoints);

        // Parts index
        int offset = 0;
        for (Coordinate[] cs : parts) { b.putInt(offset); offset += cs.length; }

        // Points
        for (Coordinate[] cs : parts) {
            for (Coordinate c : cs) { b.putDouble(c.x); b.putDouble(c.y); }
        }

        return b.array();
    }

    // -------------------------------------------------------------------------
    // SHP file writer
    // -------------------------------------------------------------------------

    private void writeSHP(Path path, int shapeType, List<byte[]> records) throws IOException {
        // Compute file length: 50 (header in 16-bit words) + sum of (4+content/2) per record
        int fileLenWords = 50;
        for (byte[] rec : records) fileLenWords += 4 + rec.length / 2;

        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
            out.write(shpHeader(shapeType, fileLenWords, records));
            for (int i = 0; i < records.size(); i++) {
                byte[] rec = records.get(i);
                // Record header: big-endian record number (1-based) + content length (16-bit words)
                ByteBuffer rh = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
                rh.putInt(i + 1);
                rh.putInt(rec.length / 2);
                out.write(rh.array());
                out.write(rec);
            }
        }
    }

    private byte[] shpHeader(int shapeType, int fileLenWords, List<byte[]> records) {
        ByteBuffer b = ByteBuffer.allocate(100);
        // Big-endian header fields
        b.order(ByteOrder.BIG_ENDIAN);
        b.putInt(9994);         // file code
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0); // unused
        b.putInt(fileLenWords); // file length in 16-bit words

        // Little-endian header fields
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(1000);          // version
        b.putInt(shapeType);     // shape type

        // Bounding box
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (byte[] rec : records) {
            if (rec.length < 4) continue;
            ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
            int st = rb.getInt();
            if (st == SHP_POINT && rec.length >= 20) {
                double x = rb.getDouble(), y = rb.getDouble();
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
            } else if ((st == SHP_POLYLINE || st == SHP_POLYGON) && rec.length >= 36) {
                double bx1 = rb.getDouble(), by1 = rb.getDouble();
                double bx2 = rb.getDouble(), by2 = rb.getDouble();
                if (bx1 < minX) minX = bx1; if (bx2 > maxX) maxX = bx2;
                if (by1 < minY) minY = by1; if (by2 > maxY) maxY = by2;
            }
        }
        if (Double.isInfinite(minX)) { minX = minY = 0; maxX = maxY = 0; }

        b.putDouble(minX); b.putDouble(minY); b.putDouble(maxX); b.putDouble(maxY);
        b.putDouble(0); b.putDouble(0); // Z range
        b.putDouble(0); b.putDouble(0); // M range
        return b.array();
    }

    // -------------------------------------------------------------------------
    // SHX file writer
    // -------------------------------------------------------------------------

    private void writeSHX(Path path, int shapeType, List<byte[]> records) throws IOException {
        int fileLenWords = 50 + 4 * records.size();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
            out.write(shpHeader(shapeType, fileLenWords, records));
            int offsetWords = 50; // file header = 50 words
            ByteBuffer idx = ByteBuffer.allocate(8 * records.size()).order(ByteOrder.BIG_ENDIAN);
            for (byte[] rec : records) {
                idx.putInt(offsetWords);
                int contentWords = rec.length / 2;
                idx.putInt(contentWords);
                offsetWords += 4 + contentWords; // 4 = record header (8 bytes / 2)
            }
            out.write(idx.array());
        }
    }

    // -------------------------------------------------------------------------
    // DBF file writer
    // -------------------------------------------------------------------------

    private void writeDBF(Path path, List<CADEntity> entities) throws IOException {
        Charset cs = Charset.forName(config.getEncoding());
        int numFields  = DBF_FIELDS.length;
        int recordSize = 1; // deletion flag
        for (Object[] f : DBF_FIELDS) recordSize += (int) f[2];

        int headerSize = 32 + 32 * numFields + 1;
        LocalDate today = LocalDate.now();

        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
            // Header
            ByteBuffer h = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            h.put((byte) 0x03);                      // version
            h.put((byte)(today.getYear() - 1900));   // year since 1900
            h.put((byte) today.getMonthValue());
            h.put((byte) today.getDayOfMonth());
            h.putInt(entities.size());               // number of records
            h.putShort((short) headerSize);          // header size
            h.putShort((short) recordSize);          // record size
            // remaining 20 bytes = 0
            out.write(h.array());

            // Field descriptors
            for (Object[] fd : DBF_FIELDS) {
                ByteBuffer f = ByteBuffer.allocate(32);
                byte[] nameBytes = ((String) fd[0]).getBytes("ASCII");
                f.put(nameBytes, 0, Math.min(nameBytes.length, 11));
                while (f.position() < 11) f.put((byte) 0);
                f.put((byte)((char) fd[1])); // type
                f.putInt(0);                  // reserved
                f.put((byte)(int) fd[2]);     // field length
                f.put((byte)(int) fd[3]);     // decimal count
                while (f.position() < 32) f.put((byte) 0);
                out.write(f.array());
            }
            out.write(0x0D); // header terminator

            // Records
            for (CADEntity e : entities) {
                out.write(0x20); // not deleted
                Map<String, Object> props = e.getProperties();
                writeDbfField(out, str(e.getLayer()),                     64, cs);
                writeDbfField(out, str(e.getType()),                      16, cs);
                writeDbfField(out, str(props.get("text")),               254, cs);
                writeDbfField(out, str(props.get("featureCode")),         32, cs);
                writeDbfField(out, str(props.get("featureType")),         64, cs);
                writeDbfNumeric(out, props.get("colorAci"),               4,  0);
                writeDbfNumeric(out, props.get("elevation"),              10,  4);
            }
            out.write(0x1A); // EOF marker
        }
    }

    private void writeDbfField(OutputStream out, String value, int length, Charset cs) throws IOException {
        byte[] bytes = (value != null ? value : "").getBytes(cs);
        int write = Math.min(bytes.length, length);
        out.write(bytes, 0, write);
        for (int i = write; i < length; i++) out.write(0x20); // right-pad with spaces
    }

    private void writeDbfNumeric(OutputStream out, Object value, int length, int decimals)
            throws IOException {
        String formatted;
        if (value == null) {
            formatted = " ".repeat(length);
        } else {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                formatted = " ".repeat(length);
            } else {
                String fmt = decimals > 0 ? "%." + decimals + "f" : "%.0f";
                formatted = String.format(fmt, d);
                if (formatted.length() > length) formatted = formatted.substring(0, length);
                if (formatted.length() < length)
                    formatted = " ".repeat(length - formatted.length()) + formatted; // right-align
            }
        }
        out.write(formatted.getBytes("ASCII"));
    }

    // -------------------------------------------------------------------------
    // PRJ file writer
    // -------------------------------------------------------------------------

    private void writePRJ(Path path, String crs) throws IOException {
        // Write a minimal PRJ: if it looks like EPSG:XXXX, write a brief WKT hint
        // For a full implementation, a CRS registry would be needed; here we write
        // the EPSG code as a comment so downstream tools can identify the CRS.
        String content = "# CRS: " + crs + "\n";
        Files.writeString(path, content);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String str(Object o) {
        return o instanceof String s ? s : (o != null ? o.toString() : "");
    }

    private static Path stripExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        Path parent = path.getParent();
        return parent != null ? parent.resolve(base) : Path.of(base);
    }
}
