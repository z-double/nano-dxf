package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.EntityProperty;
import com.nanodxf.geometry.AciColorTable;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.CADBlock;
import com.nanodxf.model.DXFVersion;
import com.nanodxf.output.DXFWriteConfig;
import com.nanodxf.output.DXFWriter;
import com.nanodxf.output.GeoJsonSerializer;
import com.nanodxf.text.MTextCleaner;
import com.nanodxf.xdata.FeatureCodeRegistry;
import com.nanodxf.xdata.FeatureCodeRegistry.FeatureCodeInfo;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * CADParser 全链路验证。
 *
 * <p>Phase 1：HEADER 解析、ParseConfig 校验、LINE/CIRCLE/POINT/LWPOLYLINE
 * Phase 2：MTEXT 清洗、POLYLINE、INSERT+ATTRIB、HATCH（含洞）、3DFACE
 * Phase 3：XDATA 地物编码、颜色富化（ACI/True Color/BYLAYER）、GeoJsonSerializer、错误收集
 * Phase 4：容错（截断/循环块/未知 SECTION/零长度线）、性能基线
 */
class CADParserTest {

    private static final GeometryFactory GF = GeometryBuilder.factory();

    // =========================================================================
    // DXF 构建辅助
    // =========================================================================

    private static String entities(String... blocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n");
        sb.append("  0\nSECTION\n  2\nENTITIES\n");
        for (String b : blocks) sb.append(b);
        sb.append("  0\nENDSEC\n  0\nEOF\n");
        return sb.toString();
    }

    private static CADEntity single(String dxf) throws Exception {
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).as("应恰好有 1 个实体").hasSize(1);
        return result.getEntities().get(0);
    }

    // =========================================================================
    // Phase 1：HEADER & ParseConfig
    // =========================================================================

    @Test
    void parseHeader_shouldExtractVersionAndUnits() throws Exception {
        String dxf = "  0\nSECTION\n  2\nHEADER\n" +
                "  9\n$ACADVER\n  1\nAC1015\n  9\n$INSUNITS\n 70\n4\n" +
                "  0\nENDSEC\n  0\nEOF\n";
        ParseResult result = new CADParser(ParseConfig.builder().crs("EPSG:4545").build())
                .parse(new StringReader(dxf));
        assertThat(result.getMetadata().getVersion()).isEqualTo(DXFVersion.R2000);
        assertThat(result.getMetadata().getInsunits()).isEqualTo(4);
        assertThat(result.getMetadata().getCrs()).isEqualTo("EPSG:4545");
        assertThat(result.getMetadata().getCrsSource()).isEqualTo("caller_specified");
        assertThat(result.getEntities()).isEmpty();
    }

    @Test
    void parseConfig_arcToleranceMustBePositive() {
        assertThatThrownBy(() -> ParseConfig.builder().arcTolerance(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseConfig_coordinateDecimalPlacesMustBeInRange() {
        assertThatThrownBy(() -> ParseConfig.builder().coordinateDecimalPlaces(16).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // Phase 1：基础实体
    // =========================================================================

    @Test
    void parseLine_shouldReturnLineStringWithCorrectCoordinates() throws Exception {
        String dxf = entities("  0\nLINE\n  8\n道路\n 10\n100\n 20\n200\n 30\n0\n 11\n150\n 21\n200\n 31\n0\n");
        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("LINE");
        assertThat(e.getLayer()).isEqualTo("道路");
        org.locationtech.jts.geom.LineString ls =
                (org.locationtech.jts.geom.LineString) e.geometry();
        assertThat(ls.getStartPoint().getX()).isEqualTo(100.0);
        assertThat(ls.getEndPoint().getX()).isEqualTo(150.0);
    }

    @Test
    void parseCircle_shouldReturnClosedLinearRing() throws Exception {
        CADEntity e = single(entities("  0\nCIRCLE\n  8\n0\n 10\n50\n 20\n50\n 30\n0\n 40\n25\n"));
        LinearRing ring = (LinearRing) e.geometry();
        assertThat(ring.getCoordinateN(0)).isEqualTo(ring.getCoordinateN(ring.getNumPoints() - 1));
    }

    @Test
    void parsePoint_shouldHave3DCoordinateAndElevationProperty() throws Exception {
        CADEntity e = single(entities("  0\nPOINT\n  8\n高程点\n 10\n1000\n 20\n2000\n 30\n125.3\n"));
        assertThat(((Point) e.geometry()).getCoordinate().getZ()).isEqualTo(125.3);
        assertThat(e.getProperties().get("elevation")).isEqualTo(125.3);
    }

    @Test
    void parseLWPolyline_closedWithElevation_shouldReturnLinearRingWithZ() throws Exception {
        String dxf = entities(
                "  0\nLWPOLYLINE\n  8\n等高线\n 38\n125.3\n 70\n1\n" +
                        " 10\n0\n 20\n0\n 10\n100\n 20\n0\n 10\n100\n 20\n100\n 10\n0\n 20\n100\n");
        CADEntity e = single(dxf);
        LinearRing ring = (LinearRing) e.geometry();
        assertThat(ring.getCoordinateN(0)).isEqualTo(ring.getCoordinateN(ring.getNumPoints() - 1));
        for (org.locationtech.jts.geom.Coordinate c : ring.getCoordinates()) {
            assertThat(c.getZ()).isEqualTo(125.3);
        }
        assertThat(e.getProperties().get("elevation")).isEqualTo(125.3);
    }

    // =========================================================================
    // Phase 2：复杂实体
    // =========================================================================

    @Test
    void mTextCleaner_shouldStripFormatsAndConvertControlCodes() {
        assertThat(MTextCleaner.clean("{\\fArial|b0;hello}")).isEqualTo("hello");
        assertThat(MTextCleaner.clean("L1\\PL2")).isEqualTo("L1\nL2");
        assertThat(MTextCleaner.clean("角度%%d")).isEqualTo("角度°");
        assertThat(MTextCleaner.clean("\\U+4E2D文")).isEqualTo("中文");
        assertThat(MTextCleaner.clean("{\\H2;{\\C1;red text}}")).isEqualTo("red text");
    }

    @Test
    void parseMText_shouldReturnCleanText() throws Exception {
        CADEntity e = single(entities(
                "  0\nMTEXT\n  8\n注记\n 10\n0\n 20\n0\n 30\n0\n 40\n3\n" +
                        "  1\n{\\fArial;道路名称\\P（二级）}\n"));
        String text = (String) e.getProperties().get("text");
        assertThat(text).contains("道路名称").contains("\n").doesNotContain("\\f");
    }

    @Test
    void parsePolyline_closed3D_shouldReturnLinearRingWithZ() throws Exception {
        CADEntity e = single(entities(
                "  0\nPOLYLINE\n  8\n0\n 70\n9\n" +
                        "  0\nVERTEX\n 10\n0\n 20\n0\n 30\n10\n" +
                        "  0\nVERTEX\n 10\n100\n 20\n0\n 30\n10\n" +
                        "  0\nVERTEX\n 10\n100\n 20\n100\n 30\n10\n" +
                        "  0\nSEQEND\n"));
        assertThat(e.geometry()).isInstanceOf(LinearRing.class);
        assertThat(e.geometry().getCoordinates()[0].getZ()).isEqualTo(10.0);
    }

    // =========================================================================
    // INSERT 块展开
    // =========================================================================

    /**
     * 最简块展开：块 SQUARE 内含一条 LINE（(0,0)-(10,0)），
     * INSERT 以 (100,200) 为插入点、比例 2、无旋转，
     * 展开后应得到 LINE (100,200)-(120,200)。
     */
    @Test
    void parseInsert_blockExpansion_shouldTransformBlockEntities() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                        "  0\nSECTION\n  2\nBLOCKS\n" +
                        "  0\nBLOCK\n  2\nSQUARE\n 70\n0\n 10\n0\n 20\n0\n 30\n0\n" +
                        "  0\nLINE\n  8\n建筑\n" +
                        " 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" +
                        "  0\nENDBLK\n" +
                        "  0\nENDSEC\n" +
                        "  0\nSECTION\n  2\nENTITIES\n" +
                        "  0\nINSERT\n  8\n0\n  2\nSQUARE\n" +
                        " 10\n100\n 20\n200\n 30\n0\n 41\n2\n 42\n2\n 50\n0\n" +
                        "  0\nENDSEC\n  0\nEOF\n";

        ParseResult result = new CADParser().parse(new StringReader(dxf));
        // 展开后得到 1 个 LINE 实体（不是 INSERT 点）
        assertThat(result.getEntities()).hasSize(1);
        CADEntity e = result.getEntities().get(0);
        assertThat(e.getType()).isEqualTo("LINE");

        org.locationtech.jts.geom.LineString ls =
                (org.locationtech.jts.geom.LineString) e.geometry();
        // 起点：(0,0) * scale2 → (0,0) → translate (100,200) → (100,200)
        assertThat(ls.getStartPoint().getX()).isEqualTo(100.0);
        assertThat(ls.getStartPoint().getY()).isEqualTo(200.0);
        // 终点：(10,0) * scale2 → (20,0) → translate → (120,200)
        assertThat(ls.getEndPoint().getX()).isEqualTo(120.0);
        assertThat(ls.getEndPoint().getY()).isEqualTo(200.0);
    }

    @Test
    void parseInsert_blockExpansion_withRotation90_shouldRotateCoordinates() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                        "  0\nSECTION\n  2\nBLOCKS\n" +
                        "  0\nBLOCK\n  2\nROT_TEST\n 70\n0\n 10\n0\n 20\n0\n 30\n0\n" +
                        "  0\nLINE\n  8\n0\n" +
                        " 10\n10\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" + // zero-length，应被跳过
                        "  0\nLINE\n  8\n0\n" +
                        " 10\n1\n 20\n0\n 30\n0\n 11\n0\n 21\n1\n 31\n0\n" +   // (1,0)-(0,1)
                        "  0\nENDBLK\n  0\nENDSEC\n" +
                        "  0\nSECTION\n  2\nENTITIES\n" +
                        "  0\nINSERT\n  8\n0\n  2\nROT_TEST\n" +
                        " 10\n0\n 20\n0\n 30\n0\n 41\n1\n 42\n1\n 50\n90\n" + // 旋转 90°
                        "  0\nENDSEC\n  0\nEOF\n";

        ParseResult result = new CADParser().parse(new StringReader(dxf));
        // 第一条 LINE 零长度被跳过，第二条 (1,0)-(0,1) 旋转 90° → (0,1)-(-1,0)
        assertThat(result.getEntities()).hasSize(1);
        org.locationtech.jts.geom.LineString ls =
                (org.locationtech.jts.geom.LineString) result.getEntities().get(0).geometry();
        // 旋转 90°: (x,y) → (-y, x)
        // (1,0) → (0, 1)
        assertThat(ls.getStartPoint().getX()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ls.getStartPoint().getY()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void parseInsert_undefinedBlock_shouldFallbackToInsertPoint() throws Exception {
        // 块 UNDEFINED 未在 BLOCKS 段定义，展开退化为 INSERT Point
        String dxf = entities(
                "  0\nINSERT\n  8\n0\n  2\nUNDEFINED\n" +
                        " 10\n500\n 20\n600\n 30\n0\n 41\n1\n 42\n1\n 50\n0\n");

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("INSERT");
        assertThat(e.getProperties().get("blockName")).isEqualTo("UNDEFINED");
        assertThat(((Point) e.geometry()).getX()).isEqualTo(500.0);
    }

    @Test
    void parseInsert_withAttrib_shouldReturnPointWithAttributes() throws Exception {
        CADEntity e = single(entities(
                "  0\nINSERT\n  8\n0\n  2\n高程点\n 10\n500\n 20\n600\n 30\n0\n" +
                        " 41\n1\n 42\n1\n 50\n0\n 66\n1\n" +
                        "  0\nATTRIB\n  2\nELV\n  1\n125.30\n 10\n500\n 20\n600\n 30\n0\n" +
                        "  0\nSEQEND\n"));
        assertThat(e.getProperties().get("blockName")).isEqualTo("高程点");
        @SuppressWarnings("unchecked")
        Map<String, String> attrs = (Map<String, String>) e.getProperties().get("attributes");
        assertThat(attrs).containsEntry("ELV", "125.30");
    }

    @Test
    void parseHatch_withHole_shouldReturnPolygonWithOneInteriorRing() throws Exception {
        CADEntity e = single(entities(
                "  0\nHATCH\n  8\n填充\n" +
                        " 91\n2\n" +
                        " 92\n1\n 93\n4\n" +
                        " 72\n1\n 10\n0\n 20\n0\n 11\n200\n 21\n0\n" +
                        " 72\n1\n 10\n200\n 20\n0\n 11\n200\n 21\n200\n" +
                        " 72\n1\n 10\n200\n 20\n200\n 11\n0\n 21\n200\n" +
                        " 72\n1\n 10\n0\n 20\n200\n 11\n0\n 21\n0\n" +
                        " 92\n0\n 93\n4\n" +
                        " 72\n1\n 10\n50\n 20\n50\n 11\n150\n 21\n50\n" +
                        " 72\n1\n 10\n150\n 20\n50\n 11\n150\n 21\n150\n" +
                        " 72\n1\n 10\n150\n 20\n150\n 11\n50\n 21\n150\n" +
                        " 72\n1\n 10\n50\n 20\n150\n 11\n50\n 21\n50\n"));
        assertThat(((Polygon) e.geometry()).getNumInteriorRing()).isEqualTo(1);
    }

    @Test
    void parse3DFace_shouldReturnLinearRingWith5Points() throws Exception {
        CADEntity e = single(entities(
                "  0\n3DFACE\n  8\n0\n" +
                        " 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" +
                        " 12\n10\n 22\n10\n 32\n0\n 13\n0\n 23\n10\n 33\n0\n"));
        assertThat(e.geometry()).isInstanceOf(LinearRing.class);
        assertThat(e.geometry().getNumPoints()).isEqualTo(5); // 4 顶点 + 闭合首点
    }

    // =========================================================================
    // Phase 3：XDATA + 地物编码
    // =========================================================================

    @Test
    void parseEntityWithCassXData_shouldExtractAndMapFeatureCode() throws Exception {
        String dxf = entities(
                "  0\nLWPOLYLINE\n  8\n建筑\n 38\n0\n 70\n1\n" +
                        " 10\n0\n 20\n0\n 10\n10\n 20\n0\n 10\n10\n 20\n10\n 10\n0\n 20\n10\n" +
                        "1001\nCASS\n1000\n41000\n");
        CADEntity e = single(dxf);
        assertThat(e.getProperties().get("featureCode")).isEqualTo("41000");
        assertThat(e.getProperties().get("featureType")).isEqualTo("普通房屋");
        assertThat(e.getProperties().get("featureCategory")).isEqualTo("建筑");
        assertThat(e.getProperties().get("featureTypeSource")).isEqualTo("registry");
        assertThat(e.getProperties()).containsKey("xdata");
    }

    @Test
    void parseEntityWithUnknownXDataCode_shouldMarkUnknownSource() throws Exception {
        String dxf = entities(
                "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n1\n 21\n1\n 31\n0\n" +
                        "1001\nCASS\n1000\n99999\n");
        CADEntity e = single(dxf);
        assertThat(e.getProperties().get("featureCode")).isEqualTo("99999");
        assertThat(e.getProperties().get("featureTypeSource")).isEqualTo("unknown");
    }

    @Test
    void featureCodeRegistry_shouldContainKeyCategories() {
        Optional<FeatureCodeInfo> house = FeatureCodeRegistry.lookup("41000");
        assertThat(house).isPresent();
        assertThat(house.get().name()).isEqualTo("普通房屋");
        assertThat(house.get().category()).isEqualTo("建筑");

        assertThat(FeatureCodeRegistry.lookup("51101")).isPresent(); // 等高线
        assertThat(FeatureCodeRegistry.lookup("24101")).isPresent(); // 水准点
        assertThat(FeatureCodeRegistry.lookup("31010")).isPresent(); // 硬化路面
        assertThat(FeatureCodeRegistry.lookup("99999")).isEmpty();   // 未收录
        assertThat(FeatureCodeRegistry.size()).isGreaterThan(300);
    }

    // =========================================================================
    // Phase 3：颜色富化
    // =========================================================================

    @Test
    void aciColorTable_standardColors() {
        assertThat(AciColorTable.toRgb(1)).containsExactly(255, 0, 0);   // Red
        assertThat(AciColorTable.toRgb(2)).containsExactly(255, 255, 0); // Yellow
        assertThat(AciColorTable.toRgb(3)).containsExactly(0, 255, 0);   // Green
        assertThat(AciColorTable.toRgb(256)).isNull(); // BYLAYER
        assertThat(AciColorTable.toRgb(0)).isNull();   // BYBLOCK
    }

    @Test
    void parseEntityWithExplicitAciColor_shouldHaveColorRgbInProperties() throws Exception {
        String dxf = entities(
                "  0\nLINE\n  8\n0\n 62\n1\n 10\n0\n 20\n0\n 30\n0\n 11\n1\n 21\n1\n 31\n0\n");
        CADEntity e = single(dxf);
        assertThat(e.getProperties().get("colorAci")).isEqualTo(1);
        assertThat((int[]) e.getProperties().get("colorRgb")).containsExactly(255, 0, 0);
    }

    @Test
    void parseEntityWithTrueColor_shouldOverrideAci() throws Exception {
        int orange = (255 << 16) | (128 << 8) | 0; // 0xFF8000
        String dxf = entities(
                "  0\nLINE\n  8\n0\n 62\n1\n 420\n" + orange + "\n" +
                        " 10\n0\n 20\n0\n 30\n0\n 11\n1\n 21\n1\n 31\n0\n");
        CADEntity e = single(dxf);
        assertThat((int[]) e.getProperties().get("colorRgb")).containsExactly(255, 128, 0);
        assertThat(e.getProperties()).doesNotContainKey("colorAci");
    }

    @Test
    void parseEntityByLayerColor_shouldInheritLayerAciAsRgb() throws Exception {
        // 图层 "道路" 颜色 ACI=1 (红色)，实体无显式颜色（code 62 = 256 表示 BYLAYER）
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                        "  0\nSECTION\n  2\nTABLES\n" +
                        "  0\nTABLE\n  2\nLAYER\n" +
                        "  0\nLAYER\n  2\n道路\n 62\n1\n  6\nContinuous\n" +
                        "  0\nENDTAB\n" +
                        "  0\nENDSEC\n" +
                        "  0\nSECTION\n  2\nENTITIES\n" +
                        "  0\nLINE\n  8\n道路\n" +
                        " 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" +
                        "  0\nENDSEC\n  0\nEOF\n";

        CADEntity e = single(dxf);
        // BYLAYER 继承：图层 ACI 1 → RGB [255, 0, 0]
        assertThat((int[]) e.getProperties().get("colorRgb")).containsExactly(255, 0, 0);
        assertThat(e.getProperties()).doesNotContainKey("colorAci");
    }

    // =========================================================================
    // Phase 3：GeoJsonSerializer
    // =========================================================================

    @Test
    void geoJsonSerializer_lineEntity_shouldProduceValidGeoJson() throws Exception {
        String dxf = entities(
                "  0\nLINE\n  8\n道路\n 10\n100\n 20\n200\n 30\n0\n 11\n150\n 21\n200\n 31\n0\n");
        ParseResult result = new CADParser(ParseConfig.builder().crs("EPSG:4545").build())
                .parse(new StringReader(dxf));

        GeoJsonSerializer ser = new GeoJsonSerializer(result.getMetadata());
        String json = ser.serialize(result.getEntities(), result.getMetadata());

        assertThat(json).startsWith("{\"type\":\"FeatureCollection\"");
        assertThat(json).contains("\"crs\"");
        assertThat(json).contains("\"EPSG:4545\"");
        assertThat(json).contains("\"type\":\"LineString\"");
        assertThat(json).contains("\"layer\":\"道路\"");
        assertThat(json).contains("100.0000"); // 默认 4 位精度
    }

    @Test
    void geoJsonSerializer_coordinatePrecision_shouldRespectConfig() throws Exception {
        String dxf = entities(
                "  0\nLINE\n  8\n0\n 10\n123456.789\n 20\n0\n 30\n0\n 11\n0\n 21\n1\n 31\n0\n");
        ParseResult result = new CADParser().parse(new StringReader(dxf));

        GeoJsonSerializer ser2 = new GeoJsonSerializer(2);
        String json = ser2.serialize(result.getEntities(), result.getMetadata());
        assertThat(json).contains("123456.79"); // 2 位，四舍五入
    }

    @Test
    void geoJsonSerializer_circleAsPolygon_shouldContainPolygonType() throws Exception {
        CADEntity e = single(entities("  0\nCIRCLE\n  8\n0\n 10\n50\n 20\n50\n 30\n0\n 40\n25\n"));
        GeoJsonSerializer ser = new GeoJsonSerializer(4);
        String json = ser.serialize(java.util.List.of(e), null);
        // LinearRing → Polygon
        assertThat(json).contains("\"type\":\"Polygon\"");
    }

    @Test
    void geoJsonSerializer_escapeSpecialChars() {
        assertThat(GeoJsonSerializer.escapeJson("hello\"world")).isEqualTo("hello\\\"world");
        assertThat(GeoJsonSerializer.escapeJson("line1\nline2")).isEqualTo("line1\\nline2");
        assertThat(GeoJsonSerializer.escapeJson(null)).isEmpty();
    }

    // =========================================================================
    // Phase 3：错误收集
    // =========================================================================

    @Test
    void unknownEntityType_shouldBeRecordedAsInfoError() throws Exception {
        String dxf = entities("  0\nMYPRIVATE\n  8\n0\n 10\n0\n 20\n0\n");
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        boolean hasInfoForType = result.getErrors().stream()
                .anyMatch(err -> err.getLevel() == ParseErrorLevel.INFO
                        && "MYPRIVATE".equals(err.getEntityType()));
        assertThat(hasInfoForType).isTrue();
    }

    @Test
    void parseStats_entityCountShouldMatchParsedEntities() throws Exception {
        String dxf = entities(
                "  0\nLINE\n 10\n0\n 20\n0\n 30\n0\n 11\n1\n 21\n1\n 31\n0\n",
                "  0\nLINE\n 10\n1\n 20\n1\n 30\n0\n 11\n2\n 21\n2\n 31\n0\n");
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getStats().entityCount()).isEqualTo(2);
        assertThat(result.getStats().parseMs()).isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // Phase 4：容错（边界情况）与性能基线
    // =========================================================================

    @Test
    void truncatedFile_missingEndsecAndEof_shouldReturnParsedEntities() throws Exception {
        // 文件在 ENTITIES 段内突然截断，无 ENDSEC 也无 EOF
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                        "  0\nSECTION\n  2\nENTITIES\n" +
                        "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n";
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        // 不能抛出异常；截断前的完整 LINE 应被解析
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).getType()).isEqualTo("LINE");
    }

    @Test
    void truncatedFile_midEntity_shouldNotThrow() throws Exception {
        // 文件在第二个实体中途截断（只有起点坐标，无终点）
        String dxf =
                "  0\nSECTION\n  2\nENTITIES\n" +
                        "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" +
                        "  0\nLINE\n  8\n0\n 10\n999\n 20\n999\n"; // 文件在此截断
        // 不能抛出异常；至少第一条完整 LINE 应被解析
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).isNotEmpty();
        assertThat(result.getEntities().get(0).getType()).isEqualTo("LINE");
    }

    @Test
    void circularBlockReference_shouldDetectCycleAndFallbackToPoint() throws Exception {
        // BLK_A 包含 BLK_B 的 INSERT，BLK_B 包含 BLK_A 的 INSERT（A→B→A 循环）
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                        "  0\nSECTION\n  2\nBLOCKS\n" +
                        "  0\nBLOCK\n  2\nBLK_A\n 70\n0\n 10\n0\n 20\n0\n 30\n0\n" +
                        "  0\nINSERT\n  2\nBLK_B\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 41\n1\n 42\n1\n 50\n0\n" +
                        "  0\nENDBLK\n" +
                        "  0\nBLOCK\n  2\nBLK_B\n 70\n0\n 10\n0\n 20\n0\n 30\n0\n" +
                        "  0\nINSERT\n  2\nBLK_A\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 41\n1\n 42\n1\n 50\n0\n" +
                        "  0\nENDBLK\n" +
                        "  0\nENDSEC\n" +
                        "  0\nSECTION\n  2\nENTITIES\n" +
                        "  0\nINSERT\n  8\n0\n  2\nBLK_A\n 10\n100\n 20\n200\n 30\n0\n 41\n1\n 42\n1\n 50\n0\n" +
                        "  0\nENDSEC\n  0\nEOF\n";
        // 路径集合检测到循环 → 退化为插入点，不死循环也不抛异常
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        CADEntity e = result.getEntities().get(0);
        assertThat(e.getType()).isEqualTo("INSERT");
        assertThat(e.getProperties().get("blockName")).isEqualTo("BLK_A");
        assertThat(((Point) e.geometry()).getX()).isEqualTo(100.0);
    }

    @Test
    void unknownSectionType_shouldBeSkippedWithoutError() throws Exception {
        // 未知 SECTION "CUSTOM" 应被 SectionDispatcher 静默跳过，不影响后续解析
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                        "  0\nSECTION\n  2\nCUSTOM\n  1\nignored data\n  0\nENDSEC\n" +
                        "  0\nSECTION\n  2\nENTITIES\n" +
                        "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" +
                        "  0\nENDSEC\n  0\nEOF\n";
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        // CUSTOM 段静默跳过；后续 LINE 正常解析；无错误记录
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).getType()).isEqualTo("LINE");
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void zeroLengthLine_shouldBeSkipped() throws Exception {
        // 起终点完全相同的 LINE 无几何意义，LineHandler 应跳过（返回空列表）
        String dxf = entities(
                "  0\nLINE\n  8\n0\n 10\n100\n 20\n200\n 30\n0\n 11\n100\n 21\n200\n 31\n0\n");
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).isEmpty();
    }

    // =========================================================================
    // v1.1：单位换算 / XDATA 序列化
    // =========================================================================

    @Test
    void unitConversion_millimeterDxf_shouldScaleCoordinatesToMeters() throws Exception {
        // $INSUNITS=4（毫米），LINE 从 (1000,2000) 到 (3000,2000)
        // 换算后应变为 (1.0,2.0) 到 (3.0,2.0)（× 0.001）
        String dxf =
            "  0\nSECTION\n  2\nHEADER\n" +
            "  9\n$INSUNITS\n 70\n4\n" +
            "  0\nENDSEC\n" +
            "  0\nSECTION\n  2\nENTITIES\n" +
            "  0\nLINE\n  8\n0\n 10\n1000\n 20\n2000\n 30\n0\n 11\n3000\n 21\n2000\n 31\n0\n" +
            "  0\nENDSEC\n  0\nEOF\n";

        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        org.locationtech.jts.geom.LineString ls =
            (org.locationtech.jts.geom.LineString) result.getEntities().get(0).geometry();
        assertThat(ls.getStartPoint().getX()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ls.getStartPoint().getY()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ls.getEndPoint().getX()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void unitConversion_disabled_shouldKeepOriginalCoordinates() throws Exception {
        // applyUnitConversion=false 时，坐标应保持原始毫米值
        String dxf =
            "  0\nSECTION\n  2\nHEADER\n" +
            "  9\n$INSUNITS\n 70\n4\n" +
            "  0\nENDSEC\n" +
            "  0\nSECTION\n  2\nENTITIES\n" +
            "  0\nLINE\n  8\n0\n 10\n1000\n 20\n0\n 30\n0\n 11\n2000\n 21\n0\n 31\n0\n" +
            "  0\nENDSEC\n  0\nEOF\n";

        ParseConfig cfg = ParseConfig.builder().applyUnitConversion(false).build();
        ParseResult result = new CADParser(cfg).parse(new StringReader(dxf));
        org.locationtech.jts.geom.LineString ls =
            (org.locationtech.jts.geom.LineString) result.getEntities().get(0).geometry();
        assertThat(ls.getStartPoint().getX()).isEqualTo(1000.0);
    }

    @Test
    void geoJsonSerializer_xdata_shouldBeSerializedNotNull() throws Exception {
        String dxf = entities(
            "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n1\n 21\n0\n 31\n0\n" +
            "1001\nCASS\n1000\n41000\n");
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        GeoJsonSerializer ser = new GeoJsonSerializer(4);
        String json = ser.serialize(result.getEntities(), result.getMetadata());
        // xdata 应序列化为真实结构，不应是 null
        assertThat(json).contains("\"xdata\"");
        assertThat(json).doesNotContain("\"xdata\":null");
        assertThat(json).contains("\"code\"");
        assertThat(json).contains("41000");
    }

    // =========================================================================
    // v1.1：DXFWriter
    // =========================================================================

    @Test
    void dxfWriter_line_roundTrip() throws Exception {
        // 写出一条 LINE，再解析回来，坐标应一致
        String dxf = entities(
            "  0\nLINE\n  8\n道路\n 10\n100\n 20\n200\n 30\n0\n 11\n150\n 21\n250\n 31\n0\n");
        List<CADEntity> src = new CADParser().parse(new StringReader(dxf)).getEntities();
        assertThat(src).hasSize(1);

        StringWriter sw = new StringWriter();
        new DXFWriter().write(src, sw);
        String written = sw.toString();

        // 解析写出的 DXF
        ParseResult result = new CADParser(
            ParseConfig.builder().applyUnitConversion(false).build())
            .parse(new StringReader(written));
        assertThat(result.getEntities()).hasSize(1);
        CADEntity e = result.getEntities().get(0);
        assertThat(e.getType()).isEqualTo("LINE");
        assertThat(e.getLayer()).isEqualTo("道路");
        LineString ls = (LineString) e.geometry();
        assertThat(ls.getStartPoint().getX()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(ls.getStartPoint().getY()).isCloseTo(200.0, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(ls.getEndPoint().getX()).isCloseTo(150.0, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void dxfWriter_lwPolyline_closedRing_roundTrip() throws Exception {
        // 写出闭合 LWPOLYLINE（等高线），再解析回来应为 LinearRing，elevation 一致
        String dxf = entities(
            "  0\nLWPOLYLINE\n  8\n等高线\n 38\n125.3\n 70\n1\n" +
            " 10\n0\n 20\n0\n 10\n100\n 20\n0\n 10\n100\n 20\n100\n 10\n0\n 20\n100\n");
        List<CADEntity> src = new CADParser().parse(new StringReader(dxf)).getEntities();

        StringWriter sw = new StringWriter();
        new DXFWriter().write(src, sw);
        ParseResult result = new CADParser(
            ParseConfig.builder().applyUnitConversion(false).build())
            .parse(new StringReader(sw.toString()));

        assertThat(result.getEntities()).hasSize(1);
        CADEntity e = result.getEntities().get(0);
        assertThat(e.geometry()).isInstanceOf(LinearRing.class);
        // elevation 应保留
        for (org.locationtech.jts.geom.Coordinate c : e.geometry().getCoordinates()) {
            assertThat(c.getZ()).isCloseTo(125.3, org.assertj.core.data.Offset.offset(1e-3));
        }
    }

    @Test
    void dxfWriter_point_withElevation_roundTrip() throws Exception {
        String dxf = entities(
            "  0\nPOINT\n  8\n高程点\n 10\n500\n 20\n600\n 30\n88.5\n");
        List<CADEntity> src = new CADParser().parse(new StringReader(dxf)).getEntities();

        StringWriter sw = new StringWriter();
        new DXFWriter().write(src, sw);
        ParseResult result = new CADParser(
            ParseConfig.builder().applyUnitConversion(false).build())
            .parse(new StringReader(sw.toString()));

        assertThat(result.getEntities()).hasSize(1);
        Point p = (Point) result.getEntities().get(0).geometry();
        assertThat(p.getX()).isCloseTo(500.0, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(p.getCoordinate().getZ()).isCloseTo(88.5, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void dxfWriter_text_roundTrip() throws Exception {
        String dxf = entities(
            "  0\nTEXT\n  8\n注记\n 10\n10\n 20\n20\n 30\n0\n 40\n5\n  1\n测试文字\n");
        List<CADEntity> src = new CADParser().parse(new StringReader(dxf)).getEntities();

        StringWriter sw = new StringWriter();
        new DXFWriter().write(src, sw);
        String written = sw.toString();

        // 输出内容应含关键字段
        assertThat(written).contains("TEXT");
        assertThat(written).contains("测试文字");
        assertThat(written).contains("注记");
    }

    @Test
    void dxfWriter_multipleEntities_shouldProduceValidDxf() throws Exception {
        // 多种类型混合写出后可被解析，且数量一致
        String dxf = entities(
            "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n",
            "  0\nPOINT\n  8\n高程点\n 10\n5\n 20\n5\n 30\n10\n",
            "  0\nLWPOLYLINE\n  8\n0\n 70\n0\n 10\n0\n 20\n0\n 10\n10\n 20\n0\n 10\n10\n 20\n10\n");
        List<CADEntity> src = new CADParser().parse(new StringReader(dxf)).getEntities();
        assertThat(src).hasSize(3);

        StringWriter sw = new StringWriter();
        new DXFWriter().write(src, sw);
        ParseResult result = new CADParser(
            ParseConfig.builder().applyUnitConversion(false).build())
            .parse(new StringReader(sw.toString()));
        assertThat(result.getEntities()).hasSize(3);
    }

    @Test
    void dxfWriteConfig_encodingAutoSelect_preR2007usesGbk() {
        // R2007 以前的版本（含 R12 / R2000 / R2004）默认使用 GBK 编码
        DXFWriteConfig cfgR12   = DXFWriteConfig.builder().version(DXFVersion.R12).build();
        DXFWriteConfig cfgR2000 = DXFWriteConfig.builder().version(DXFVersion.R2000).build();
        assertThat(cfgR12.getEncoding()).isEqualTo("GBK");
        assertThat(cfgR2000.getEncoding()).isEqualTo("GBK");
    }

    @Test
    void dxfWriteConfig_encodingAutoSelect_r2007usesUtf8() {
        DXFWriteConfig cfg = DXFWriteConfig.builder().version(DXFVersion.R2007).build();
        assertThat(cfg.getEncoding()).isEqualTo("UTF-8");
    }

    // =========================================================================
    // v1.2.0 写出：ARC / CIRCLE / HATCH / INSERT + 块定义
    // =========================================================================

    @Test
    void dxfWriter_arc_shouldProduceArcEntity() throws Exception {
        // ARC 写出约定：type=ARC, geometry=Point(圆心), properties={radius,startAngle,endAngle}
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.ARC)
                .layer("弧形")
                .geometry(GF.createPoint(new Coordinate(50, 50)))
                .property(EntityProperty.RADIUS,      20.0)
                .property(EntityProperty.START_ANGLE,  0.0)
                .property(EntityProperty.END_ANGLE,  270.0)
                .build());

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        String dxf = sw.toString();

        assertThat(dxf).contains("ARC");
        assertThat(dxf).contains("50.0000");  // 圆心 x
        assertThat(dxf).contains("20.0000");  // 半径
        assertThat(dxf).contains("270.0000"); // 终止角
    }

    @Test
    void dxfWriter_circle_shouldProduceCircleEntity() throws Exception {
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.CIRCLE)
                .layer("圆形")
                .geometry(GF.createPoint(new Coordinate(100, 100)))
                .property(EntityProperty.RADIUS, 15.0)
                .build());

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        String dxf = sw.toString();

        assertThat(dxf).contains("CIRCLE");
        assertThat(dxf).contains("15.0000"); // 半径
    }

    @Test
    void dxfWriter_hatch_solidFill_roundTrip() throws Exception {
        // HATCH 写出：type=HATCH + Polygon → 解析回来应为 HATCH 类型、Polygon 几何
        Coordinate[] coords = {
            new Coordinate(0, 0), new Coordinate(10, 0),
            new Coordinate(10, 10), new Coordinate(0, 10), new Coordinate(0, 0)
        };
        Polygon poly = GF.createPolygon(coords);
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.HATCH)
                .layer("填充").geometry(poly)
                .property(EntityProperty.HATCH_PATTERN, "SOLID")
                .build());

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        String dxf = sw.toString();

        assertThat(dxf).contains("HATCH");
        assertThat(dxf).contains("SOLID");

        // 解析回来应为 HATCH 实体，几何为 Polygon
        ParseResult result = new CADParser(
            ParseConfig.builder().applyUnitConversion(false).build())
            .parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        CADEntity e = result.getEntities().get(0);
        assertThat(e.getType()).isEqualTo("HATCH");
        assertThat(e.geometry()).isInstanceOf(Polygon.class);
    }

    @Test
    void dxfWriter_hatch_withHole_roundTrip() throws Exception {
        // HATCH 含洞：解析回来的 Polygon 应有 1 个内环
        Coordinate[] outer = {
            new Coordinate(0, 0), new Coordinate(20, 0),
            new Coordinate(20, 20), new Coordinate(0, 20), new Coordinate(0, 0)
        };
        Coordinate[] hole = {
            new Coordinate(5, 5), new Coordinate(15, 5),
            new Coordinate(15, 15), new Coordinate(5, 15), new Coordinate(5, 5)
        };
        Polygon poly = GF.createPolygon(GF.createLinearRing(outer),
                                         new LinearRing[]{GF.createLinearRing(hole)});
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.HATCH).layer("填充").geometry(poly).build());

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);

        ParseResult result = new CADParser(
            ParseConfig.builder().applyUnitConversion(false).build())
            .parse(new StringReader(sw.toString()));
        assertThat(result.getEntities()).hasSize(1);
        Polygon parsed = (Polygon) result.getEntities().get(0).geometry();
        assertThat(parsed.getNumInteriorRing()).isEqualTo(1);
    }

    @Test
    void dxfWriter_arc_angleRoundTrip() throws Exception {
        // ARC 写出：DXF 中应包含正确的起终角度值
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.ARC)
                .layer("弧形")
                .geometry(GF.createPoint(new Coordinate(0, 0)))
                .property(EntityProperty.RADIUS,      10.0)
                .property(EntityProperty.START_ANGLE, 45.0)
                .property(EntityProperty.END_ANGLE,   225.0)
                .build());

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        String dxf = sw.toString();

        assertThat(dxf).contains("ARC");
        assertThat(dxf).contains("45.0000");  // startAngle
        assertThat(dxf).contains("225.0000"); // endAngle
        assertThat(dxf).contains("10.0000");  // radius
    }

    @Test
    void dxfWriter_mtext_roundTrip() throws Exception {
        // MTEXT 写出后解析回读，文字内容应保留
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.MTEXT)
                .layer("注记")
                .geometry(GF.createPoint(new Coordinate(10, 10)))
                .property(EntityProperty.TEXT, "测试文字\\P第二行")
                .property(EntityProperty.HEIGHT, 3.0)
                .build());

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        String dxf = sw.toString();

        assertThat(dxf).contains("MTEXT");
        assertThat(dxf).contains("测试文字");

        ParseResult result = new CADParser(
            ParseConfig.builder().applyUnitConversion(false).build())
            .parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        CADEntity e = result.getEntities().get(0);
        assertThat(e.getType()).isEqualTo("MTEXT");
        assertThat(e.getProperties().get(EntityProperty.TEXT)).asString().contains("测试文字");
    }

    @Test
    void dxfWriter_insertWithBlock_r2007_roundTrip() throws Exception {
        // INSERT + 块定义写出：解析回来块内实体应能展开
        CADBlock block = new CADBlock("CROSS");
        block.setInsertionPoint(0, 0, 0);
        block.addEntity(CADEntity.builder(CADEntity.Types.LINE)
            .layer("0")
            .geometry(GF.createLineString(new Coordinate[]{
                new Coordinate(-1, 0), new Coordinate(1, 0)}))
            .build());

        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.INSERT)
                .layer("符号")
                .geometry(GF.createPoint(new Coordinate(50, 50)))
                .property(EntityProperty.BLOCK_NAME, "CROSS")
                .build());

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build())
            .write(List.of(block), entities, sw);
        String dxf = sw.toString();

        assertThat(dxf).contains("INSERT");
        assertThat(dxf).contains("CROSS");
        // 解析时 INSERT 应被展开为块内的 LINE 实体
        ParseResult result = new CADParser(
            ParseConfig.builder().applyUnitConversion(false).build())
            .parse(new StringReader(dxf));
        assertThat(result.getEntities()).isNotEmpty();
        assertThat(result.getEntities().stream()
            .anyMatch(e -> "LINE".equals(e.getType()))).isTrue();
    }

    @Test
    void dxfWriter_write_nullArguments_shouldThrow() {
        DXFWriter writer = new DXFWriter();
        assertThatThrownBy(() -> writer.write((List<CADEntity>) null, new StringWriter()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> writer.write(List.of(), (List<CADEntity>) null, new StringWriter()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void dxfWriter_insertWithBlock_shouldContainBlockAndInsert() throws Exception {
        // 块定义 + INSERT：BLOCKS 段应含块定义，ENTITIES 段应含 INSERT
        CADBlock block = new CADBlock("SYM");
        block.setInsertionPoint(0, 0, 0);
        block.addEntity(CADEntity.builder(CADEntity.Types.LINE)
            .layer("0")
            .geometry(GF.createLineString(new Coordinate[]{
                new Coordinate(-1, 0), new Coordinate(1, 0)}))
            .build());

        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.INSERT)
                .layer("符号")
                .geometry(GF.createPoint(new Coordinate(50, 50)))
                .property(EntityProperty.BLOCK_NAME, "SYM")
                .build());

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build())
            .write(List.of(block), entities, sw);
        String dxf = sw.toString();

        assertThat(dxf).contains("BLOCK");
        assertThat(dxf).contains("SYM");
        assertThat(dxf).contains("INSERT");
        // BLOCKS 段必须在 ENTITIES 段之前
        assertThat(dxf.indexOf("SECTION\r\n  2\r\nBLOCKS"))
            .isLessThan(dxf.indexOf("SECTION\r\n  2\r\nENTITIES"));
    }

    @Test
    void performanceBaseline_5000Lines_shouldParseWithin5Seconds() throws Exception {
        // 5000 条 LINE 实体的内存解析基线；真实文件测试需外部夹具
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("  0\nSECTION\n  2\nENTITIES\n");
        for (int i = 0; i < 5000; i++) {
            sb.append("  0\nLINE\n  8\n0\n")
                    .append(" 10\n").append(i).append(".0\n")
                    .append(" 20\n0.0\n 30\n0.0\n")
                    .append(" 11\n").append(i + 1).append(".0\n")
                    .append(" 21\n0.0\n 31\n0.0\n");
        }
        sb.append("  0\nENDSEC\n  0\nEOF\n");

        long start = System.currentTimeMillis();
        ParseResult result = new CADParser().parse(new StringReader(sb.toString()));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.getEntities()).hasSize(5000);
        assertThat(elapsed)
                .as("5000 LINE 实体应在 5000ms 内解析完毕，实际耗时: " + elapsed + "ms")
                .isLessThan(5000L);
    }

    public static void main(String[] args) throws IOException, URISyntaxException {
        ParseConfig config = ParseConfig.builder()
                .crs("EPSG:4545")          // CGCS2000 / 3度带 117°E
                .arcTolerance(0.001)       // 弧线离散精度（米）
                .coordinateDecimalPlaces(4)// 坐标小数位数
                .build();
        // 从 classpath 加载 DXF 文件（自动检测 GBK / UTF-8 编码）
        String resourceName = "/城建.dxf";
        java.net.URL resourceUrl = CADParserTest.class.getResource(resourceName);
        if (resourceUrl == null) {
            System.err.println("错误：找不到资源文件 " + resourceName);
            System.err.println("请确保文件位于 src/test/resources 目录下");
            return;
        }
        ParseResult result = new CADParser(config).parse(java.nio.file.Paths.get(resourceUrl.toURI()));

// 遍历实体
        for (CADEntity entity : result.getEntities()) {
            System.out.println(entity.getType() + " 图层=" + entity.getLayer());
            System.out.println("  几何=" + entity.geometry());
        }

// 查看错误与统计
        result.getErrors().forEach(System.err::println);
        System.out.println(result.getStats());

        GeoJsonSerializer ser = new GeoJsonSerializer(result.getMetadata());
        String geojson = ser.serialize(result.getEntities(), result.getMetadata());
        Files.writeString(Paths.get("output.geojson"), geojson);
    }
}
