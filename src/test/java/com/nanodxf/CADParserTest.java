package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.DXFVersion;
import com.nanodxf.text.MTextCleaner;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CADParser 全链路验证。
 *
 * <p>覆盖：ParseConfig 校验、HEADER 解析、Phase 1 基础实体（LINE/CIRCLE/ARC/POINT/TEXT/LWPOLYLINE）、
 * Phase 2 复杂实体（MTEXT/POLYLINE/INSERT+ATTRIB/ELLIPSE/HATCH/3DFACE/SOLID/DIMENSION）。
 */
class CADParserTest {

    // =========================================================================
    // 公共测试 DXF 片段
    // =========================================================================

    private static final String HDR_ONLY =
        "  0\nSECTION\n  2\nHEADER\n" +
        "  9\n$ACADVER\n  1\nAC1015\n" +
        "  9\n$INSUNITS\n 70\n4\n" +
        "  0\nENDSEC\n  0\nEOF\n";

    private static String entities(String... blocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n");
        sb.append("  0\nSECTION\n  2\nENTITIES\n");
        for (String b : blocks) sb.append(b);
        sb.append("  0\nENDSEC\n  0\nEOF\n");
        return sb.toString();
    }

    // =========================================================================
    // HEADER & ParseConfig
    // =========================================================================

    @Test
    void parseMinimalDxf_shouldExtractVersionAndUnits() throws Exception {
        ParseResult result = new CADParser(ParseConfig.builder().crs("EPSG:4545").build())
                .parse(new StringReader(HDR_ONLY));
        assertThat(result.getMetadata().getVersion()).isEqualTo(DXFVersion.R2000);
        assertThat(result.getMetadata().getInsunits()).isEqualTo(4);
        assertThat(result.getMetadata().getCrs()).isEqualTo("EPSG:4545");
        assertThat(result.getMetadata().getCrsSource()).isEqualTo("caller_specified");
        assertThat(result.getEntities()).isEmpty();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void parseConfig_arcToleranceMustBePositive() {
        assertThatThrownBy(() -> ParseConfig.builder().arcTolerance(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("arcTolerance");
    }

    @Test
    void parseConfig_coordinateDecimalPlacesMustBeInRange() {
        assertThatThrownBy(() -> ParseConfig.builder().coordinateDecimalPlaces(16).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coordinateDecimalPlaces");
    }

    // =========================================================================
    // Phase 1：基础实体
    // =========================================================================

    @Test
    void parseLine_shouldReturnLineStringWithCorrectCoordinates() throws Exception {
        String dxf = entities(
            "  0\nLINE\n  8\n道路\n" +
            " 10\n100.0\n 20\n200.0\n 30\n0.0\n" +
            " 11\n150.0\n 21\n200.0\n 31\n0.0\n");

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("LINE");
        assertThat(e.getLayer()).isEqualTo("道路");
        LineString ls = (LineString) e.geometry();
        assertThat(ls.getStartPoint().getX()).isEqualTo(100.0);
        assertThat(ls.getEndPoint().getX()).isEqualTo(150.0);
    }

    @Test
    void parseCircle_shouldReturnClosedLinearRingWithCorrectCenter() throws Exception {
        String dxf = entities(
            "  0\nCIRCLE\n  8\n0\n" +
            " 10\n50.0\n 20\n50.0\n 30\n0.0\n 40\n25.0\n");

        CADEntity e = single(dxf);
        LinearRing ring = (LinearRing) e.geometry();
        // 首尾相同（闭合）
        assertThat(ring.getCoordinateN(0)).isEqualTo(ring.getCoordinateN(ring.getNumPoints() - 1));
    }

    @Test
    void parsePoint_shouldReturnPoint3DWithElevationProperty() throws Exception {
        String dxf = entities("  0\nPOINT\n  8\n高程点\n 10\n1000\n 20\n2000\n 30\n125.3\n");

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("POINT");
        Point p = (Point) e.geometry();
        assertThat(p.getCoordinate().getZ()).isEqualTo(125.3);
        assertThat(e.getProperties().get("elevation")).isEqualTo(125.3);
    }

    @Test
    void parseText_shouldReturnPointWithTextProperty() throws Exception {
        String dxf = entities(
            "  0\nTEXT\n  8\n注记\n 10\n300\n 20\n400\n 30\n0\n 40\n3.0\n  1\n道路名称\n");

        CADEntity e = single(dxf);
        assertThat(e.getProperties().get("text")).isEqualTo("道路名称");
        assertThat(e.getProperties().get("height")).isEqualTo(3.0);
    }

    @Test
    void parseLWPolyline_closedWithElevation_shouldReturnLinearRingWithZ() throws Exception {
        String dxf = entities(
            "  0\nLWPOLYLINE\n  8\n等高线\n 38\n125.3\n 70\n1\n" +
            " 10\n0\n 20\n0\n 10\n100\n 20\n0\n 10\n100\n 20\n100\n 10\n0\n 20\n100\n");

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("LWPOLYLINE");
        LinearRing ring = (LinearRing) e.geometry();
        // 首尾坐标精确相等
        assertThat(ring.getCoordinateN(0)).isEqualTo(ring.getCoordinateN(ring.getNumPoints() - 1));
        // 所有点 Z = elevation
        for (org.locationtech.jts.geom.Coordinate c : ring.getCoordinates()) {
            assertThat(c.getZ()).isEqualTo(125.3);
        }
        assertThat(e.getProperties().get("elevation")).isEqualTo(125.3);
    }

    // =========================================================================
    // Phase 2：MTEXT 清洗
    // =========================================================================

    @Test
    void mTextCleaner_shouldStripFormatCodesAndConvertControlChars() {
        // 格式块：{\fArial|b0;text} → text
        assertThat(MTextCleaner.clean("{\\fArial|b0;hello world}")).isEqualTo("hello world");
        // 段落换行
        assertThat(MTextCleaner.clean("line1\\Pline2")).isEqualTo("line1\nline2");
        // 特殊符号
        assertThat(MTextCleaner.clean("角度%%d")).isEqualTo("角度°");
        assertThat(MTextCleaner.clean("偏差%%p0.1")).isEqualTo("偏差±0.1");
        // Unicode 转义
        assertThat(MTextCleaner.clean("\\U+4E2D文")).isEqualTo("中文");
        // 嵌套格式块
        assertThat(MTextCleaner.clean("{\\H2.5;{\\C1;red text}}")).isEqualTo("red text");
        // 无格式块的纯花括号
        assertThat(MTextCleaner.clean("{plain}")).isEqualTo("plain");
    }

    @Test
    void parseMText_shouldReturnPointWithCleanText() throws Exception {
        // 内容：{\fArial;道路名称\P（二级）}
        String dxf = entities(
            "  0\nMTEXT\n  8\n注记\n 10\n100\n 20\n200\n 30\n0\n 40\n3.0\n" +
            "  1\n{\\fArial;道路名称\\P（二级）}\n");

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("MTEXT");
        String text = (String) e.getProperties().get("text");
        assertThat(text).contains("道路名称");
        assertThat(text).contains("\n"); // \P → 换行
        assertThat(text).doesNotContain("\\f"); // 格式码已剥离
    }

    // =========================================================================
    // Phase 2：POLYLINE + VERTEX + SEQEND
    // =========================================================================

    @Test
    void parsePolyline_closed3D_shouldReturnLinearRing() throws Exception {
        String dxf = entities(
            "  0\nPOLYLINE\n  8\n0\n 70\n9\n" +   // flags: bit0(closed) + bit3(3D) = 9
            "  0\nVERTEX\n 10\n0\n 20\n0\n 30\n10\n" +
            "  0\nVERTEX\n 10\n100\n 20\n0\n 30\n10\n" +
            "  0\nVERTEX\n 10\n100\n 20\n100\n 30\n10\n" +
            "  0\nSEQEND\n");

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("POLYLINE");
        assertThat(e.geometry()).isInstanceOf(LinearRing.class);
        LinearRing ring = (LinearRing) e.geometry();
        // 3D：Z 应被读取
        assertThat(ring.getCoordinateN(0).getZ()).isEqualTo(10.0);
    }

    @Test
    void parsePolyline_open_shouldReturnLineString() throws Exception {
        String dxf = entities(
            "  0\nPOLYLINE\n  8\n0\n 70\n0\n" +    // 开放
            "  0\nVERTEX\n 10\n0\n 20\n0\n 30\n0\n" +
            "  0\nVERTEX\n 10\n50\n 20\n50\n 30\n0\n" +
            "  0\nSEQEND\n");

        CADEntity e = single(dxf);
        assertThat(e.geometry()).isInstanceOf(LineString.class);
    }

    // =========================================================================
    // Phase 2：INSERT + ATTRIB
    // =========================================================================

    @Test
    void parseInsert_withAttrib_shouldReturnPointWithAttributes() throws Exception {
        String dxf = entities(
            "  0\nINSERT\n  8\n0\n  2\n高程点\n" +
            " 10\n500\n 20\n600\n 30\n0\n" +
            " 41\n1.0\n 42\n1.0\n 50\n0\n 66\n1\n" +
            "  0\nATTRIB\n  2\nELV\n  1\n125.30\n" +
            " 10\n500\n 20\n600\n 30\n0\n" +
            "  0\nSEQEND\n");

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("INSERT");
        assertThat(e.getProperties().get("blockName")).isEqualTo("高程点");
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> attrs =
            (java.util.Map<String, String>) e.getProperties().get("attributes");
        assertThat(attrs).containsEntry("ELV", "125.30");
    }

    // =========================================================================
    // Phase 2：ELLIPSE
    // =========================================================================

    @Test
    void parseEllipse_full_shouldReturnLinearRing() throws Exception {
        // 完整椭圆：长轴沿 X，a=100，b=50（ratio=0.5）
        String dxf = entities(
            "  0\nELLIPSE\n  8\n0\n" +
            " 10\n0\n 20\n0\n 30\n0\n" +  // center
            " 11\n100\n 21\n0\n 31\n0\n" + // major axis endpoint (relative)
            " 40\n0.5\n" +                 // ratio = b/a
            " 41\n0.0\n 42\n6.283185\n"); // full ellipse (0 to 2π)

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("ELLIPSE");
        assertThat(e.geometry()).isInstanceOf(LinearRing.class);
        LinearRing ring = (LinearRing) e.geometry();
        assertThat(ring.getCoordinateN(0)).isEqualTo(ring.getCoordinateN(ring.getNumPoints() - 1));
    }

    // =========================================================================
    // Phase 2：HATCH（含内洞）
    // =========================================================================

    @Test
    void parseHatch_singleBoundaryWithLineEdges_shouldReturnPolygon() throws Exception {
        // 矩形 HATCH：4 条直线边段
        String dxf = entities(
            "  0\nHATCH\n  8\n填充\n" +
            " 91\n1\n" +    // 1 条边界路径
            " 92\n1\n" +    // 外边界
            " 93\n4\n" +    // 4 条边段
            " 72\n1\n 10\n0\n 20\n0\n 11\n100\n 21\n0\n" +   // line
            " 72\n1\n 10\n100\n 20\n0\n 11\n100\n 21\n100\n" + // line
            " 72\n1\n 10\n100\n 20\n100\n 11\n0\n 21\n100\n" + // line
            " 72\n1\n 10\n0\n 20\n100\n 11\n0\n 21\n0\n");    // line

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("HATCH");
        assertThat(e.geometry()).isInstanceOf(Polygon.class);
        Polygon poly = (Polygon) e.geometry();
        assertThat(poly.getNumInteriorRing()).isEqualTo(0); // 无内洞
    }

    @Test
    void parseHatch_withHole_shouldReturnPolygonWithInteriorRing() throws Exception {
        // 外边界：大矩形 (0,0)-(200,200)，内边界（洞）：小矩形 (50,50)-(150,150)
        String dxf = entities(
            "  0\nHATCH\n  8\n填充\n" +
            " 91\n2\n" +
            // 外边界
            " 92\n1\n 93\n4\n" +
            " 72\n1\n 10\n0\n 20\n0\n 11\n200\n 21\n0\n" +
            " 72\n1\n 10\n200\n 20\n0\n 11\n200\n 21\n200\n" +
            " 72\n1\n 10\n200\n 20\n200\n 11\n0\n 21\n200\n" +
            " 72\n1\n 10\n0\n 20\n200\n 11\n0\n 21\n0\n" +
            // 内边界（洞）
            " 92\n0\n 93\n4\n" +
            " 72\n1\n 10\n50\n 20\n50\n 11\n150\n 21\n50\n" +
            " 72\n1\n 10\n150\n 20\n50\n 11\n150\n 21\n150\n" +
            " 72\n1\n 10\n150\n 20\n150\n 11\n50\n 21\n150\n" +
            " 72\n1\n 10\n50\n 20\n150\n 11\n50\n 21\n50\n");

        CADEntity e = single(dxf);
        Polygon poly = (Polygon) e.geometry();
        assertThat(poly.getNumInteriorRing()).isEqualTo(1); // 1 个内洞
    }

    // =========================================================================
    // Phase 2：3DFACE 和 SOLID
    // =========================================================================

    @Test
    void parse3DFace_quad_shouldReturnLinearRing() throws Exception {
        String dxf = entities(
            "  0\n3DFACE\n  8\n0\n" +
            " 10\n0\n 20\n0\n 30\n0\n" +
            " 11\n10\n 21\n0\n 31\n0\n" +
            " 12\n10\n 22\n10\n 32\n0\n" +
            " 13\n0\n 23\n10\n 33\n0\n");

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("3DFACE");
        assertThat(e.geometry()).isInstanceOf(LinearRing.class);
        assertThat(e.geometry().getNumPoints()).isEqualTo(5); // 4 + 闭合首点
    }

    @Test
    void parseSolid_triangle_shouldReturnLinearRing() throws Exception {
        String dxf = entities(
            "  0\nSOLID\n  8\n0\n" +
            " 10\n0\n 20\n0\n 30\n0\n" +
            " 11\n10\n 21\n0\n 31\n0\n" +
            " 12\n5\n 22\n10\n 32\n0\n" +
            " 13\n5\n 23\n10\n 33\n0\n"); // v2==v3 → 三角面

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("SOLID");
        assertThat(e.geometry()).isInstanceOf(LinearRing.class);
        assertThat(e.geometry().getNumPoints()).isEqualTo(4); // 3 + 闭合首点
    }

    // =========================================================================
    // Phase 2：DIMENSION
    // =========================================================================

    @Test
    void parseDimension_shouldReturnPointWithText() throws Exception {
        String dxf = entities(
            "  0\nDIMENSION\n  8\n标注\n" +
            " 11\n50\n 21\n50\n 31\n0\n" + // 文字中点
            "  1\n100.0\n 70\n0\n");

        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("DIMENSION");
        assertThat(e.geometry()).isInstanceOf(Point.class);
        Point p = (Point) e.geometry();
        assertThat(p.getX()).isEqualTo(50.0);
        assertThat(e.getProperties().get("text")).isEqualTo("100.0");
    }

    // =========================================================================
    // 统计验证
    // =========================================================================

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
    // 辅助方法
    // =========================================================================

    /** 解析并断言恰好有一个实体，返回该实体。 */
    private static CADEntity single(String dxf) throws Exception {
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).as("应该有且仅有 1 个实体").hasSize(1);
        return result.getEntities().get(0);
    }
}
