package com.nanodxf;

import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.filter.PaperSpaceFilter;
import com.nanodxf.geometry.AciColorTable;
import com.nanodxf.layer.LineTypeDef;
import com.nanodxf.output.CrsRegistry;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Polygon;

import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.7.2 — 编码覆盖 API、LTYPE 图案解析、CRS 注册表、几何容差常量，
 * 以及此前缺失覆盖的 ACI 色表 / 图纸空间过滤器 / 配置边界校验。
 */
class V172Test extends NanoDxfTestBase {

    private static final String SIMPLE_LINE_DXF =
            "  0\nSECTION\n  2\nHEADER\n  9\n$ACADVER\n  1\nAC1015\n  0\nENDSEC\n" +
            "  0\nSECTION\n  2\nENTITIES\n" +
            "  0\nLINE\n  8\n道路\n 10\n100\n 20\n200\n 30\n0\n 11\n150\n 21\n200\n 31\n0\n" +
            "  0\nENDSEC\n  0\nEOF\n";

    // =========================================================================
    // 编码覆盖 API
    // =========================================================================

    @Test
    void parsePath_forcedEncoding_shouldDecodeGbkLayerName() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "enc_");
        try {
            Path dxf = tmp.resolve("gbk.dxf");
            Files.write(dxf, SIMPLE_LINE_DXF.getBytes(Charset.forName("GBK")));

            ParseResult result = new CADParser(
                    ParseConfig.builder().encoding("GBK").build()).parse(dxf);

            assertThat(result.getEntities()).hasSize(1);
            assertThat(result.getEntities().get(0).getLayer()).isEqualTo("道路");
        } finally {
            deleteDir(tmp);
        }
    }

    @Test
    void parsePath_forcedWrongEncoding_shouldProduceGarbledLayerName() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "enc_");
        try {
            Path dxf = tmp.resolve("gbk.dxf");
            Files.write(dxf, SIMPLE_LINE_DXF.getBytes(Charset.forName("GBK")));

            ParseResult result = new CADParser(
                    ParseConfig.builder().encoding(StandardCharsets.ISO_8859_1).build()).parse(dxf);

            assertThat(result.getEntities()).hasSize(1);
            assertThat(result.getEntities().get(0).getLayer()).isNotEqualTo("道路");
        } finally {
            deleteDir(tmp);
        }
    }

    @Test
    void parsePath_withoutForcedEncoding_shouldStillAutoDetect() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "enc_");
        try {
            Path dxf = tmp.resolve("auto.dxf");
            Files.write(dxf, SIMPLE_LINE_DXF.getBytes(Charset.forName("GBK")));

            ParseResult result = new CADParser().parse(dxf);

            assertThat(result.getEntities()).as("自动检测链路不应被编码覆盖改动破坏").hasSize(1);
        } finally {
            deleteDir(tmp);
        }
    }

    @Test
    void parseConfig_encoding_shouldValidateAndExposeValue() {
        assertThat(ParseConfig.defaults().getEncoding()).isNull();
        assertThat(ParseConfig.builder().encoding("utf-8").build().getEncoding())
                .isEqualTo(StandardCharsets.UTF_8);
        assertThat(ParseConfig.builder().encoding(Charset.forName("GB18030")).build().getEncoding())
                .isEqualTo(Charset.forName("GB18030"));

        assertThatThrownBy(() -> ParseConfig.builder().encoding((String) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParseConfig.builder().encoding("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseConfig_proximityThreshold_negative_shouldThrow() {
        assertThatThrownBy(() -> ParseConfig.builder().proximityThreshold(-0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // LTYPE 图案解析
    // =========================================================================

    @Test
    void ltypeTable_shouldParsePatternLengthAndElements() throws Exception {
        String dxf =
                "  0\nSECTION\n  2\nTABLES\n" +
                "  0\nTABLE\n  2\nLTYPE\n 70\n1\n" +
                "  0\nLTYPE\n  2\nDASHED\n  3\nDashed __ __ __\n 72\n65\n 73\n2\n 40\n12.7\n" +
                " 49\n5.4\n 49\n-2.7\n" +
                "  0\nENDTAB\n  0\nENDSEC\n" +
                "  0\nSECTION\n  2\nENTITIES\n  0\nENDSEC\n  0\nEOF\n";

        ParseResult result = new CADParser().parse(new StringReader(dxf));
        LineTypeDef lt = result.getLineTypes().get("DASHED");

        assertThat(lt).isNotNull();
        assertThat(lt.getDescription()).isEqualTo("Dashed __ __ __");
        assertThat(lt.getPatternLength()).isEqualTo(12.7);
        assertThat(lt.getPatternElements()).containsExactly(5.4, -2.7);
    }

    // =========================================================================
    // HATCH 样条边界边（v1.7.1 补全）
    // =========================================================================

    @Test
    void hatch_splineEdge_shouldProducePolygon() throws Exception {
        CADEntity e = single(entities(
                "  0\nHATCH\n  8\n填充\n" +
                " 91\n1\n" +
                " 92\n1\n 93\n1\n" +
                " 72\n4\n 94\n2\n 73\n0\n 74\n0\n 95\n3\n 96\n6\n" +
                " 40\n0\n 40\n0\n 40\n0\n 40\n1\n 40\n1\n 40\n1\n" +
                " 10\n0\n 20\n0\n 10\n5\n 20\n10\n 10\n10\n 20\n0\n"));

        assertThat(e.getType()).isEqualTo("HATCH");
        assertThat(e.geometry()).isInstanceOf(Polygon.class);
        assertThat(e.geometry().getArea()).isGreaterThan(0);
    }

    // =========================================================================
    // CRS 注册表
    // =========================================================================

    @Test
    void crsRegistry_shouldSeparateEsriAndOgcDialects() {
        assertThat(CrsRegistry.esriWkt(4490)).contains("GCS_China_Geodetic_Coordinate_System_2000");
        assertThat(CrsRegistry.ogcWkt(4490)).contains("China Geodetic Coordinate System 2000");

        assertThat(CrsRegistry.esriWkt(4326)).contains("D_WGS_1984");
        assertThat(CrsRegistry.ogcWkt(4326)).contains("WGS_1984");

        assertThat(CrsRegistry.esriWkt(4545)).contains("Central_Meridian\",108.0");
        assertThat(CrsRegistry.ogcWkt(4545)).contains("central_meridian\",108");

        assertThat(CrsRegistry.ogcWkt(3857)).contains("Pseudo-Mercator");
        assertThat(CrsRegistry.esriWkt(3857)).as("3857 仅提供 OGC 方言").isNull();
    }

    @Test
    void crsRegistry_parseEpsg_shouldHandleValidAndInvalidInput() {
        assertThat(CrsRegistry.parseEpsg("EPSG:4534")).isEqualTo(4534);
        assertThat(CrsRegistry.parseEpsg("  epsg:4490  ")).isEqualTo(4490);
        assertThat(CrsRegistry.parseEpsg("EPSG:abc")).isZero();
        assertThat(CrsRegistry.parseEpsg("CGCS2000")).isZero();
        assertThat(CrsRegistry.parseEpsg(null)).isZero();
    }

    @Test
    void crsRegistry_esriWktFor_shouldPassThroughUserWktOrReturnNull() {
        String userWkt = "PROJCS[\"Custom\",GEOGCS[\"WGS 84\"]]";
        assertThat(CrsRegistry.esriWktFor(userWkt)).isEqualTo(userWkt);
        assertThat(CrsRegistry.esriWktFor("EPSG:32650")).contains("WGS_1984_UTM_Zone_50N");
        assertThat(CrsRegistry.esriWktFor("EPSG:99999")).isNull();
        assertThat(CrsRegistry.esriWktFor(null)).isNull();
        assertThat(CrsRegistry.esriWktFor("  ")).isNull();
    }

    // =========================================================================
    // ACI 色表
    // =========================================================================

    @Test
    void aciColorTable_standardColors_shouldMatchCadPalette() {
        assertThat(AciColorTable.toRgb(1)).containsExactly(255, 0, 0);
        assertThat(AciColorTable.toRgb(3)).containsExactly(0, 255, 0);
        assertThat(AciColorTable.toRgb(5)).containsExactly(0, 0, 255);
        assertThat(AciColorTable.toRgb(7)).containsExactly(255, 255, 255);
        assertThat(AciColorTable.toRgb(8)).containsExactly(128, 128, 128);
        assertThat(AciColorTable.toRgb(9)).containsExactly(192, 192, 192);
    }

    @Test
    void aciColorTable_extendedPalette_shouldEncodeHueAndValue() {
        assertThat(AciColorTable.toRgb(10)).containsExactly(255, 0, 0);   // 0° 全亮
        assertThat(AciColorTable.toRgb(30)).containsExactly(255, 128, 0); // 30° 全亮
        assertThat(AciColorTable.toRgb(19)).containsExactly(38, 0, 0);    // 0° 最低明度
    }

    @Test
    void aciColorTable_specialCodes_shouldReturnNull() {
        assertThat(AciColorTable.toRgb(0)).isNull();     // BYBLOCK
        assertThat(AciColorTable.toRgb(256)).isNull();   // BYLAYER
        assertThat(AciColorTable.toRgb(257)).isNull();   // 越界
        assertThat(AciColorTable.toRgb(-1)).isNull();
    }

    // =========================================================================
    // 图纸空间过滤器
    // =========================================================================

    @Test
    void paperSpaceFilter_shouldRespectCode67() {
        EntityBuffer absent = new EntityBuffer();
        EntityBuffer model   = new EntityBuffer();
        EntityBuffer paper   = new EntityBuffer();
        model.add(new GroupCodePair(67, "0"));
        paper.add(new GroupCodePair(67, "1"));

        assertThat(PaperSpaceFilter.isModelSpace(absent)).as("code 67 缺失默认模型空间").isTrue();
        assertThat(PaperSpaceFilter.isModelSpace(model)).isTrue();
        assertThat(PaperSpaceFilter.isModelSpace(paper)).isFalse();
    }
}
