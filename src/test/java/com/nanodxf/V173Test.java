package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.geometry.Discretizer;
import com.nanodxf.output.DXFWriter;
import com.nanodxf.output.GeoJsonSerializer;
import com.nanodxf.output.ShapefileWriter;
import com.nanodxf.output.SvgWriter;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.7.3 — 解析容错隔离、输出 Locale 无关、DBF 多字节截断与 .cpg、圆弧采样封顶。
 */
class V173Test extends NanoDxfTestBase {

    private static final String VALID_LINE =
            "  0\nLINE\n  8\n道路\n 10\n100\n 20\n200\n 30\n0\n 11\n150\n 21\n200\n 31\n0\n";

    // =========================================================================
    // 实体级容错
    // =========================================================================

    @Test
    void corruptLwPolyline_shouldBeSkippedWithWarn_notAbortParse() throws Exception {
        String badEntity =
                "  0\nLWPOLYLINE\n  8\n0\n 90\n2\n 70\n0\n" +
                " 10\nabc\n 20\n0\n 10\n10\n 20\n0\n";   // code 10 非数值

        ParseResult result = new CADParser().parse(new StringReader(entities(badEntity, VALID_LINE)));

        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).getType()).isEqualTo("LINE");
        assertThat(result.getErrors())
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(ParseErrorLevel.WARN);
                    assertThat(e.getEntityType()).isEqualTo("LWPOLYLINE");
                    assertThat(e.getMessage()).contains("实体解析失败");
                });
    }

    // =========================================================================
    // 段级容错
    // =========================================================================

    @Test
    void corruptHeaderVariable_shouldFallBackToDefault() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nHEADER\n  9\n$INSUNITS\n 70\nabc\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" + VALID_LINE + "  0\nENDSEC\n  0\nEOF\n";

        ParseResult result = new CADParser().parse(new StringReader(dxf));

        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getErrors())
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(ParseErrorLevel.WARN);
                    assertThat(e.getEntityType()).isEqualTo("HEADER");
                    assertThat(e.getMessage()).contains("$INSUNITS");
                });
    }

    @Test
    void corruptTablesSection_shouldNotLoseEntitiesInLaterSection() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nTABLES\n" +
                "  0\nTABLE\n  2\nLAYER\n 70\n1\n" +
                "  0\nLAYER\n  2\nbad\n 62\nabc\n" +      // 颜色号非数值
                "  0\nENDTAB\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n" + VALID_LINE + "  0\nENDSEC\n  0\nEOF\n";

        ParseResult result = new CADParser().parse(new StringReader(dxf));

        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getErrors())
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(ParseErrorLevel.WARN);
                    assertThat(e.getEntityType()).isEqualTo("TABLES");
                    assertThat(e.getMessage()).contains("段解析失败");
                });
    }

    // =========================================================================
    // Locale 无关的输出
    // =========================================================================

    @Test
    void outputs_shouldUseDotDecimalSeparator_underGermanLocale() throws Exception {
        Locale old = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            CADEntity line = CADEntity.builder("LINE").handle("1").layer("0")
                    .geometry(GF.createLineString(new Coordinate[]{
                            new Coordinate(1234.5, 5678.5),
                            new Coordinate(1300.25, 5700.75)}))
                    .build();

            String geojson = new GeoJsonSerializer(4).serialize(List.of(line), null);
            assertThat(geojson).contains("1234.5000").doesNotContain("1234,5");

            StringWriter dxf = new StringWriter();
            new DXFWriter().write(List.of(line), dxf);
            assertThat(dxf.toString()).contains("1234.5000").doesNotContain("1234,5");

            String svg = new SvgWriter().serialize(List.of(line));
            assertThat(svg).contains("1234.500000").doesNotContain("1234,5");
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test
    void geojson_nonFiniteCoordinate_shouldEmitNull_notInvalidJson() {
        CADEntity nan = CADEntity.builder("POINT").handle("1").layer("0")
                .geometry(GF.createPoint(new Coordinate(Double.NaN, 1.0)))
                .build();

        String geojson = new GeoJsonSerializer(4).serialize(List.of(nan), null);

        assertThat(geojson).contains("[null,1.0000]").doesNotContain("NaN");
    }

    // =========================================================================
    // Shapefile：DBF 多字节截断 + .cpg
    // =========================================================================

    @Test
    void dbf_multibyteText_shouldTruncateOnCharBoundary_andWriteCpg() throws Exception {
        Locale old = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        Path tmp = Files.createTempDirectory(Paths.get("target"), "dbf_");
        try {
            String text = "测".repeat(130); // GBK 260 字节 > 254，必然截断
            CADEntity e = CADEntity.builder("TEXT").handle("1").layer("图层")
                    .geometry(GF.createPoint(new Coordinate(0, 0)))
                    .property("text", text)
                    .property("elevation", 1234.5678)
                    .build();

            Path shp = tmp.resolve("out.shp");
            new ShapefileWriter().write(List.of(e), shp);

            byte[] dbf = Files.readAllBytes(tmp.resolve("out.dbf"));
            int headerSize = ByteBuffer.wrap(dbf, 8, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
            byte[] textField = new byte[254];
            System.arraycopy(dbf, headerSize + 1 + 64 + 16, textField, 0, 254);

            String decoded = new String(textField, Charset.forName("GBK")).stripTrailing();
            assertThat(decoded).isEqualTo("测".repeat(127)); // 254 字节 = 127 个汉字，无半个字符

            String cpg = Files.readString(tmp.resolve("out.cpg")).trim();
            assertThat(cpg).isEqualTo("GBK");

            // ELEVATION 为最后一个 N10.4 字段：德语区域下仍必须是 "1234.5678"
            byte[] elevField = new byte[10];
            System.arraycopy(dbf, headerSize + 1 + 64 + 16 + 254 + 32 + 64 + 4, elevField, 0, 10);
            assertThat(new String(elevField, Charset.forName("US-ASCII"))).contains("1234.5678");
        } finally {
            Locale.setDefault(old);
            deleteDir(tmp);
        }
    }

    // =========================================================================
    // 圆弧采样上限
    // =========================================================================

    @Test
    void arc_hugeRadiusTinyTolerance_shouldBeCapped() {
        List<Coordinate> pts = Discretizer.arc(new double[]{0, 0}, 1e9, 0, 90, 1e-6);
        assertThat(pts).hasSizeLessThanOrEqualTo(2001);
        assertThat(pts).hasSizeGreaterThanOrEqualTo(9);
    }

    @Test
    void arc_toleranceGreaterThanRadius_shouldNotProduceNaNPoints() {
        List<Coordinate> pts = Discretizer.arc(new double[]{0, 0}, 10, 0, 90, 1000);
        assertThat(pts).hasSize(9); // 退化为最少 8 段
        for (Coordinate c : pts) {
            assertThat(c.x).isFinite();
            assertThat(c.y).isFinite();
        }
    }

    @Test
    void arc_zeroRadius_shouldCollapseToCenterPoint() {
        List<Coordinate> pts = Discretizer.arc(new double[]{3, 4}, 0, 0, 90, 0.01);
        assertThat(pts).hasSize(1);
        assertThat(pts.get(0).x).isEqualTo(3.0);
        assertThat(pts.get(0).y).isEqualTo(4.0);
    }
}
