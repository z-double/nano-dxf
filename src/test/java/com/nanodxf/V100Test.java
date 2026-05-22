package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.geometry.AciColorTable;
import com.nanodxf.output.DXFWriteConfig;
import com.nanodxf.output.DXFWriter;
import com.nanodxf.output.GeoJsonSerializer;
import com.nanodxf.model.DXFVersion;
import com.nanodxf.xdata.FeatureCodeRegistry;
import com.nanodxf.xdata.FeatureCodeRegistry.FeatureCodeInfo;
import com.nanodxf.text.MTextCleaner;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.0.0 / v1.1 — 核心解析 + 基础 DXF 写出。
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>HEADER 解析（$ACADVER、$INSUNITS、$CRS）</li>
 *   <li>ParseConfig 校验</li>
 *   <li>基础实体：LINE / CIRCLE / POINT / LWPOLYLINE</li>
 *   <li>复杂实体：MTEXT / POLYLINE / INSERT（含展开、旋转、ATTRIB）/ HATCH（含洞）/ 3DFACE</li>
 *   <li>XDATA 地物编码提取与映射（CASS）</li>
 *   <li>FeatureCodeRegistry</li>
 *   <li>颜色富化：ACI / True Color / BYLAYER 继承</li>
 *   <li>GeoJsonSerializer（坐标精度、XDATA 序列化）</li>
 *   <li>错误收集与解析统计</li>
 *   <li>容错：截断文件、循环块引用、未知 SECTION、零长度线</li>
 *   <li>单位换算（mm → m）</li>
 *   <li>DXFWriter 基础（LINE / LWPOLYLINE / POINT / TEXT / 混合写出、编码配置）</li>
 *   <li>性能基线（5000 条 LINE < 5s）</li>
 * </ul>
 */
class V100Test extends NanoDxfTestBase {

    // =========================================================================
    // HEADER & ParseConfig
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
    // 基础实体
    // =========================================================================

    @Test
    void parseLine_shouldReturnLineStringWithCorrectCoordinates() throws Exception {
        String dxf = entities("  0\nLINE\n  8\n道路\n 10\n100\n 20\n200\n 30\n0\n 11\n150\n 21\n200\n 31\n0\n");
        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("LINE");
        assertThat(e.getLayer()).isEqualTo("道路");
        LineString ls = (LineString) e.geometry();
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
        for (Coordinate c : ring.getCoordinates()) assertThat(c.getZ()).isEqualTo(125.3);
        assertThat(e.getProperties().get("elevation")).isEqualTo(125.3);
    }

    // =========================================================================
    // 复杂实体
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

    @Test
    void parseInsert_blockExpansion_shouldTransformBlockEntities() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nBLOCKS\n" +
                "  0\nBLOCK\n  2\nSQUARE\n 70\n0\n 10\n0\n 20\n0\n 30\n0\n" +
                "  0\nLINE\n  8\n建筑\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" +
                "  0\nENDBLK\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nINSERT\n  8\n0\n  2\nSQUARE\n" +
                " 10\n100\n 20\n200\n 30\n0\n 41\n2\n 42\n2\n 50\n0\n" +
                "  0\nENDSEC\n  0\nEOF\n";
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        CADEntity e = result.getEntities().get(0);
        assertThat(e.getType()).isEqualTo("LINE");
        LineString ls = (LineString) e.geometry();
        assertThat(ls.getStartPoint().getX()).isEqualTo(100.0);
        assertThat(ls.getStartPoint().getY()).isEqualTo(200.0);
        assertThat(ls.getEndPoint().getX()).isEqualTo(120.0);
        assertThat(ls.getEndPoint().getY()).isEqualTo(200.0);
    }

    @Test
    void parseInsert_blockExpansion_withRotation90_shouldRotateCoordinates() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nBLOCKS\n" +
                "  0\nBLOCK\n  2\nROT_TEST\n 70\n0\n 10\n0\n 20\n0\n 30\n0\n" +
                "  0\nLINE\n  8\n0\n 10\n10\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" +
                "  0\nLINE\n  8\n0\n 10\n1\n 20\n0\n 30\n0\n 11\n0\n 21\n1\n 31\n0\n" +
                "  0\nENDBLK\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nINSERT\n  8\n0\n  2\nROT_TEST\n" +
                " 10\n0\n 20\n0\n 30\n0\n 41\n1\n 42\n1\n 50\n90\n" +
                "  0\nENDSEC\n  0\nEOF\n";
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        LineString ls = (LineString) result.getEntities().get(0).geometry();
        assertThat(ls.getStartPoint().getX()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ls.getStartPoint().getY()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void parseInsert_undefinedBlock_shouldFallbackToInsertPoint() throws Exception {
        CADEntity e = single(entities(
                "  0\nINSERT\n  8\n0\n  2\nUNDEFINED\n" +
                " 10\n500\n 20\n600\n 30\n0\n 41\n1\n 42\n1\n 50\n0\n"));
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
        assertThat(e.geometry().getNumPoints()).isEqualTo(5);
    }

    // =========================================================================
    // XDATA & 地物编码
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
        assertThat(FeatureCodeRegistry.lookup("99999")).isEmpty();
        assertThat(FeatureCodeRegistry.size()).isGreaterThan(200);
    }

    // =========================================================================
    // 颜色富化
    // =========================================================================

    @Test
    void aciColorTable_standardColors() {
        assertThat(AciColorTable.toRgb(1)).containsExactly(255, 0, 0);
        assertThat(AciColorTable.toRgb(2)).containsExactly(255, 255, 0);
        assertThat(AciColorTable.toRgb(3)).containsExactly(0, 255, 0);
        assertThat(AciColorTable.toRgb(256)).isNull();
        assertThat(AciColorTable.toRgb(0)).isNull();
    }

    @Test
    void parseEntityWithExplicitAciColor_shouldHaveColorRgbInProperties() throws Exception {
        String dxf = entities("  0\nLINE\n  8\n0\n 62\n1\n 10\n0\n 20\n0\n 30\n0\n 11\n1\n 21\n1\n 31\n0\n");
        CADEntity e = single(dxf);
        assertThat(e.getProperties().get("colorAci")).isEqualTo(1);
        assertThat((int[]) e.getProperties().get("colorRgb")).containsExactly(255, 0, 0);
    }

    @Test
    void parseEntityWithTrueColor_shouldOverrideAci() throws Exception {
        int orange = (255 << 16) | (128 << 8);
        String dxf = entities("  0\nLINE\n  8\n0\n 62\n1\n 420\n" + orange + "\n" +
                " 10\n0\n 20\n0\n 30\n0\n 11\n1\n 21\n1\n 31\n0\n");
        CADEntity e = single(dxf);
        assertThat((int[]) e.getProperties().get("colorRgb")).containsExactly(255, 128, 0);
        assertThat(e.getProperties()).doesNotContainKey("colorAci");
    }

    @Test
    void parseEntityByLayerColor_shouldInheritLayerAciAsRgb() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nTABLES\n" +
                "  0\nTABLE\n  2\nLAYER\n" +
                "  0\nLAYER\n  2\n道路\n 62\n1\n  6\nContinuous\n" +
                "  0\nENDTAB\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nLINE\n  8\n道路\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" +
                "  0\nENDSEC\n  0\nEOF\n";
        CADEntity e = single(dxf);
        assertThat((int[]) e.getProperties().get("colorRgb")).containsExactly(255, 0, 0);
        assertThat(e.getProperties()).doesNotContainKey("colorAci");
    }

    // =========================================================================
    // GeoJsonSerializer
    // =========================================================================

    @Test
    void geoJsonSerializer_lineEntity_shouldProduceValidGeoJson() throws Exception {
        String dxf = entities("  0\nLINE\n  8\n道路\n 10\n100\n 20\n200\n 30\n0\n 11\n150\n 21\n200\n 31\n0\n");
        ParseResult result = new CADParser(ParseConfig.builder().crs("EPSG:4545").build())
                .parse(new StringReader(dxf));
        GeoJsonSerializer ser = new GeoJsonSerializer(result.getMetadata());
        String json = ser.serialize(result.getEntities(), result.getMetadata());
        assertThat(json).startsWith("{\"type\":\"FeatureCollection\"");
        assertThat(json).contains("\"crs\"").contains("\"EPSG:4545\"")
                .contains("\"type\":\"LineString\"").contains("\"layer\":\"道路\"")
                .contains("100.0000");
    }

    @Test
    void geoJsonSerializer_coordinatePrecision_shouldRespectConfig() throws Exception {
        String dxf = entities("  0\nLINE\n  8\n0\n 10\n123456.789\n 20\n0\n 30\n0\n 11\n0\n 21\n1\n 31\n0\n");
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        String json = new GeoJsonSerializer(2).serialize(result.getEntities(), result.getMetadata());
        assertThat(json).contains("123456.79");
    }

    @Test
    void geoJsonSerializer_circleAsPolygon_shouldContainPolygonType() throws Exception {
        CADEntity e = single(entities("  0\nCIRCLE\n  8\n0\n 10\n50\n 20\n50\n 30\n0\n 40\n25\n"));
        String json = new GeoJsonSerializer(4).serialize(java.util.List.of(e), null);
        assertThat(json).contains("\"type\":\"Polygon\"");
    }

    @Test
    void geoJsonSerializer_escapeSpecialChars() {
        assertThat(GeoJsonSerializer.escapeJson("hello\"world")).isEqualTo("hello\\\"world");
        assertThat(GeoJsonSerializer.escapeJson("line1\nline2")).isEqualTo("line1\\nline2");
        assertThat(GeoJsonSerializer.escapeJson(null)).isEmpty();
    }

    @Test
    void geoJsonSerializer_xdata_shouldBeSerializedNotNull() throws Exception {
        String dxf = entities(
                "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n1\n 21\n0\n 31\n0\n" +
                "1001\nCASS\n1000\n41000\n");
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        String json = new GeoJsonSerializer(4).serialize(result.getEntities(), result.getMetadata());
        assertThat(json).contains("\"xdata\"").doesNotContain("\"xdata\":null").contains("41000");
    }

    // =========================================================================
    // 错误收集 & 解析统计
    // =========================================================================

    @Test
    void unknownEntityType_shouldBeRecordedAsInfoError() throws Exception {
        String dxf = entities("  0\nMYPRIVATE\n  8\n0\n 10\n0\n 20\n0\n");
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        boolean hasInfo = result.getErrors().stream()
                .anyMatch(err -> err.getLevel() == ParseErrorLevel.INFO
                        && "MYPRIVATE".equals(err.getEntityType()));
        assertThat(hasInfo).isTrue();
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
    // 容错（Phase 4）
    // =========================================================================

    @Test
    void truncatedFile_missingEndsecAndEof_shouldReturnParsedEntities() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n";
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).getType()).isEqualTo("LINE");
    }

    @Test
    void truncatedFile_midEntity_shouldNotThrow() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" +
                "  0\nLINE\n  8\n0\n 10\n999\n 20\n999\n";
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).isNotEmpty();
        assertThat(result.getEntities().get(0).getType()).isEqualTo("LINE");
    }

    @Test
    void circularBlockReference_shouldDetectCycleAndFallbackToPoint() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nBLOCKS\n" +
                "  0\nBLOCK\n  2\nBLK_A\n 70\n0\n 10\n0\n 20\n0\n 30\n0\n" +
                "  0\nINSERT\n  2\nBLK_B\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 41\n1\n 42\n1\n 50\n0\n" +
                "  0\nENDBLK\n" +
                "  0\nBLOCK\n  2\nBLK_B\n 70\n0\n 10\n0\n 20\n0\n 30\n0\n" +
                "  0\nINSERT\n  2\nBLK_A\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 41\n1\n 42\n1\n 50\n0\n" +
                "  0\nENDBLK\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nINSERT\n  8\n0\n  2\nBLK_A\n 10\n100\n 20\n200\n 30\n0\n 41\n1\n 42\n1\n 50\n0\n" +
                "  0\nENDSEC\n  0\nEOF\n";
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        CADEntity e = result.getEntities().get(0);
        assertThat(e.getType()).isEqualTo("INSERT");
        assertThat(((Point) e.geometry()).getX()).isEqualTo(100.0);
    }

    @Test
    void unknownSectionType_shouldBeSkippedWithoutError() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nCUSTOM\n  1\nignored data\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n" +
                "  0\nENDSEC\n  0\nEOF\n";
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void zeroLengthLine_shouldBeSkipped() throws Exception {
        String dxf = entities("  0\nLINE\n  8\n0\n 10\n100\n 20\n200\n 30\n0\n 11\n100\n 21\n200\n 31\n0\n");
        assertThat(new CADParser().parse(new StringReader(dxf)).getEntities()).isEmpty();
    }

    // =========================================================================
    // 单位换算
    // =========================================================================

    @Test
    void unitConversion_millimeterDxf_shouldScaleCoordinatesToMeters() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  9\n$INSUNITS\n 70\n4\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nLINE\n  8\n0\n 10\n1000\n 20\n2000\n 30\n0\n 11\n3000\n 21\n2000\n 31\n0\n" +
                "  0\nENDSEC\n  0\nEOF\n";
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        LineString ls = (LineString) result.getEntities().get(0).geometry();
        assertThat(ls.getStartPoint().getX()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ls.getEndPoint().getX()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void unitConversion_disabled_shouldKeepOriginalCoordinates() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  9\n$INSUNITS\n 70\n4\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" +
                "  0\nLINE\n  8\n0\n 10\n1000\n 20\n0\n 30\n0\n 11\n2000\n 21\n0\n 31\n0\n" +
                "  0\nENDSEC\n  0\nEOF\n";
        ParseConfig cfg = ParseConfig.builder().applyUnitConversion(false).build();
        LineString ls = (LineString) new CADParser(cfg).parse(new StringReader(dxf))
                .getEntities().get(0).geometry();
        assertThat(ls.getStartPoint().getX()).isEqualTo(1000.0);
    }

    // =========================================================================
    // DXFWriter — 基础写出
    // =========================================================================

    @Test
    void dxfWriter_line_roundTrip() throws Exception {
        String dxf = entities("  0\nLINE\n  8\n道路\n 10\n100\n 20\n200\n 30\n0\n 11\n150\n 21\n250\n 31\n0\n");
        List<CADEntity> src = new CADParser().parse(new StringReader(dxf)).getEntities();
        StringWriter sw = new StringWriter();
        new DXFWriter().write(src, sw);
        ParseResult result = new CADParser(ParseConfig.builder().applyUnitConversion(false).build())
                .parse(new StringReader(sw.toString()));
        assertThat(result.getEntities()).hasSize(1);
        LineString ls = (LineString) result.getEntities().get(0).geometry();
        assertThat(ls.getStartPoint().getX()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(ls.getEndPoint().getX()).isCloseTo(150.0, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void dxfWriter_lwPolyline_closedRing_roundTrip() throws Exception {
        String dxf = entities(
                "  0\nLWPOLYLINE\n  8\n等高线\n 38\n125.3\n 70\n1\n" +
                " 10\n0\n 20\n0\n 10\n100\n 20\n0\n 10\n100\n 20\n100\n 10\n0\n 20\n100\n");
        List<CADEntity> src = new CADParser().parse(new StringReader(dxf)).getEntities();
        StringWriter sw = new StringWriter();
        new DXFWriter().write(src, sw);
        ParseResult result = new CADParser(ParseConfig.builder().applyUnitConversion(false).build())
                .parse(new StringReader(sw.toString()));
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).geometry()).isInstanceOf(LinearRing.class);
        for (Coordinate c : result.getEntities().get(0).geometry().getCoordinates())
            assertThat(c.getZ()).isCloseTo(125.3, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void dxfWriter_point_withElevation_roundTrip() throws Exception {
        String dxf = entities("  0\nPOINT\n  8\n高程点\n 10\n500\n 20\n600\n 30\n88.5\n");
        List<CADEntity> src = new CADParser().parse(new StringReader(dxf)).getEntities();
        StringWriter sw = new StringWriter();
        new DXFWriter().write(src, sw);
        ParseResult result = new CADParser(ParseConfig.builder().applyUnitConversion(false).build())
                .parse(new StringReader(sw.toString()));
        Point p = (Point) result.getEntities().get(0).geometry();
        assertThat(p.getCoordinate().getZ()).isCloseTo(88.5, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void dxfWriter_text_roundTrip() throws Exception {
        String dxf = entities("  0\nTEXT\n  8\n注记\n 10\n10\n 20\n20\n 30\n0\n 40\n5\n  1\n测试文字\n");
        List<CADEntity> src = new CADParser().parse(new StringReader(dxf)).getEntities();
        StringWriter sw = new StringWriter();
        new DXFWriter().write(src, sw);
        String written = sw.toString();
        assertThat(written).contains("TEXT").contains("测试文字").contains("注记");
    }

    @Test
    void dxfWriter_multipleEntities_shouldProduceValidDxf() throws Exception {
        String dxf = entities(
                "  0\nLINE\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n",
                "  0\nPOINT\n  8\n高程点\n 10\n5\n 20\n5\n 30\n10\n",
                "  0\nLWPOLYLINE\n  8\n0\n 70\n0\n 10\n0\n 20\n0\n 10\n10\n 20\n0\n 10\n10\n 20\n10\n");
        List<CADEntity> src = new CADParser().parse(new StringReader(dxf)).getEntities();
        assertThat(src).hasSize(3);
        StringWriter sw = new StringWriter();
        new DXFWriter().write(src, sw);
        ParseResult result = new CADParser(ParseConfig.builder().applyUnitConversion(false).build())
                .parse(new StringReader(sw.toString()));
        assertThat(result.getEntities()).hasSize(3);
    }

    @Test
    void dxfWriteConfig_encodingAutoSelect_preR2007usesGbk() {
        assertThat(DXFWriteConfig.builder().version(DXFVersion.R12).build().getEncoding()).isEqualTo("GBK");
        assertThat(DXFWriteConfig.builder().version(DXFVersion.R2000).build().getEncoding()).isEqualTo("GBK");
    }

    @Test
    void dxfWriteConfig_encodingAutoSelect_r2007usesUtf8() {
        assertThat(DXFWriteConfig.builder().version(DXFVersion.R2007).build().getEncoding()).isEqualTo("UTF-8");
    }

    // =========================================================================
    // 性能基线
    // =========================================================================

    @Test
    void performanceBaseline_5000Lines_shouldParseWithin5Seconds() throws Exception {
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("  0\nSECTION\n  2\nENTITIES\n");
        for (int i = 0; i < 5000; i++) {
            sb.append("  0\nLINE\n  8\n0\n")
                    .append(" 10\n").append(i).append(".0\n 20\n0.0\n 30\n0.0\n")
                    .append(" 11\n").append(i + 1).append(".0\n 21\n0.0\n 31\n0.0\n");
        }
        sb.append("  0\nENDSEC\n  0\nEOF\n");
        long start = System.currentTimeMillis();
        ParseResult result = new CADParser().parse(new StringReader(sb.toString()));
        long elapsed = System.currentTimeMillis() - start;
        assertThat(result.getEntities()).hasSize(5000);
        assertThat(elapsed).as("5000 LINE 应在 5s 内完成，实际: " + elapsed + "ms").isLessThan(5000L);
    }
}
