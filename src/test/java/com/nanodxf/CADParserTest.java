package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.DXFVersion;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CADParser 功能验证。
 *
 * <p>测试覆盖：ParseConfig 参数校验、HEADER 解析、LINE/CIRCLE/ARC/POINT/TEXT/LWPOLYLINE 实体解析。
 */
class CADParserTest {

    /** 最简合法 DXF：仅含 HEADER 段，声明版本 R2000（AC1015）+ 毫米单位。 */
    private static final String MINIMAL_DXF =
        "  0\nSECTION\n  2\nHEADER\n" +
        "  9\n$ACADVER\n  1\nAC1015\n" +
        "  9\n$INSUNITS\n 70\n4\n" +
        "  0\nENDSEC\n  0\nEOF\n";

    /** 含 LINE 实体的最简 DXF。 */
    private static final String LINE_DXF =
        "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
        "  0\nSECTION\n  2\nENTITIES\n" +
        "  0\nLINE\n" +
        "  8\n道路\n" +
        " 10\n100.0\n 20\n200.0\n 30\n0.0\n" +
        " 11\n150.0\n 21\n200.0\n 31\n0.0\n" +
        "  0\nENDSEC\n  0\nEOF\n";

    /** 含 CIRCLE 实体的最简 DXF（半径 25，圆心 (50,50)）。 */
    private static final String CIRCLE_DXF =
        "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
        "  0\nSECTION\n  2\nENTITIES\n" +
        "  0\nCIRCLE\n" +
        "  8\n0\n" +
        " 10\n50.0\n 20\n50.0\n 30\n0.0\n" +
        " 40\n25.0\n" +
        "  0\nENDSEC\n  0\nEOF\n";

    /** 含 POINT 实体（高程 125.3）。 */
    private static final String POINT_DXF =
        "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
        "  0\nSECTION\n  2\nENTITIES\n" +
        "  0\nPOINT\n" +
        "  8\n高程点\n" +
        " 10\n1000.0\n 20\n2000.0\n 30\n125.3\n" +
        "  0\nENDSEC\n  0\nEOF\n";

    /** 含 TEXT 实体。 */
    private static final String TEXT_DXF =
        "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
        "  0\nSECTION\n  2\nENTITIES\n" +
        "  0\nTEXT\n" +
        "  8\n注记\n" +
        " 10\n300.0\n 20\n400.0\n 30\n0.0\n" +
        " 40\n3.0\n" +
        "  1\n道路名称\n" +
        "  0\nENDSEC\n  0\nEOF\n";

    /** 含 LWPOLYLINE（4 顶点，闭合，elevation=125.3）。 */
    private static final String LWPOLY_DXF =
        "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n" +
        "  0\nSECTION\n  2\nENTITIES\n" +
        "  0\nLWPOLYLINE\n" +
        "  8\n等高线\n" +
        " 38\n125.3\n" +     // elevation
        " 70\n1\n" +          // closed
        " 10\n0.0\n 20\n0.0\n" +
        " 10\n100.0\n 20\n0.0\n" +
        " 10\n100.0\n 20\n100.0\n" +
        " 10\n0.0\n 20\n100.0\n" +
        "  0\nENDSEC\n  0\nEOF\n";

    // -------------------------------------------------------------------------
    // HEADER 和 ParseConfig 测试
    // -------------------------------------------------------------------------

    @Test
    void parseMinimalDxf_shouldExtractVersionAndUnits() throws Exception {
        ParseResult result = new CADParser(
                ParseConfig.builder().crs("EPSG:4545").build()
        ).parse(new StringReader(MINIMAL_DXF));

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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arcTolerance");
    }

    @Test
    void parseConfig_coordinateDecimalPlacesMustBeInRange() {
        assertThatThrownBy(() -> ParseConfig.builder().coordinateDecimalPlaces(16).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coordinateDecimalPlaces");
    }

    // -------------------------------------------------------------------------
    // 实体解析测试
    // -------------------------------------------------------------------------

    @Test
    void parseLine_shouldReturnLineStringWithCorrectCoordinates() throws Exception {
        ParseResult result = new CADParser().parse(new StringReader(LINE_DXF));

        assertThat(result.getEntities()).hasSize(1);
        CADEntity entity = result.getEntities().get(0);
        assertThat(entity.getType()).isEqualTo("LINE");
        assertThat(entity.getLayer()).isEqualTo("道路");
        assertThat(entity.geometry()).isInstanceOf(LineString.class);

        LineString ls = (LineString) entity.geometry();
        assertThat(ls.getStartPoint().getX()).isEqualTo(100.0);
        assertThat(ls.getStartPoint().getY()).isEqualTo(200.0);
        assertThat(ls.getEndPoint().getX()).isEqualTo(150.0);
    }

    @Test
    void parseCircle_shouldReturnClosedLinearRing() throws Exception {
        ParseResult result = new CADParser().parse(new StringReader(CIRCLE_DXF));

        assertThat(result.getEntities()).hasSize(1);
        CADEntity entity = result.getEntities().get(0);
        assertThat(entity.getType()).isEqualTo("CIRCLE");
        assertThat(entity.geometry()).isInstanceOf(LinearRing.class);

        LinearRing ring = (LinearRing) entity.geometry();
        // 首尾坐标必须完全相同
        assertThat(ring.getCoordinateN(0)).isEqualTo(ring.getCoordinateN(ring.getNumPoints() - 1));
        // 几何中心附近（由于离散化，不用精确相等）
        assertThat(ring.getEnvelopeInternal().centre().x).isCloseTo(50.0, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void parsePoint_shouldReturnPoint3DWithElevationProperty() throws Exception {
        ParseResult result = new CADParser().parse(new StringReader(POINT_DXF));

        assertThat(result.getEntities()).hasSize(1);
        CADEntity entity = result.getEntities().get(0);
        assertThat(entity.getType()).isEqualTo("POINT");
        assertThat(entity.getLayer()).isEqualTo("高程点");
        assertThat(entity.geometry()).isInstanceOf(Point.class);

        Point p = (Point) entity.geometry();
        assertThat(p.getX()).isEqualTo(1000.0);
        assertThat(p.getY()).isEqualTo(2000.0);
        assertThat(p.getCoordinate().getZ()).isEqualTo(125.3);
        // elevation 也要存在属性中
        assertThat(entity.getProperties().get("elevation")).isEqualTo(125.3);
    }

    @Test
    void parseText_shouldReturnPointWithTextProperty() throws Exception {
        ParseResult result = new CADParser().parse(new StringReader(TEXT_DXF));

        assertThat(result.getEntities()).hasSize(1);
        CADEntity entity = result.getEntities().get(0);
        assertThat(entity.getType()).isEqualTo("TEXT");
        assertThat(entity.geometry()).isInstanceOf(Point.class);
        assertThat(entity.getProperties().get("text")).isEqualTo("道路名称");
        assertThat(entity.getProperties().get("height")).isEqualTo(3.0);
    }

    @Test
    void parseLWPolyline_closedWithElevation_shouldReturnLinearRingWithElevationProperty()
            throws Exception {
        ParseResult result = new CADParser().parse(new StringReader(LWPOLY_DXF));

        assertThat(result.getEntities()).hasSize(1);
        CADEntity entity = result.getEntities().get(0);
        assertThat(entity.getType()).isEqualTo("LWPOLYLINE");
        assertThat(entity.getLayer()).isEqualTo("等高线");
        assertThat(entity.geometry()).isInstanceOf(LinearRing.class);
        // elevation 存入属性
        assertThat(entity.getProperties().get("elevation")).isEqualTo(125.3);

        // 闭合：首尾坐标相同
        LinearRing ring = (LinearRing) entity.geometry();
        assertThat(ring.getCoordinateN(0)).isEqualTo(ring.getCoordinateN(ring.getNumPoints() - 1));
        // 所有点的 Z 必须是 elevation
        for (org.locationtech.jts.geom.Coordinate c : ring.getCoordinates()) {
            assertThat(c.getZ()).isEqualTo(125.3);
        }
    }

    @Test
    void parseStats_elapsedTimeShouldBeNonNegative() throws Exception {
        ParseResult result = new CADParser().parse(new StringReader(LINE_DXF));
        assertThat(result.getStats().parseMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.getStats().entityCount()).isEqualTo(1);
    }
}
