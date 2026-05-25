package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.output.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.5.0 — 解析补全、空间查询 API、写出补全、GeoPackage 输出。
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>方向 D：MLINE / WIPEOUT / IMAGE / TOLERANCE 解析 handler</li>
 *   <li>方向 B：EntityIndex（STRtree bbox 查询、byLayer、byType）</li>
 *   <li>方向 C：DIMENSION / LEADER / MULTILEADER 写出，MTEXT width/attachment 增强</li>
 *   <li>方向 A：GeoPackageWriter 多图层写出，gpkg 结构验证</li>
 * </ul>
 */
class V150Test extends NanoDxfTestBase {

    // =========================================================================
    // 方向 D：解析补全
    // =========================================================================

    @Test
    void mlineHandler_shouldProduceLineStringWithStyle() throws Exception {
        String dxf = entities(
                "  0\nMLINE\n  5\nAA\n  8\n道路\n" +
                        "  2\nSTANDARD\n 40\n2.0\n 70\n0\n 71\n0\n 72\n2\n 73\n2\n" +
                        " 10\n0.0\n 20\n0.0\n 30\n0.0\n" +  // vertex 1
                        " 11\n1.0\n 21\n0.0\n 31\n0.0\n" +  // direction 1
                        " 12\n0.0\n 22\n1.0\n 32\n0.0\n" +  // miter 1
                        " 74\n0\n 75\n0\n" +
                        " 10\n100.0\n 20\n50.0\n 30\n0.0\n" + // vertex 2
                        " 11\n1.0\n 21\n0.0\n 31\n0.0\n" +
                        " 12\n0.0\n 22\n1.0\n 32\n0.0\n" +
                        " 74\n0\n 75\n0\n"
        );
        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("MLINE");
        assertThat(e.geometry()).isInstanceOf(LineString.class);
        LineString ls = (LineString) e.geometry();
        assertThat(ls.getNumPoints()).isEqualTo(2);
        assertThat(ls.getStartPoint().getX()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ls.getEndPoint().getX()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(e.getProperties().get(EntityProperty.MLINE_STYLE)).isEqualTo("STANDARD");
        assertThat(e.getProperties().get(EntityProperty.MLINE_SCALE)).isEqualTo(2.0);
    }

    @Test
    void wipeoutHandler_shouldProduceBoundaryPolygon() throws Exception {
        // WIPEOUT: 插入点(0,0)，U向量(1,0)每像素，V向量(0,1)每像素，100×50像素
        String dxf = entities(
                "  0\nWIPEOUT\n  5\nBB\n  8\n遮罩\n" +
                        " 10\n0.0\n 20\n0.0\n 30\n0.0\n" +   // insertion
                        " 11\n1.0\n 21\n0.0\n 31\n0.0\n" +   // U-vector
                        " 12\n0.0\n 22\n1.0\n 32\n0.0\n" +   // V-vector
                        " 13\n100.0\n 23\n50.0\n" +            // size in pixels
                        "340\nDEF1\n"   // code 340: 引用 IMAGEDEF handle
        );
        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("WIPEOUT");
        assertThat(e.geometry()).isInstanceOf(Polygon.class);
        Envelope env = e.geometry().getEnvelopeInternal();
        assertThat(env.getMaxX()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(env.getMaxY()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(e.getProperties().get(EntityProperty.WIPEOUT)).isEqualTo(Boolean.TRUE);
        assertThat(e.getProperties().get(EntityProperty.IMAGE_DEF_HANDLE)).isEqualTo("DEF1");
    }

    @Test
    void imageHandler_shouldProduceBoundingPolygon() throws Exception {
        String dxf = entities(
                "  0\nIMAGE\n  5\nCC\n  8\n影像\n" +
                        " 10\n10.0\n 20\n20.0\n 30\n0.0\n" +
                        " 11\n0.5\n 21\n0.0\n 31\n0.0\n" +
                        " 12\n0.0\n 22\n0.5\n 32\n0.0\n" +
                        " 13\n200.0\n 23\n100.0\n" +
                        "340\nIMGDEF1\n"
        );
        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("IMAGE");
        assertThat(e.geometry()).isInstanceOf(Polygon.class);
        // 宽 = 200 * 0.5 = 100, 高 = 100 * 0.5 = 50, 起始 (10,20)
        Envelope env = e.geometry().getEnvelopeInternal();
        assertThat(env.getMinX()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(env.getMaxX()).isCloseTo(110.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(env.getMaxY()).isCloseTo(70.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(e.getProperties().get(EntityProperty.IMAGE_DEF_HANDLE)).isEqualTo("IMGDEF1");
    }

    @Test
    void toleranceHandler_shouldProducePointWithText() throws Exception {
        String dxf = entities(
                "  0\nTOLERANCE\n  5\nDD\n  8\n公差\n" +
                        "  1\n{\\Fgdt;n}%%v0.05\n" +
                        "  3\nStandard\n" +
                        " 10\n50.0\n 20\n80.0\n 30\n0.0\n"
        );
        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("TOLERANCE");
        assertThat(e.geometry()).isInstanceOf(Point.class);
        assertThat(((Point) e.geometry()).getX()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(e.getProperties().get(EntityProperty.TEXT)).isEqualTo("{\\Fgdt;n}%%v0.05");
    }

    // =========================================================================
    // 方向 B：EntityIndex 空间查询 API
    // =========================================================================

    @Test
    void entityIndex_bboxQuery_shouldReturnIntersecting() {
        List<CADEntity> entities = List.of(
                CADEntity.builder("POINT").layer("A").geometry(GF.createPoint(new Coordinate(10, 10))).build(),
                CADEntity.builder("POINT").layer("A").geometry(GF.createPoint(new Coordinate(50, 50))).build(),
                CADEntity.builder("POINT").layer("B").geometry(GF.createPoint(new Coordinate(200, 200))).build()
        );
        EntityIndex idx = new EntityIndex(entities);

        List<CADEntity> found = idx.query(new Envelope(0, 60, 0, 60));
        assertThat(found).hasSize(2);
        assertThat(found).noneMatch(e -> e.geometry().getEnvelopeInternal().getMinX() > 100);
    }

    @Test
    void entityIndex_byLayer_shouldFilterCorrectly() {
        List<CADEntity> entities = List.of(
                CADEntity.builder("LINE").layer("道路").geometry(
                        GF.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(1, 1)})).build(),
                CADEntity.builder("LINE").layer("水系").geometry(
                        GF.createLineString(new Coordinate[]{new Coordinate(2, 2), new Coordinate(3, 3)})).build(),
                CADEntity.builder("LINE").layer("道路").geometry(
                        GF.createLineString(new Coordinate[]{new Coordinate(4, 4), new Coordinate(5, 5)})).build()
        );
        EntityIndex idx = new EntityIndex(entities);
        assertThat(idx.byLayer("道路")).hasSize(2);
        assertThat(idx.byLayer("水系")).hasSize(1);
        assertThat(idx.byLayer("植被")).isEmpty();
    }

    @Test
    void entityIndex_byType_shouldFilterCorrectly() {
        List<CADEntity> entities = List.of(
                CADEntity.builder("LINE").layer("L").geometry(
                        GF.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(1, 0)})).build(),
                CADEntity.builder("CIRCLE").layer("L").geometry(GF.createPoint(new Coordinate(5, 5))).build(),
                CADEntity.builder("LINE").layer("L").geometry(
                        GF.createLineString(new Coordinate[]{new Coordinate(2, 0), new Coordinate(3, 0)})).build()
        );
        EntityIndex idx = new EntityIndex(entities);
        assertThat(idx.byType("line")).hasSize(2);   // 大小写不敏感
        assertThat(idx.byType("CIRCLE")).hasSize(1);
    }

    @Test
    void entityIndex_query_withLayerAndTypeFilter() {
        List<CADEntity> entities = List.of(
                CADEntity.builder("CIRCLE").layer("构筑物").geometry(GF.createPoint(new Coordinate(10, 10))).build(),
                CADEntity.builder("POINT").layer("构筑物").geometry(GF.createPoint(new Coordinate(15, 15))).build(),
                CADEntity.builder("CIRCLE").layer("植被").geometry(GF.createPoint(new Coordinate(12, 12))).build()
        );
        EntityIndex idx = new EntityIndex(entities);
        List<CADEntity> res = idx.query(new Envelope(0, 20, 0, 20), "构筑物", "CIRCLE");
        assertThat(res).hasSize(1);
        assertThat(res.get(0).getLayer()).isEqualTo("构筑物");
    }

    @Test
    void parseResult_index_shouldBeLazyAndConsistent() throws Exception {
        String dxf = entities(
                "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n10\n 31\n0\n"
        );
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        EntityIndex idx1 = result.index();
        EntityIndex idx2 = result.index();
        assertThat(idx1).isSameAs(idx2);  // 懒建后返回同一实例
        assertThat(idx1.size()).isEqualTo(1);
    }

    // =========================================================================
    // 方向 C：写出补全
    // =========================================================================

    @Test
    void dxfWriter_dimension_r2007_shouldProduceDimensionEntity() throws Exception {
        Point textPt = GF.createPoint(new Coordinate(50.0, 110.0));
        CADEntity dim = CADEntity.builder(CADEntity.Types.DIMENSION)
                .layer("标注")
                .geometry(textPt)
                .property(EntityProperty.DIMENSION_TYPE, 1)       // aligned
                .property(EntityProperty.DIMENSION_VALUE, 100.0)
                .property(EntityProperty.TEXT, "<>")
                .property(EntityProperty.DIM_POINT1, new double[]{0.0, 100.0})
                .property(EntityProperty.DIM_POINT2, new double[]{100.0, 100.0})
                .build();

        DXFWriteConfig cfg = DXFWriteConfig.builder().version(com.nanodxf.model.DXFVersion.R2007).build();
        StringWriter sw = new StringWriter();
        new DXFWriter(cfg).write(List.of(dim), sw);
        String out = sw.toString();

        assertThat(out).contains("DIMENSION");
        assertThat(out).contains("AcDbDimension");
        assertThat(out).contains("AcDbAlignedDimension");
        assertThat(out).contains("<>");
    }

    @Test
    void dxfWriter_dimension_r12_shouldProduceDimensionEntity() throws Exception {
        Point textPt = GF.createPoint(new Coordinate(50.0, 110.0));
        CADEntity dim = CADEntity.builder(CADEntity.Types.DIMENSION)
                .layer("标注")
                .geometry(textPt)
                .property(EntityProperty.DIMENSION_TYPE, 1)
                .property(EntityProperty.DIMENSION_VALUE, 100.0)
                .property(EntityProperty.TEXT, "<>")
                .build();

        DXFWriteConfig r12 = DXFWriteConfig.builder().version(com.nanodxf.model.DXFVersion.R12).build();
        StringWriter sw = new StringWriter();
        new DXFWriter(r12).write(List.of(dim), sw);
        String out = sw.toString();

        assertThat(out).contains("DIMENSION");
        assertThat(out).contains("*D0");
        assertThat(out).doesNotContain("AcDbDimension");
    }

    @Test
    void dxfWriter_leader_r12_shouldProduceLeaderEntity() throws Exception {
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(10, 20), new Coordinate(30, 40), new Coordinate(50, 40)
        });
        CADEntity leader = CADEntity.builder(CADEntity.Types.LEADER)
                .layer("引线")
                .geometry(ls)
                .property(EntityProperty.LEADER_ARROW_TYPE, 1)
                .build();

        StringWriter sw = new StringWriter();
        new DXFWriter().write(List.of(leader), sw);
        String out = sw.toString();

        assertThat(out).contains("LEADER");
        assertThat(out).contains("Standard");
        assertThat(out).contains("  3");  // vertex count header for LEADER
    }

    @Test
    void dxfWriter_leader_r2007_shouldProduceLeaderEntity() throws Exception {
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(50, 50)
        });
        CADEntity leader = CADEntity.builder(CADEntity.Types.LEADER)
                .layer("引线")
                .geometry(ls)
                .build();

        DXFWriteConfig cfg = DXFWriteConfig.builder().version(com.nanodxf.model.DXFVersion.R2007).build();
        StringWriter sw = new StringWriter();
        new DXFWriter(cfg).write(List.of(leader), sw);
        String out = sw.toString();

        assertThat(out).contains("LEADER");
        assertThat(out).contains("AcDbLeader");
    }

    @Test
    void dxfWriter_multileader_r2007_degradesToLeader() throws Exception {
        // MULTILEADER 在 R2007 路径下降级为 LEADER 实体
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(20, 10)
        });
        CADEntity ml = CADEntity.builder(CADEntity.Types.MULTILEADER)
                .layer("引线")
                .geometry(ls)
                .property(EntityProperty.TEXT, "注记A")
                .build();

        DXFWriteConfig cfg = DXFWriteConfig.builder().version(com.nanodxf.model.DXFVersion.R2007).build();
        StringWriter sw = new StringWriter();
        new DXFWriter(cfg).write(List.of(ml), sw);
        String out = sw.toString();

        assertThat(out).contains("LEADER");
    }

    @Test
    void dxfWriter_mtext_withWidth_shouldWriteCode41() throws Exception {
        Point pt = GF.createPoint(new Coordinate(0, 0));
        CADEntity mtext = CADEntity.builder(CADEntity.Types.MTEXT)
                .layer("注记")
                .geometry(pt)
                .property(EntityProperty.TEXT, "测试文字")
                .property(EntityProperty.MTEXT_WIDTH, 50.0)
                .property(EntityProperty.MTEXT_ATTACHMENT, 4)  // 左中
                .build();

        DXFWriteConfig cfg = DXFWriteConfig.builder().version(com.nanodxf.model.DXFVersion.R2007).build();
        StringWriter sw = new StringWriter();
        new DXFWriter(cfg).write(List.of(mtext), sw);
        String out = sw.toString();

        assertThat(out).contains("MTEXT");
        // code 41 = 50.0000（宽度）
        assertThat(out).contains("50.0000");
        // code 71 = 4（附着点：左中）
        String[] lines = out.split("\r\n");
        boolean found71 = false;
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].trim().equals("71") && lines[i + 1].trim().equals("4")) {
                found71 = true;
                break;
            }
        }
        assertThat(found71).as("code 71 = 4 (attachment=左中)").isTrue();
    }

    // =========================================================================
    // 方向 A：GeoPackage 输出
    // =========================================================================

    @Test
    void geoPackageWriter_point_shouldWriteGpkgFile() throws Exception {
        Path tmp = Files.createTempFile(Paths.get("target"), "test_", ".gpkg");
        try {
            List<CADEntity> entities = List.of(
                    CADEntity.builder("POINT").layer("控制点")
                            .geometry(GF.createPoint(new Coordinate(116.3, 39.9)))
                            .property("colorAci", 3)
                            .build()
            );
            GeoPackageWriteConfig cfg = GeoPackageWriteConfig.builder().crs("EPSG:4326").build();
            new GeoPackageWriter(cfg).write(entities, tmp);

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tmp)) {
                // 验证 gpkg_contents 中有 points 表
                try (ResultSet rs = conn.createStatement()
                        .executeQuery("SELECT table_name FROM gpkg_contents WHERE table_name='points'")) {
                    assertThat(rs.next()).isTrue();
                }
                // 验证 points 表记录数
                try (ResultSet rs = conn.createStatement()
                        .executeQuery("SELECT COUNT(*) FROM points")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                // 验证 layer 属性
                try (ResultSet rs = conn.createStatement()
                        .executeQuery("SELECT layer FROM points")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("控制点");
                }
            }
        } finally {
            //Files.deleteIfExists(tmp);
        }
    }

    @Test
    void geoPackageWriter_multiGeomTypes_shouldWriteSeparateTables() throws Exception {
        Path tmp = Files.createTempFile(Paths.get("target"), "test_", ".gpkg");
        try {
            List<CADEntity> entities = List.of(
                    CADEntity.builder("POINT").layer("P")
                            .geometry(GF.createPoint(new Coordinate(1, 1))).build(),
                    CADEntity.builder("LINE").layer("L")
                            .geometry(GF.createLineString(
                                    new Coordinate[]{new Coordinate(0, 0), new Coordinate(1, 1)})).build(),
                    CADEntity.builder("LWPOLYLINE").layer("PG")
                            .geometry(GF.createPolygon(new Coordinate[]{
                                    new Coordinate(0, 0), new Coordinate(1, 0),
                                    new Coordinate(1, 1), new Coordinate(0, 0)})).build()
            );
            new GeoPackageWriter().write(entities, tmp);

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tmp)) {
                // 三张表都应存在
                for (String t : List.of("points", "linestrings", "polygons")) {
                    try (ResultSet rs = conn.createStatement()
                            .executeQuery("SELECT table_name FROM gpkg_contents WHERE table_name='" + t + "'")) {
                        assertThat(rs.next()).as("gpkg_contents 应包含 " + t).isTrue();
                    }
                    try (ResultSet rs = conn.createStatement()
                            .executeQuery("SELECT COUNT(*) FROM \"" + t + "\"")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).as(t + " 行数").isEqualTo(1);
                    }
                }
            }
        } finally {
           // Files.deleteIfExists(tmp);
        }
    }

    @Test
    void geoPackageWriter_crs_shouldWriteSrsEntry() throws Exception {
        Path tmp = Files.createTempFile(Paths.get("target"), "test_", ".gpkg");
        try {
            List<CADEntity> entities = List.of(
                    CADEntity.builder("POINT").layer("P")
                            .geometry(GF.createPoint(new Coordinate(100, 30))).build()
            );
            GeoPackageWriteConfig cfg = GeoPackageWriteConfig.builder().crs("EPSG:4490").build();
            new GeoPackageWriter(cfg).write(entities, tmp);

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tmp)) {
                try (ResultSet rs = conn.createStatement()
                        .executeQuery("SELECT srs_id, definition FROM gpkg_spatial_ref_sys WHERE srs_id=4490")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(4490);
                    assertThat(rs.getString(2)).contains("CGCS2000").as("4490 应包含 CGCS2000 WKT");
                }
                // gpkg_geometry_columns 的 srs_id 应为 4490
                try (ResultSet rs = conn.createStatement()
                        .executeQuery("SELECT srs_id FROM gpkg_geometry_columns WHERE table_name='points'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(4490);
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void geoPackageWriter_geomBlob_shouldHaveGpMagicBytes() throws Exception {
        Path tmp = Files.createTempFile(Paths.get("target"), "test_", ".gpkg");
        try {
            List<CADEntity> entities = List.of(
                    CADEntity.builder("POINT").layer("P")
                            .geometry(GF.createPoint(new Coordinate(1, 2))).build()
            );
            new GeoPackageWriter().write(entities, tmp);

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tmp)) {
                try (ResultSet rs = conn.createStatement()
                        .executeQuery("SELECT geom FROM points LIMIT 1")) {
                    assertThat(rs.next()).isTrue();
                    byte[] blob = rs.getBytes(1);
                    assertThat(blob).isNotNull();
                    assertThat(blob.length).isGreaterThan(8);
                    // GeoPackage magic: bytes 0='G', 1='P'
                    assertThat(blob[0]).isEqualTo((byte) 0x47);  // 'G'
                    assertThat(blob[1]).isEqualTo((byte) 0x50);  // 'P'
                    assertThat(blob[2]).isEqualTo((byte) 0x00);  // version
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
