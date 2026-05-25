package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.DXFVersion;
import com.nanodxf.output.*;
import com.nanodxf.EntityProperty;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 — 新实体写出 + 解析增强 + 流式 API + Shapefile 2D 输出。
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>DXFWriter：SOLID / 3DFACE / ELLIPSE 写出</li>
 *   <li>DXFWriter：SPLINE（有控制点 / 无控制点 / 控制点不足）</li>
 *   <li>DXFWriter：R12 路径 ELLIPSE 降级为 POLYLINE、SPLINE 降级为 POLYLINE</li>
 *   <li>解析：DIMENSION（实测值 + 定义点）/ LEADER / SPLINE 控制点</li>
 *   <li>CADParser.parseStream：与全量解析结果一致、filter、单位换算</li>
 *   <li>ShapefileWriter 2D：magic / shape type / 记录数 / DBF 字段 / PRJ / Null Shape /
 *       混合几何 / 含洞 Polygon / 空实体 / SHX 偏移一致性</li>
 * </ul>
 */
class V130Test extends NanoDxfTestBase {

    // =========================================================================
    // DXFWriter — SOLID / 3DFACE / ELLIPSE
    // =========================================================================

    @Test
    void dxfWriter_solid_shouldProduceSolidEntity() throws Exception {
        Coordinate[] ring = {
            new Coordinate(0, 0), new Coordinate(10, 0),
            new Coordinate(10, 10), new Coordinate(0, 10), new Coordinate(0, 0)};
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.SOLID).layer("填充")
                .geometry(GF.createPolygon(ring)).build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        assertThat(sw.toString()).contains("SOLID");
    }

    @Test
    void dxfWriter_3dface_shouldProduceFaceEntity() throws Exception {
        Coordinate[] ring = {
            new Coordinate(0, 0), new Coordinate(10, 0),
            new Coordinate(10, 10), new Coordinate(0, 10), new Coordinate(0, 0)};
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.FACE3D).layer("面")
                .geometry(GF.createLinearRing(ring)).build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        assertThat(sw.toString()).contains("3DFACE");
    }

    @Test
    void dxfWriter_ellipse_shouldProduceEllipseEntity() throws Exception {
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.ELLIPSE).layer("椭圆")
                .geometry(GF.createPoint(new Coordinate(50, 50)))
                .property(EntityProperty.MAJOR_AXIS_X, 30.0)
                .property(EntityProperty.MAJOR_AXIS_Y, 0.0)
                .property(EntityProperty.AXIS_RATIO, 0.5)
                .property(EntityProperty.START_ANGLE, 0.0)
                .property(EntityProperty.END_ANGLE, 2 * Math.PI)
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        assertThat(sw.toString()).contains("ELLIPSE").contains("0.5000");
    }

    @Test
    void dxfWriter_ellipse_r12_shouldFallBackToPolyline() throws Exception {
        // R12 不支持 ELLIPSE，应离散化为 POLYLINE
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.ELLIPSE).layer("椭圆")
                .geometry(GF.createPoint(new Coordinate(50, 50)))
                .property(EntityProperty.MAJOR_AXIS_X, 30.0)
                .property(EntityProperty.MAJOR_AXIS_Y, 0.0)
                .property(EntityProperty.AXIS_RATIO, 0.5)
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R12).build()).write(entities, sw);
        String dxf = sw.toString();
        assertThat(dxf).doesNotContain("ELLIPSE");
        assertThat(dxf).contains("POLYLINE");
    }

    // =========================================================================
    // DXFWriter — SPLINE
    // =========================================================================

    @Test
    void dxfWriter_spline_withControlPoints_shouldProduceSplineEntity() throws Exception {
        List<double[]> ctrlPts = List.of(
            new double[]{0, 0, 0}, new double[]{10, 20, 0},
            new double[]{30, 15, 0}, new double[]{40, 0, 0});
        Coordinate[] coords = ctrlPts.stream()
                .map(p -> new Coordinate(p[0], p[1])).toArray(Coordinate[]::new);
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.SPLINE).layer("样条")
                .geometry(GF.createLineString(coords))
                .property(EntityProperty.CONTROL_POINTS, ctrlPts)
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        String dxf = sw.toString();
        assertThat(dxf).contains("SPLINE").doesNotContain("LWPOLYLINE");
    }

    @Test
    void dxfWriter_spline_withoutControlPoints_fallsBackToLwPolyline() throws Exception {
        Coordinate[] coords = {new Coordinate(0, 0), new Coordinate(10, 5), new Coordinate(20, 0)};
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.SPLINE).layer("样条")
                .geometry(GF.createLineString(coords)).build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        assertThat(sw.toString()).contains("LWPOLYLINE");
    }

    @Test
    void dxfWriter_spline_withInsufficientControlPoints_fallsBackToLwPolyline() throws Exception {
        List<double[]> only3pts = List.of(
            new double[]{0, 0, 0}, new double[]{10, 20, 0}, new double[]{30, 5, 0});
        Coordinate[] coords = only3pts.stream()
                .map(p -> new Coordinate(p[0], p[1])).toArray(Coordinate[]::new);
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.SPLINE).layer("样条")
                .geometry(GF.createLineString(coords))
                .property(EntityProperty.CONTROL_POINTS, only3pts)
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        String dxf = sw.toString();
        assertThat(dxf).doesNotContain("SPLINE").contains("LWPOLYLINE");
    }

    @Test
    void dxfWriter_spline_r12_shouldAlwaysUsePolyline() throws Exception {
        // R12 不支持 SPLINE，不管有无控制点都用 POLYLINE
        List<double[]> ctrlPts = List.of(
            new double[]{0, 0, 0}, new double[]{10, 20, 0},
            new double[]{30, 15, 0}, new double[]{40, 0, 0});
        Coordinate[] coords = ctrlPts.stream()
                .map(p -> new Coordinate(p[0], p[1])).toArray(Coordinate[]::new);
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.SPLINE).layer("样条")
                .geometry(GF.createLineString(coords))
                .property(EntityProperty.CONTROL_POINTS, ctrlPts)
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R12).build()).write(entities, sw);
        String dxf = sw.toString();
        assertThat(dxf).doesNotContain("SPLINE");
        assertThat(dxf).contains("POLYLINE");
    }

    // =========================================================================
    // 解析增强：DIMENSION / LEADER / SPLINE 控制点
    // =========================================================================

    @Test
    void dimensionHandler_shouldExtractMeasurementValue() throws Exception {
        String dxf = entities(
                "  0\nDIMENSION\n  8\n标注\n" +
                " 10\n100\n 20\n50\n 30\n0\n" +
                " 11\n110\n 21\n55\n 31\n0\n" +
                " 42\n15.5\n" +
                " 13\n95\n 23\n50\n 33\n0\n" +
                " 14\n125\n 24\n50\n 34\n0\n" +
                " 70\n1\n");
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        CADEntity e = result.getEntities().get(0);
        assertThat(e.getType()).isEqualTo("DIMENSION");
        assertThat((Double) e.getProperties().get(EntityProperty.DIMENSION_VALUE))
                .isCloseTo(15.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(e.getProperties().get(EntityProperty.DIMENSION_TYPE)).isEqualTo(1);
        assertThat(e.getProperties().get(EntityProperty.DIM_POINT1)).isInstanceOf(double[].class);
        assertThat(e.getProperties().get(EntityProperty.DIM_POINT2)).isInstanceOf(double[].class);
    }

    @Test
    void leaderHandler_shouldProduceLineStringGeometry() throws Exception {
        String dxf = entities(
                "  0\nLEADER\n  8\n引线\n" +
                " 76\n3\n" +
                " 10\n0\n 20\n0\n 30\n0\n" +
                " 10\n10\n 20\n5\n 30\n0\n" +
                " 10\n20\n 20\n0\n 30\n0\n");
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        CADEntity e = result.getEntities().get(0);
        assertThat(e.getType()).isEqualTo("LEADER");
        assertThat(e.geometry()).isInstanceOf(LineString.class);
        assertThat(e.geometry().getNumPoints()).isEqualTo(3);
    }

    @Test
    void splineHandler_shouldStoreControlPoints() throws Exception {
        String dxf = entities(
                "  0\nSPLINE\n  8\n样条\n" +
                " 71\n3\n 72\n8\n 73\n4\n" +
                " 40\n0\n 40\n0\n 40\n0\n 40\n0\n 40\n1\n 40\n1\n 40\n1\n 40\n1\n" +
                " 10\n0\n 20\n0\n 30\n0\n" +
                " 10\n10\n 20\n20\n 30\n0\n" +
                " 10\n30\n 20\n15\n 30\n0\n" +
                " 10\n40\n 20\n0\n 30\n0\n");
        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("SPLINE");
        @SuppressWarnings("unchecked")
        List<double[]> pts = (List<double[]>) e.getProperties().get(EntityProperty.CONTROL_POINTS);
        assertThat(pts).hasSize(4);
    }

    // =========================================================================
    // CADParser.parseStream
    // =========================================================================

    @Test
    void parseStream_shouldReturnSameEntitiesAsParseResult() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" +
                "  0\nLINE\n  8\n0\n 10\n10\n 20\n0\n 30\n0\n 11\n20\n 21\n0\n 31\n0\n" +
                "  0\nPOINT\n  8\n0\n 10\n5\n 20\n5\n 30\n0\n" +
                "  0\nENDSEC\n  0\nEOF\n";
        Path tmpFile = Files.createTempFile(Paths.get("target"), "test_", ".dxf");
        try {
            Files.writeString(tmpFile, dxf);
            ParseConfig cfg = ParseConfig.builder().applyUnitConversion(false).build();
            int fullCount = new CADParser(cfg).parse(tmpFile).getEntities().size();
            List<CADEntity> streamed = new ArrayList<>();
            try (Stream<CADEntity> stream = new CADParser(cfg).parseStream(tmpFile)) {
                stream.forEach(streamed::add);
            }
            assertThat(streamed).hasSize(fullCount).hasSize(3);
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    void parseStream_shouldApplyUnitConversion() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  9\n$INSUNITS\n 70\n4\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nLINE\n  8\n0\n 10\n1000\n 20\n0\n 30\n0\n 11\n2000\n 21\n0\n 31\n0\n" +
                "  0\nENDSEC\n  0\nEOF\n";
        Path tmpFile = Files.createTempFile(Paths.get("target"), "test_stream_units_", ".dxf");
        try {
            Files.writeString(tmpFile, dxf);
            List<CADEntity> streamed = new ArrayList<>();
            try (Stream<CADEntity> stream = new CADParser().parseStream(tmpFile)) {
                stream.forEach(streamed::add);
            }
            assertThat(streamed).hasSize(1);
            LineString ls = (LineString) streamed.get(0).geometry();
            assertThat(ls.getStartPoint().getX()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(ls.getEndPoint().getX()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    void parseStream_shouldSupportFilter() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n1\n 21\n0\n 31\n0\n" +
                "  0\nPOINT\n  8\n0\n 10\n5\n 20\n5\n 30\n0\n" +
                "  0\nLINE\n  8\n0\n 10\n2\n 20\n0\n 30\n0\n 11\n3\n 21\n0\n 31\n0\n" +
                "  0\nENDSEC\n  0\nEOF\n";
        Path tmpFile = Files.createTempFile(Paths.get("target"), "test_filter_", ".dxf");
        try {
            Files.writeString(tmpFile, dxf);
            List<CADEntity> lines = new ArrayList<>();
            try (Stream<CADEntity> stream = new CADParser(
                    ParseConfig.builder().applyUnitConversion(false).build()).parseStream(tmpFile)) {
                stream.filter(e -> "LINE".equals(e.getType())).forEach(lines::add);
            }
            assertThat(lines).hasSize(2);
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    // =========================================================================
    // ShapefileWriter 2D
    // =========================================================================

    @Test
    void shapefileWriter_magicNumber_shouldBe9994() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("out.shp");
            new ShapefileWriter().write(List.of(
                CADEntity.builder(CADEntity.Types.POINT).layer("0")
                    .geometry(GF.createPoint(new Coordinate(1, 2))).build()), shp);
            assertThat(readShpMagic(shp)).isEqualTo(9994);
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_pointEntities_shouldWriteShapeType1() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("pt.shp");
            new ShapefileWriter().write(List.of(
                CADEntity.builder(CADEntity.Types.POINT).layer("0")
                    .geometry(GF.createPoint(new Coordinate(10, 20))).build()), shp);
            assertThat(readShpShapeType(shp)).isEqualTo(1);
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_linestringEntities_shouldWriteShapeType3() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("line.shp");
            LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(1, 1)});
            new ShapefileWriter().write(List.of(
                CADEntity.builder(CADEntity.Types.LWPOLYLINE).layer("道路")
                    .geometry(ls).build()), shp);
            assertThat(readShpShapeType(shp)).isEqualTo(3);
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_polygonEntities_shouldWriteShapeType5() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("poly.shp");
            Coordinate[] ring = {
                new Coordinate(0,0), new Coordinate(10,0),
                new Coordinate(10,10), new Coordinate(0,10), new Coordinate(0,0)};
            Polygon poly = GF.createPolygon(ring);
            new ShapefileWriter().write(List.of(
                CADEntity.builder(CADEntity.Types.LWPOLYLINE).layer("建筑")
                    .geometry(poly).build()), shp);
            assertThat(readShpShapeType(shp)).isEqualTo(5);
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_recordCount_shouldMatchEntityCount() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("cnt.shp");
            List<CADEntity> entities = List.of(
                CADEntity.builder(CADEntity.Types.POINT).layer("A")
                    .geometry(GF.createPoint(new Coordinate(1, 2))).build(),
                CADEntity.builder(CADEntity.Types.POINT).layer("B")
                    .geometry(GF.createPoint(new Coordinate(3, 4))).build());
            new ShapefileWriter().write(entities, shp);
            assertThat(readDbfRecordCount(tmp.resolve("cnt.dbf"))).isEqualTo(2);
            assertThat(readShxRecordCount(tmp.resolve("cnt.shx"))).isEqualTo(2);
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_dbf_shouldContainLayerAndTypeFields() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("attrs.shp");
            new ShapefileWriter(ShapefileWriteConfig.builder().encoding("UTF-8").build()).write(List.of(
                CADEntity.builder(CADEntity.Types.POINT).layer("高程点")
                    .geometry(GF.createPoint(new Coordinate(1, 2))).build()), shp);
            String dbfLatin1 = dbfLatin1(tmp.resolve("attrs.dbf"));
            assertThat(dbfLatin1).contains("POINT");
            // 中文图层名以 UTF-8 写入，用 ISO-8859-1 字节透传验证存在
            String layerInLatin1 = new String("高程点".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            assertThat(dbfLatin1).contains(layerInLatin1);
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_crs_shouldWritePrjFile() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("crs.shp");
            ShapefileWriteConfig cfg = ShapefileWriteConfig.builder().crs("EPSG:4326").build();
            new ShapefileWriter(cfg).write(List.of(
                CADEntity.builder(CADEntity.Types.POINT).layer("0")
                    .geometry(GF.createPoint(new Coordinate(1, 2))).build()), shp);
            Path prj = tmp.resolve("crs.prj");
            assertThat(prj).exists();
            assertThat(Files.readString(prj)).startsWith("GEOGCS[");
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_nullGeometry_shouldWriteNullShape() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("null.shp");
            List<CADEntity> entities = List.of(
                CADEntity.builder(CADEntity.Types.POINT).layer("0").build()); // no geometry
            new ShapefileWriter().write(entities, shp);
            assertThat(readDbfRecordCount(tmp.resolve("null.dbf"))).isEqualTo(1);
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_mixedGeometry_polygonDominant() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("mixed.shp");
            Coordinate[] ring = {
                new Coordinate(0,0), new Coordinate(10,0),
                new Coordinate(10,10), new Coordinate(0,10), new Coordinate(0,0)};
            List<CADEntity> entities = List.of(
                CADEntity.builder(CADEntity.Types.LWPOLYLINE).layer("建筑")
                    .geometry(GF.createPolygon(ring)).build(),
                CADEntity.builder(CADEntity.Types.LWPOLYLINE).layer("建筑")
                    .geometry(GF.createPolygon(ring)).build(),
                CADEntity.builder(CADEntity.Types.LWPOLYLINE).layer("道路")
                    .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(0,0), new Coordinate(5,5)})).build());
            new ShapefileWriter().write(entities, shp);
            assertThat(readShpShapeType(shp)).isEqualTo(5);
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_polygonWithHole_shouldWriteRing() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("hole.shp");
            Coordinate[] outer = {
                new Coordinate(0,0), new Coordinate(20,0),
                new Coordinate(20,20), new Coordinate(0,20), new Coordinate(0,0)};
            Coordinate[] hole = {
                new Coordinate(5,5), new Coordinate(15,5),
                new Coordinate(15,15), new Coordinate(5,15), new Coordinate(5,5)};
            Polygon poly = GF.createPolygon(GF.createLinearRing(outer),
                    new LinearRing[]{GF.createLinearRing(hole)});
            new ShapefileWriter().write(List.of(
                CADEntity.builder(CADEntity.Types.HATCH).layer("填充").geometry(poly).build()), shp);
            assertThat(readShpShapeType(shp)).isEqualTo(5);
            assertThat(readDbfRecordCount(tmp.resolve("hole.dbf"))).isEqualTo(1);
            assertThat(Files.size(shp)).isGreaterThan(200L);
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_emptyEntities_shouldWriteValidEmptyFiles() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("empty.shp");
            new ShapefileWriter().write(List.of(), shp);
            assertThat(shp).exists();
            assertThat(readDbfRecordCount(tmp.resolve("empty.dbf"))).isEqualTo(0);
            assertThat(readShxRecordCount(tmp.resolve("empty.shx"))).isEqualTo(0);
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_shxOffsetConsistency_shouldMatchShpRecords() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_");
        try {
            Path shp = tmp.resolve("idx.shp");
            List<CADEntity> entities = List.of(
                CADEntity.builder(CADEntity.Types.POINT).layer("A")
                    .geometry(GF.createPoint(new Coordinate(1, 2))).build(),
                CADEntity.builder(CADEntity.Types.POINT).layer("B")
                    .geometry(GF.createPoint(new Coordinate(3, 4))).build());
            new ShapefileWriter().write(entities, shp);
            byte[] shxBytes = Files.readAllBytes(tmp.resolve("idx.shx"));
            byte[] shpBytes = Files.readAllBytes(shp);
            // 第一条记录偏移 = 50 words = 100 bytes
            assertThat(ByteBuffer.wrap(shxBytes, 100, 4).order(ByteOrder.BIG_ENDIAN).getInt()).isEqualTo(50);
            // 第一条记录 record number = 1
            assertThat(ByteBuffer.wrap(shpBytes, 100, 4).order(ByteOrder.BIG_ENDIAN).getInt()).isEqualTo(1);
            // 第二条记录偏移 = 50 + (4+10) = 64 words（Point content = 10 words）
            assertThat(ByteBuffer.wrap(shxBytes, 108, 4).order(ByteOrder.BIG_ENDIAN).getInt()).isEqualTo(64);
        } finally { deleteDir(tmp); }
    }
}
