package com.nanodxf;

import com.nanodxf.crs.CrsTransform;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.DrawingMetadata;
import com.nanodxf.output.CsvField;
import com.nanodxf.output.CsvWriteConfig;
import com.nanodxf.output.CsvWriter;
import com.nanodxf.stat.AreaStatRow;
import com.nanodxf.stat.AreaStats;
import com.nanodxf.xdata.FeatureCodeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * v1.9.0 — 坐标投影换算（CrsTransform）、自定义地物编码、
 * 按编码面积报表（AreaStats）、CSV ATTRIB 属性列。
 */
class V190Test extends NanoDxfTestBase {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanupCustomCodes() {
        FeatureCodeRegistry.clearCustom();
    }

    // =========================================================================
    // A. CrsTransform 投影换算
    // =========================================================================

    @Test
    void gaussKruger_roundTrip_shouldBeMillimeterAccurate() {
        double[][] cases = {{116.4, 39.9, 117}, {108.7, 30.2, 108}, {121.5, 31.2, 120}};
        for (double[] c : cases) {
            double[] en = CrsTransform.fromGeographic(c[0], c[1], c[2]);
            double[] ll = CrsTransform.toGeographic(en[0], en[1], c[2]);
            assertThat(ll[0]).as("经度往返 %s", c[0]).isCloseTo(c[0], within(1e-8));
            assertThat(ll[1]).as("纬度往返 %s", c[1]).isCloseTo(c[1], within(1e-8));
        }
    }

    @Test
    void gaussKruger_centralMeridianAndEquator() {
        double[] en = CrsTransform.fromGeographic(117, 30, 117);
        assertThat(en[0]).as("中央经线上东坐标 = 500000").isCloseTo(500000, within(1e-6));
        double[] eq = CrsTransform.fromGeographic(117.5, 0, 117);
        assertThat(eq[1]).as("赤道上北坐标 = 0").isCloseTo(0, within(1e-6));
    }

    @Test
    void epsg4545_shouldMatchExplicitCentralMeridian108() {
        double[] byEpsg = CrsTransform.fromGeographic(108.7, 30.2, "EPSG:4545");
        double[] byCm   = CrsTransform.fromGeographic(108.7, 30.2, 108.0);
        assertThat(byEpsg[0]).isCloseTo(byCm[0], within(1e-6));
        assertThat(byEpsg[1]).isCloseTo(byCm[1], within(1e-6));
    }

    @Test
    void zonePrefixAuto_3degree() {
        // 带号 36 → 3° 带中央经线 108°
        double[] auto = CrsTransform.toGeographicAuto(36_500_123, 3_320_000);
        double[] plain = CrsTransform.toGeographic(500_123, 3_320_000, 108);
        assertThat(auto[0]).isCloseTo(plain[0], within(1e-9));
        assertThat(auto[1]).isCloseTo(plain[1], within(1e-9));
    }

    @Test
    void zonePrefixAuto_6degree() {
        // 带号 19 → 6° 带中央经线 111°
        double[] auto = CrsTransform.toGeographicAuto(19_500_000, 3_320_000);
        double[] plain = CrsTransform.toGeographic(500_000, 3_320_000, 111);
        assertThat(auto[0]).isCloseTo(plain[0], within(1e-9));
        assertThat(auto[1]).isCloseTo(plain[1], within(1e-9));
    }

    @Test
    void fromGeographicAuto_roundTrip() {
        double[] en = CrsTransform.fromGeographicAuto(116.4, 39.9);
        assertThat(en[0]).as("3° 带号 39 前缀").isBetween(39_000_000.0, 40_000_000.0);
        double[] ll = CrsTransform.toGeographicAuto(en[0], en[1]);
        assertThat(ll[0]).isCloseTo(116.4, within(1e-8));
        assertThat(ll[1]).isCloseTo(39.9, within(1e-8));
    }

    @Test
    void epsgWithZonePrefix_shouldMatchAuto() {
        // EPSG:4527 = 3° 带号 39（CM 117°，含带号前缀）
        double[] byEpsg = CrsTransform.fromGeographic(116.4, 39.9, "EPSG:4527");
        double[] auto   = CrsTransform.fromGeographicAuto(116.4, 39.9);
        assertThat(byEpsg[0]).isCloseTo(auto[0], within(1e-6));
        assertThat(byEpsg[1]).isCloseTo(auto[1], within(1e-6));
    }

    @Test
    void utm_scaleFactorAtCentralMeridian() {
        // EPSG:32650 = UTM 50N（CM 117°，k0=0.9996）
        double[] utm = CrsTransform.fromGeographic(117, 30, "EPSG:32650");
        double[] gk  = CrsTransform.fromGeographic(117, 30, 117.0);
        assertThat(utm[0]).isCloseTo(500000, within(1e-6));
        assertThat(utm[1]).as("UTM 北坐标 ≈ k0 × 高斯北坐标（CGCS2000/WGS84 椭球差约 0.1mm）")
                .isCloseTo(gk[1] * 0.9996, within(1e-3));
    }

    @Test
    void webMercator_knownValuesAndRoundTrip() {
        double[] origin = CrsTransform.fromGeographic(0, 0, "EPSG:3857");
        assertThat(origin[0]).isCloseTo(0, within(1e-6));
        assertThat(origin[1]).isCloseTo(0, within(1e-6));
        double[] edge = CrsTransform.fromGeographic(180, 85.05112878, "EPSG:3857");
        assertThat(edge[0]).isCloseTo(20037508.342789244, within(0.01));
        assertThat(edge[1]).isCloseTo(20037508.342789244, within(0.01));

        double[] xy = CrsTransform.fromGeographic(116.4, 39.9, "EPSG:3857");
        double[] ll = CrsTransform.toGeographic(xy[0], xy[1], "EPSG:3857");
        assertThat(ll[0]).isCloseTo(116.4, within(1e-9));
        assertThat(ll[1]).isCloseTo(39.9, within(1e-9));
    }

    @Test
    void geographicCrs_passthrough_andUnsupportedThrows() {
        assertThat(CrsTransform.toGeographic(116.4, 39.9, "EPSG:4326"))
                .containsExactly(116.4, 39.9);
        assertThat(CrsTransform.fromGeographic(116.4, 39.9, "EPSG:4490"))
                .containsExactly(116.4, 39.9);
        assertThatThrownBy(() -> CrsTransform.toGeographic(0, 0, "EPSG:9999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 CRS");
    }

    @Test
    void transformEntities_projectedToWgs84_shouldPreserveZ() {
        // 取 4545 带内点（CM 108°，级数有效范围 ±3.5°）
        double[] en = CrsTransform.fromGeographic(108.7, 30.2, "EPSG:4545");
        CADEntity point = CADEntity.builder(CADEntity.Types.POINT).layer("0")
                .geometry(GF.createPoint(new Coordinate(en[0], en[1], 50)))
                .build();
        CADEntity line = CADEntity.builder(CADEntity.Types.LINE).layer("0")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(en[0], en[1]), new Coordinate(en[0] + 100, en[1] + 100)}))
                .build();

        List<CADEntity> out = CrsTransform.transformEntities(
                List.of(point, line), "EPSG:4545", "EPSG:4326");

        Coordinate p = out.get(0).geometry().getCoordinate();
        assertThat(p.x).isCloseTo(108.7, within(1e-8));
        assertThat(p.y).isCloseTo(30.2, within(1e-8));
        assertThat(p.getZ()).isEqualTo(50.0);
        assertThat(out.get(1).geometry().getCoordinates()).hasSize(2);
    }

    // =========================================================================
    // B. 自定义地物编码
    // =========================================================================

    @Test
    void customRegister_overrideAndClear() {
        int baseSize = FeatureCodeRegistry.size();
        FeatureCodeRegistry.register("99901", "自定义房屋", "建筑");
        assertThat(FeatureCodeRegistry.lookup("99901"))
                .hasValueSatisfying(info -> {
                    assertThat(info.name()).isEqualTo("自定义房屋");
                    assertThat(info.category()).isEqualTo("建筑");
                });
        assertThat(FeatureCodeRegistry.size()).isEqualTo(baseSize + 1);

        // 同码覆盖内置
        FeatureCodeRegistry.register("41000", "我的房屋", null);
        assertThat(FeatureCodeRegistry.lookup("41000").orElseThrow().name()).isEqualTo("我的房屋");
        assertThat(FeatureCodeRegistry.lookup("41000").orElseThrow().category()).isEqualTo("自定义");
        assertThat(FeatureCodeRegistry.size()).isEqualTo(baseSize + 1);

        FeatureCodeRegistry.clearCustom();
        assertThat(FeatureCodeRegistry.lookup("99901")).isEmpty();
        assertThat(FeatureCodeRegistry.lookup("41000").orElseThrow().name()).isEqualTo("普通房屋");
        assertThat(FeatureCodeRegistry.size()).isEqualTo(baseSize);
    }

    @Test
    void loadCsv_headerCommentAndRows() throws Exception {
        Path csv = tempDir.resolve("codes.csv");
        Files.writeString(csv, """
                # 项目专用编码
                code,name,category
                99901,自定义房屋,建筑
                99902,自定义道路

                99903,自定义水系,水系
                """);
        int loaded = FeatureCodeRegistry.loadCsv(csv);
        assertThat(loaded).isEqualTo(3);
        assertThat(FeatureCodeRegistry.lookup("99902").orElseThrow().category()).isEqualTo("自定义");
        assertThat(FeatureCodeRegistry.lookup("99903").orElseThrow().name()).isEqualTo("自定义水系");
    }

    @Test
    void parseIntegration_customCodeEnriched() throws Exception {
        FeatureCodeRegistry.register("99901", "自定义房屋", "建筑");
        String dxf = entities(
                "  0\nTEXT\n  5\nB1\n  8\n0\n 10\n1\n 20\n2\n 40\n2.5\n  1\n房\n"
                        + "1001\nCASS\n1000\n99901\n");
        CADEntity e = single(dxf);
        assertThat(e.getProperties().get(EntityProperty.FEATURE_CODE)).isEqualTo("99901");
        assertThat(e.getProperties().get(EntityProperty.FEATURE_TYPE)).isEqualTo("自定义房屋");
        assertThat(e.getProperties().get(EntityProperty.FEATURE_CATEGORY)).isEqualTo("建筑");
    }

    // =========================================================================
    // C. 按编码面积报表
    // =========================================================================

    @Test
    void areaStats_groupByCodeAndUnits() {
        // 毫米图纸：两个 10000mm 见方房屋（各 100 m²）+ 一个无编码三角形（50 m²）
        CADEntity house1 = closedSquare("41000", 0, 0);
        CADEntity house2 = closedSquare("41000", 20000, 0);
        Polygon triangle = GF.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10000, 0),
                new Coordinate(0, 10000), new Coordinate(0, 0)});
        CADEntity pond = CADEntity.builder("LWPOLYLINE").layer("0").geometry(triangle).build();
        CADEntity openLine = CADEntity.builder("LWPOLYLINE").layer("0")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(0, 0), new Coordinate(100, 0)}))
                .build();

        DrawingMetadata mm = new DrawingMetadata();
        mm.setInsunits(4);
        Map<String, AreaStatRow> stats = AreaStats.compute(
                List.of(house1, house2, pond, openLine), mm);

        assertThat(stats).containsOnlyKeys("41000", AreaStats.NO_CODE);
        AreaStatRow houses = stats.get("41000");
        assertThat(houses.getCount()).isEqualTo(2);
        assertThat(houses.getFeatureType()).isEqualTo("普通房屋");
        assertThat(houses.getArea()).isCloseTo(200, within(1e-6));
        assertThat(houses.getMu()).isCloseTo(0.3, within(1e-9));
        assertThat(houses.getHectares()).isCloseTo(0.02, within(1e-9));
        assertThat(stats.get(AreaStats.NO_CODE).getArea()).isCloseTo(50, within(1e-6));

        // 面积降序：41000 在前
        assertThat(stats.keySet().iterator().next()).isEqualTo("41000");

        // 不传元数据 → 原始单位²
        Map<String, AreaStatRow> raw = AreaStats.compute(List.of(house1));
        assertThat(raw.get("41000").getArea()).isCloseTo(1e8, within(1.0));
    }

    @Test
    void areaStats_summaryAndCsv() throws Exception {
        DrawingMetadata mm = new DrawingMetadata();
        mm.setInsunits(4);
        Map<String, AreaStatRow> stats = AreaStats.compute(
                List.of(closedSquare("41000", 0, 0), closedSquare("41000", 20000, 0)), mm);

        String summary = AreaStats.summary(stats);
        assertThat(summary).contains("41000").contains("普通房屋").contains("合计");

        Path csv = tempDir.resolve("area.csv");
        AreaStats.writeCsv(stats, csv);
        List<String> lines = Files.readAllLines(csv);
        assertThat(lines.get(0)).isEqualTo("featureCode,featureType,count,area_m2,area_mu,area_ha");
        assertThat(lines.get(1)).isEqualTo("41000,普通房屋,2,200.0000,0.3000,0.0200");
    }

    /** 10000mm 见方的闭合多段线（面积 1e8 mm² = 100 m²）。 */
    private static CADEntity closedSquare(String code, double ox, double oy) {
        LinearRing ring = GF.createLinearRing(new Coordinate[]{
                new Coordinate(ox, oy), new Coordinate(ox + 10000, oy),
                new Coordinate(ox + 10000, oy + 10000), new Coordinate(ox, oy + 10000),
                new Coordinate(ox, oy)});
        return CADEntity.builder("LWPOLYLINE").layer("0").geometry(ring)
                .property(EntityProperty.FEATURE_CODE, code)
                .build();
    }

    // =========================================================================
    // D. CSV ATTRIB 属性列
    // =========================================================================

    @Test
    void csv_attributeKeys_flattenInsertAttributes() throws Exception {
        CADEntity e = CADEntity.builder(CADEntity.Types.TEXT).layer("宗地")
                .geometry(GF.createPoint(new Coordinate(1, 2)))
                .property("insertAttributes", Map.of("宗地号", "ZD001", "权利人", "张三"))
                .build();
        CsvWriteConfig cfg = CsvWriteConfig.builder()
                .fields(CsvField.X, CsvField.Y)
                .attributeKeys("宗地号", "权利人", "缺失")
                .build();

        StringWriter sw = new StringWriter();
        CsvWriter.write(List.of(e), sw, cfg);
        String[] lines = sw.toString().split("\n");
        assertThat(lines[0]).isEqualTo("X,Y,宗地号,权利人,缺失");
        assertThat(lines[1]).isEqualTo("1.0,2.0,ZD001,张三,");
    }

    @Test
    void csv_attributeKeys_fallbackAndQuoting() throws Exception {
        // 无 insertAttributes 时回退同名实体属性；含逗号的值加引号
        CADEntity e = CADEntity.builder(CADEntity.Types.TEXT).layer("0")
                .geometry(GF.createPoint(new Coordinate(1, 2)))
                .property("备注", "a,b")
                .build();
        CsvWriteConfig cfg = CsvWriteConfig.builder()
                .fields(CsvField.X)
                .attributeKeys("备注")
                .build();

        StringWriter sw = new StringWriter();
        CsvWriter.write(List.of(e), sw, cfg);
        String[] lines = sw.toString().split("\n");
        assertThat(lines[0]).isEqualTo("X,备注");
        assertThat(lines[1]).isEqualTo("1.0,\"a,b\"");
    }
}