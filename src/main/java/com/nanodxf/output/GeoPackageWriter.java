package com.nanodxf.output;

import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKBWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * 将 {@link CADEntity} 列表序列化为 OGC GeoPackage 格式（SQLite 单文件）。
 *
 * <p>GeoPackage 是现代 GIS 数据交换格式，克服了 Shapefile 的诸多限制：
 * 无字段名长度限制、支持 UTF-8 中文属性、单文件包含多图层、属性值超长不截断。
 *
 * <p>输出规则：
 * <ul>
 *   <li>按几何类型分为三张特征表：{@code points}（点）、{@code linestrings}（线）、
 *       {@code polygons}（面）</li>
 *   <li>每张表均包含属性列：{@code layer TEXT}、{@code etype TEXT}、
 *       {@code text TEXT}、{@code feat_code TEXT}、{@code feat_type TEXT}、
 *       {@code color INTEGER}、{@code elevation REAL}</li>
 *   <li>几何列名为 {@code geom}，采用 GeoPackage WKB 编码（GP 魔术字节 + ISO WKB）</li>
 *   <li>几何为 null 或空的实体：不写入特征表，跳过</li>
 *   <li>空几何表不创建（不产生空的 {@code gpkg_contents} 记录）</li>
 * </ul>
 *
 * <p>依赖 {@code org.xerial:sqlite-jdbc}（纯 Java SQLite 驱动，无本地库）。
 *
 * <pre>{@code
 * GeoPackageWriteConfig cfg = GeoPackageWriteConfig.builder()
 *     .crs("EPSG:4490")
 *     .build();
 * new GeoPackageWriter(cfg).write(entities, Paths.get("output.gpkg"));
 * }</pre>
 */
public class GeoPackageWriter {

    // GeoPackage 规范要求的 SQLite pragma 值
    private static final int GPKG_APPLICATION_ID = 0x47504B47; // 'GPKG'
    private static final int GPKG_USER_VERSION    = 10200;     // v1.2.0

    // 内置 EPSG CRS 描述（用于 gpkg_spatial_ref_sys.definition）
    private static final Map<Integer, String[]> KNOWN_SRS;
    static {
        Map<Integer, String[]> m = new LinkedHashMap<>();
        // [organization, org_coord_sys_id, definition (WKT)]
        m.put(4326, new String[]{"EPSG", "4326",
            "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]]," +
            "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]"});
        m.put(4490, new String[]{"EPSG", "4490",
            "GEOGCS[\"China Geodetic Coordinate System 2000\"," +
            "DATUM[\"China_2000\",SPHEROID[\"CGCS2000\",6378137,298.257222101]]," +
            "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]"});
        m.put(3857, new String[]{"EPSG", "3857",
            "PROJCS[\"WGS 84 / Pseudo-Mercator\",GEOGCS[\"WGS 84\"," +
            "DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]]," +
            "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]," +
            "PROJECTION[\"Mercator_1SP\"],PARAMETER[\"central_meridian\",0]," +
            "PARAMETER[\"scale_factor\",1],PARAMETER[\"false_easting\",0]," +
            "PARAMETER[\"false_northing\",0],UNIT[\"metre\",1]]"});
        m.put(32649, new String[]{"EPSG", "32649",
            "PROJCS[\"WGS 84 / UTM zone 49N\",GEOGCS[\"WGS 84\"," +
            "DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]]," +
            "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]," +
            "PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"latitude_of_origin\",0]," +
            "PARAMETER[\"central_meridian\",111],PARAMETER[\"scale_factor\",0.9996]," +
            "PARAMETER[\"false_easting\",500000],PARAMETER[\"false_northing\",0]," +
            "UNIT[\"metre\",1]]"});
        KNOWN_SRS = Collections.unmodifiableMap(m);
    }

    private final GeoPackageWriteConfig config;

    public GeoPackageWriter() { this(GeoPackageWriteConfig.defaults()); }

    public GeoPackageWriter(GeoPackageWriteConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /**
     * 将实体列表写出到 GeoPackage 文件。
     * 若文件已存在则覆盖。
     *
     * @param entities 实体列表
     * @param path     输出路径（推荐以 .gpkg 结尾）
     */
    public void write(List<CADEntity> entities, Path path) throws IOException {
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(path, "path");
        Files.deleteIfExists(path);

        int srsId = parseSrsId(config.getCrs());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            // WAL pragma must run outside a transaction (autocommit=true)
            setPragmas(conn);
            conn.setAutoCommit(false);

            createGpkgTables(conn);
            insertSrs(conn, srsId);

            // 分组：按几何主类型 (点 / 线 / 面)
            List<CADEntity> points      = new ArrayList<>();
            List<CADEntity> linestrings = new ArrayList<>();
            List<CADEntity> polygons    = new ArrayList<>();

            for (CADEntity e : entities) {
                Geometry g = e.geometry();
                if (g == null || g.isEmpty()) continue;
                switch (dominantType(g)) {
                    case "POINT"      -> points.add(e);
                    case "LINESTRING" -> linestrings.add(e);
                    case "POLYGON"    -> polygons.add(e);
                }
            }

            if (!points.isEmpty())      writeFeatureTable(conn, "points",      "POINT",      points,      srsId);
            if (!linestrings.isEmpty()) writeFeatureTable(conn, "linestrings",  "GEOMETRY",   linestrings, srsId);
            if (!polygons.isEmpty())    writeFeatureTable(conn, "polygons",     "GEOMETRY",   polygons,    srsId);

            conn.commit();
        } catch (SQLException ex) {
            throw new IOException("GeoPackage write failed: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // SQLite 初始化
    // -------------------------------------------------------------------------

    private static void setPragmas(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA application_id=" + GPKG_APPLICATION_ID);
            st.execute("PRAGMA user_version=" + GPKG_USER_VERSION);
        }
    }

    private static void createGpkgTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS gpkg_spatial_ref_sys (" +
                "  srs_name TEXT NOT NULL," +
                "  srs_id INTEGER NOT NULL PRIMARY KEY," +
                "  organization TEXT NOT NULL," +
                "  organization_coordsys_id INTEGER NOT NULL," +
                "  definition TEXT NOT NULL," +
                "  description TEXT)");

            st.execute(
                "CREATE TABLE IF NOT EXISTS gpkg_contents (" +
                "  table_name TEXT NOT NULL PRIMARY KEY," +
                "  data_type TEXT NOT NULL," +
                "  identifier TEXT," +
                "  description TEXT DEFAULT ''," +
                "  last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S.000Z','now'))," +
                "  min_x REAL, min_y REAL, max_x REAL, max_y REAL," +
                "  srs_id INTEGER REFERENCES gpkg_spatial_ref_sys(srs_id))");

            st.execute(
                "CREATE TABLE IF NOT EXISTS gpkg_geometry_columns (" +
                "  table_name TEXT NOT NULL," +
                "  column_name TEXT NOT NULL," +
                "  geometry_type_name TEXT NOT NULL," +
                "  srs_id INTEGER NOT NULL REFERENCES gpkg_spatial_ref_sys(srs_id)," +
                "  z TINYINT NOT NULL," +
                "  m TINYINT NOT NULL," +
                "  CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name))");

            // 必须的 WGS 84 & undefined 记录（GeoPackage spec §1.1.2.1.2）
            st.execute(
                "INSERT OR IGNORE INTO gpkg_spatial_ref_sys VALUES (" +
                "'Undefined Cartesian SRS',-1,'NONE',-1,'undefined','undefined cartesian coordinate reference system')");
            st.execute(
                "INSERT OR IGNORE INTO gpkg_spatial_ref_sys VALUES (" +
                "'Undefined Geographic SRS',0,'NONE',0,'undefined','undefined geographic coordinate reference system')");
            st.execute(
                "INSERT OR IGNORE INTO gpkg_spatial_ref_sys VALUES (" +
                "'WGS 84 geodetic',4326,'EPSG',4326," +
                "'GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]]," +
                "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]','WGS 84')");
        }
    }

    private static void insertSrs(Connection conn, int srsId) throws SQLException {
        if (srsId == 0 || srsId == -1 || srsId == 4326) return; // 已由 createGpkgTables 插入
        String[] meta = KNOWN_SRS.get(srsId);
        String name = meta != null ? "EPSG:" + srsId : "User-defined SRS";
        String org  = meta != null ? meta[0] : "NONE";
        String def  = meta != null ? meta[2] : "undefined";
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO gpkg_spatial_ref_sys VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, name);
            ps.setInt(2, srsId);
            ps.setString(3, org);
            ps.setInt(4, srsId);
            ps.setString(5, def);
            ps.setString(6, "");
            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // 特征表写出
    // -------------------------------------------------------------------------

    private void writeFeatureTable(Connection conn, String tableName, String geomTypeName,
                                    List<CADEntity> entities, int srsId) throws SQLException {
        // 创建特征表
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS \"" + tableName + "\" (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  geom BLOB," +
                "  layer TEXT," +
                "  etype TEXT," +
                "  text TEXT," +
                "  feat_code TEXT," +
                "  feat_type TEXT," +
                "  color INTEGER," +
                "  elevation REAL)");
        }

        // 注册到 gpkg_geometry_columns
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO gpkg_geometry_columns VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, tableName);
            ps.setString(2, "geom");
            ps.setString(3, geomTypeName);
            ps.setInt(4, srsId);
            ps.setInt(5, 0); // z: 0=prohibited, 1=mandatory, 2=optional
            ps.setInt(6, 0); // m
            ps.executeUpdate();
        }

        // 计算 bbox 并注册到 gpkg_contents
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        // 插入特征行
        String sql = "INSERT INTO \"" + tableName + "\" (geom,layer,etype,text,feat_code,feat_type,color,elevation) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            WKBWriter wkbWriter = new WKBWriter(2, org.locationtech.jts.io.ByteOrderValues.LITTLE_ENDIAN);
            for (CADEntity e : entities) {
                Geometry g = e.geometry();
                if (g == null || g.isEmpty()) continue;
                Envelope env = g.getEnvelopeInternal();
                if (env.getMinX() < minX) minX = env.getMinX();
                if (env.getMinY() < minY) minY = env.getMinY();
                if (env.getMaxX() > maxX) maxX = env.getMaxX();
                if (env.getMaxY() > maxY) maxY = env.getMaxY();

                ps.setBytes(1, toGpkgBlob(g, srsId, wkbWriter));
                ps.setString(2, e.getLayer());
                ps.setString(3, e.getType());
                ps.setString(4, strProp(e, "text"));
                ps.setString(5, strProp(e, "featureCode"));
                ps.setString(6, strProp(e, "featureType"));
                Object aci = e.getProperties().get("colorAci");
                if (aci instanceof Integer v) ps.setInt(7, v); else ps.setNull(7, Types.INTEGER);
                Object elev = e.getProperties().get("elevation");
                if (elev instanceof Number n) ps.setDouble(8, n.doubleValue()); else ps.setNull(8, Types.REAL);
                ps.executeUpdate();
            }
        }

        // gpkg_contents
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO gpkg_contents (table_name,data_type,identifier,description,min_x,min_y,max_x,max_y,srs_id) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, tableName);
            ps.setString(2, "features");
            ps.setString(3, tableName);
            ps.setString(4, "");
            ps.setDouble(5, minX == Double.MAX_VALUE ? 0 : minX);
            ps.setDouble(6, minY == Double.MAX_VALUE ? 0 : minY);
            ps.setDouble(7, maxX == -Double.MAX_VALUE ? 0 : maxX);
            ps.setDouble(8, maxY == -Double.MAX_VALUE ? 0 : maxY);
            ps.setInt(9, srsId);
            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // GeoPackage WKB 编码
    // -------------------------------------------------------------------------

    /**
     * 生成 GeoPackage 几何 BLOB：2 字节魔术 + 1 字节版本 + 1 字节 flags + 4 字节 SRS ID + ISO WKB。
     * flags = 0x01（小端 WKB，无包络 envelope）。
     */
    private static byte[] toGpkgBlob(Geometry geom, int srsId, WKBWriter writer) {  // writer is little-endian WKBWriter
        byte[] wkb = writer.write(geom);
        ByteBuffer buf = ByteBuffer.allocate(8 + wkb.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x47);  // 'G'
        buf.put((byte) 0x50);  // 'P'
        buf.put((byte) 0x00);  // version 0
        buf.put((byte) 0x01);  // flags: little-endian, no envelope, not empty
        buf.putInt(srsId);
        buf.put(wkb);
        return buf.array();
    }

    // -------------------------------------------------------------------------
    // 工具
    // -------------------------------------------------------------------------

    private static String dominantType(Geometry g) {
        if (g instanceof Point || g instanceof MultiPoint)                    return "POINT";
        if (g instanceof LineString || g instanceof MultiLineString)          return "LINESTRING";
        if (g instanceof Polygon || g instanceof MultiPolygon)                return "POLYGON";
        if (g instanceof GeometryCollection gc && gc.getNumGeometries() > 0)
            return dominantType(gc.getGeometryN(0));
        return "LINESTRING"; // default
    }

    /** 从 CRS 字符串（如 "EPSG:4326"）解析 SRID，无法解析返回 0。 */
    private static int parseSrsId(String crs) {
        if (crs == null || crs.isBlank()) return 0;
        String upper = crs.trim().toUpperCase();
        if (upper.startsWith("EPSG:")) {
            try { return Integer.parseInt(upper.substring(5).trim()); }
            catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static String strProp(CADEntity e, String key) {
        Object v = e.getProperties().get(key);
        return v instanceof String s ? s : "";
    }

}
