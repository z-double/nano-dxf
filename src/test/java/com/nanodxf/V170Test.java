package com.nanodxf;

import com.nanodxf.dem.*;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.geometry.GeomSimplifier;
import com.nanodxf.geometry.PolygonBuilder;
import com.nanodxf.geometry.SimplifyMode;
import com.nanodxf.output.CsvField;
import com.nanodxf.output.CsvWriteConfig;
import com.nanodxf.output.CsvWriter;
import com.nanodxf.sheet.SheetEdgeMatcher;
import com.nanodxf.sheet.SheetEdgeReport;
import com.nanodxf.stat.LayerStatRow;
import com.nanodxf.stat.LayerStats;
import com.nanodxf.survey.ContourHelper;
import com.nanodxf.survey.ContourSet;
import com.nanodxf.topology.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * v1.7.0 — 拓扑修复、多边形重建、几何简化、图层统计、CSV 输出、DEM、坡度分析、接边检查。
 */
class V170Test extends NanoDxfTestBase {

    // =========================================================================
    // 方向 A：拓扑修复 API（TopologyFixer）
    // =========================================================================

    @Test
    void topologyFixer_removeDuplicates_shouldDedup() {
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0)});
        CADEntity e1 = CADEntity.builder("LINE").handle("1").layer("道路").geometry(ls).build();
        CADEntity e2 = CADEntity.builder("LINE").handle("2").layer("道路").geometry(ls).build();

        TopologyFixResult r = TopologyFixer.fix(List.of(e1, e2),
                TopologyFixConfig.builder().rules(TopologyRule.DUPLICATE_ENTITY).build());
        assertThat(r.getEntities()).hasSize(1);
        assertThat(r.fixedByRule().get(TopologyRule.DUPLICATE_ENTITY)).isEqualTo(1);
        assertThat(r.hasChanges()).isTrue();
    }

    @Test
    void topologyFixer_removeZeroLength_shouldDrop() {
        LineString zero = GF.createLineString(new Coordinate[]{
                new Coordinate(5, 5), new Coordinate(5, 5)});
        LineString ok   = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(1, 0)});
        CADEntity ez = CADEntity.builder("LINE").handle("Z").layer("L").geometry(zero).build();
        CADEntity eo = CADEntity.builder("LINE").handle("O").layer("L").geometry(ok).build();

        TopologyFixResult r = TopologyFixer.fix(List.of(ez, eo),
                TopologyFixConfig.builder().rules(TopologyRule.ZERO_LENGTH).build());
        assertThat(r.getEntities()).hasSize(1);
        assertThat(r.getEntities().get(0).getHandle()).isEqualTo("O");
        assertThat(r.fixedByRule().get(TopologyRule.ZERO_LENGTH)).isEqualTo(1);
    }

    @Test
    void topologyFixer_snapEndpoints_shouldCloseNearMiss() {
        // ls1 终点 (10.0004, 0)，ls2 起点 (10.0, 0) — 间距 0.0004 < tol 0.001
        LineString ls1 = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10.0004, 0)});
        LineString ls2 = GF.createLineString(new Coordinate[]{
                new Coordinate(10.0, 0), new Coordinate(20, 0)});
        CADEntity e1 = CADEntity.builder("LINE").handle("1").layer("R").geometry(ls1).build();
        CADEntity e2 = CADEntity.builder("LINE").handle("2").layer("R").geometry(ls2).build();

        TopologyFixResult r = TopologyFixer.fix(List.of(e1, e2),
                TopologyFixConfig.builder()
                        .rules(TopologyRule.DANGLING_ENDPOINT)
                        .snapTolerance(0.001).build());
        assertThat(r.fixedByRule().getOrDefault(TopologyRule.DANGLING_ENDPOINT, 0))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void topologyFixer_summary_shouldDescribeChanges() {
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0)});
        CADEntity e1 = CADEntity.builder("LINE").handle("A").layer("L").geometry(ls).build();
        CADEntity e2 = CADEntity.builder("LINE").handle("B").layer("L").geometry(ls).build();

        TopologyFixResult r = TopologyFixer.fix(List.of(e1, e2));
        assertThat(r.summary()).contains("DUPLICATE_ENTITY");
    }

    // =========================================================================
    // 方向 B：多段线→多边形重建（PolygonBuilder）
    // =========================================================================

    @Test
    void polygonBuilder_build_shouldConvertLinearRingToPolygon() {
        LinearRing ring = GF.createLinearRing(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0),
                new Coordinate(10, 10), new Coordinate(0, 10),
                new Coordinate(0, 0)});
        CADEntity e = CADEntity.builder("LWPOLYLINE").handle("A1").layer("建筑").geometry(ring).build();

        List<CADEntity> polys = PolygonBuilder.build(List.of(e));
        assertThat(polys).hasSize(1);
        assertThat(polys.get(0).geometry()).isInstanceOf(Polygon.class);
        assertThat(polys.get(0).getLayer()).isEqualTo("建筑");
        assertThat(polys.get(0).getType()).isEqualTo("POLYGON");
        assertThat(polys.get(0).geometry().getArea()).isCloseTo(100.0, offset(1e-6));
    }

    @Test
    void polygonBuilder_buildSimple_shouldHandleNonClosedInput() {
        LinearRing ring = GF.createLinearRing(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(5, 0),
                new Coordinate(5, 5), new Coordinate(0, 5),
                new Coordinate(0, 0)});
        CADEntity e = CADEntity.builder("LWPOLYLINE").handle("B2").layer("地块").geometry(ring).build();

        List<CADEntity> polys = PolygonBuilder.buildSimple(List.of(e));
        assertThat(polys).hasSize(1);
        assertThat(polys.get(0).geometry()).isInstanceOf(Polygon.class);
        assertThat(polys.get(0).geometry().getArea()).isCloseTo(25.0, offset(1e-6));
    }

    @Test
    void polygonBuilder_noRings_shouldReturnEmpty() {
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0)});
        CADEntity e = CADEntity.builder("LINE").layer("道路").geometry(ls).build();

        assertThat(PolygonBuilder.build(List.of(e))).isEmpty();
    }

    // =========================================================================
    // 方向 C：几何简化（GeomSimplifier）
    // =========================================================================

    @Test
    void geomSimplifier_simplify_shouldReduceVertexCount() {
        // 创建含许多冗余顶点的折线
        Coordinate[] coords = new Coordinate[20];
        for (int i = 0; i < 20; i++) coords[i] = new Coordinate(i, i % 2 == 0 ? 0 : 0.0001);
        LineString ls = GF.createLineString(coords);
        CADEntity e = CADEntity.builder("LWPOLYLINE").layer("等高线").geometry(ls).build();

        List<CADEntity> simplified = GeomSimplifier.simplify(List.of(e), 0.01, SimplifyMode.DOUGLAS_PEUCKER);
        assertThat(simplified).hasSize(1);
        assertThat(simplified.get(0).geometry().getNumPoints())
                .isLessThan(ls.getNumPoints());
    }

    @Test
    void geomSimplifier_zeroTolerance_shouldReturnOriginal() {
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(5, 0), new Coordinate(10, 0)});
        CADEntity e = CADEntity.builder("LINE").layer("L").geometry(ls).build();

        List<CADEntity> result = GeomSimplifier.simplify(List.of(e), 0.0);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).geometry().getNumPoints()).isEqualTo(3);
    }

    @Test
    void geomSimplifier_simplifyContours_shouldReturnNewContourSet() {
        Coordinate[] c1 = new Coordinate[10];
        for (int i = 0; i < 10; i++) c1[i] = new Coordinate(i, i % 2 == 0 ? 0 : 0.0001);
        LineString ls = GF.createLineString(c1);
        CADEntity e = CADEntity.builder("LWPOLYLINE").layer("等高线")
                .geometry(ls).property("elevation", 10.0).build();

        ContourSet cs = ContourHelper.extract(List.of(e));
        ContourSet simplified = GeomSimplifier.simplifyContours(cs, 0.01);
        assertThat(simplified.size()).isGreaterThan(0);
    }

    // =========================================================================
    // 方向 D：图层统计量算（LayerStats）
    // =========================================================================

    @Test
    void layerStats_compute_shouldGroupByLayer() {
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0)});
        CADEntity e1 = CADEntity.builder("LINE").layer("道路").geometry(ls).build();
        CADEntity e2 = CADEntity.builder("LINE").layer("道路").geometry(ls).build();
        CADEntity e3 = CADEntity.builder("LINE").layer("建筑").geometry(ls).build();

        Map<String, LayerStatRow> stats = LayerStats.compute(List.of(e1, e2, e3));
        assertThat(stats).containsKey("道路");
        assertThat(stats).containsKey("建筑");
        assertThat(stats.get("道路").getCount()).isEqualTo(2);
        assertThat(stats.get("建筑").getCount()).isEqualTo(1);
    }

    @Test
    void layerStats_totalLength_shouldSumLineString() {
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0)});
        CADEntity e = CADEntity.builder("LINE").layer("道路").geometry(ls).build();

        Map<String, LayerStatRow> stats = LayerStats.compute(List.of(e));
        assertThat(stats.get("道路").getTotalLength()).isCloseTo(10.0, offset(1e-9));
    }

    @Test
    void layerStats_totalArea_shouldSumPolygon() {
        Polygon poly = GF.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(4, 0),
                new Coordinate(4, 5), new Coordinate(0, 5),
                new Coordinate(0, 0)});
        CADEntity e = CADEntity.builder("HATCH").layer("建筑").geometry(poly).build();

        Map<String, LayerStatRow> stats = LayerStats.compute(List.of(e));
        assertThat(stats.get("建筑").getTotalArea()).isCloseTo(20.0, offset(1e-9));
    }

    @Test
    void layerStats_summary_shouldContainHeader() {
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(1, 0)});
        CADEntity e = CADEntity.builder("LINE").layer("L").geometry(ls).build();

        String s = LayerStats.summary(LayerStats.compute(List.of(e)));
        assertThat(s).contains("图层");
        assertThat(s).contains("合计");
    }

    // =========================================================================
    // 方向 E：点云 CSV 输出（CsvWriter）
    // =========================================================================

    @Test
    void csvWriter_defaultConfig_shouldProduceXYZColumns() throws Exception {
        Point pt = GF.createPoint(new Coordinate(10.0, 20.0, 5.0));
        CADEntity e = CADEntity.builder("POINT").layer("控制点").geometry(pt).build();

        StringWriter sw = new StringWriter();
        CsvWriter.write(List.of(e), sw, CsvWriteConfig.defaults());
        String csv = sw.toString();
        assertThat(csv).contains("X,Y,Z");     // header
        assertThat(csv).contains("10.0");
        assertThat(csv).contains("20.0");
    }

    @Test
    void csvWriter_customFields_shouldRespectOrder() throws Exception {
        Point pt = GF.createPoint(new Coordinate(1.0, 2.0, 3.0));
        CADEntity e = CADEntity.builder("POINT").handle("H1").layer("测量").geometry(pt).build();

        CsvWriteConfig cfg = CsvWriteConfig.builder()
                .fields(CsvField.HANDLE, CsvField.LAYER, CsvField.X, CsvField.Y)
                .build();
        StringWriter sw = new StringWriter();
        CsvWriter.write(List.of(e), sw, cfg);
        String csv = sw.toString();

        String[] lines = csv.strip().split("\n");
        assertThat(lines[0]).startsWith("HANDLE,LAYER,X,Y");
        assertThat(lines[1]).startsWith("H1,测量,");
    }

    @Test
    void csvWriter_nullGeometry_shouldSkipEntity() throws Exception {
        CADEntity e = CADEntity.builder("TEXT").layer("注记").build(); // no geometry

        StringWriter sw = new StringWriter();
        CsvWriter.write(List.of(e), sw, CsvWriteConfig.defaults());
        String csv = sw.toString();
        // 只有 header，无数据行
        assertThat(csv.strip().split("\n")).hasSize(1);
    }

    @Test
    void csvWriter_lineStringCentroid_shouldBeOutputted() throws Exception {
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0)});
        CADEntity e = CADEntity.builder("LINE").layer("道路").geometry(ls).build();

        StringWriter sw = new StringWriter();
        CsvWriter.write(List.of(e), sw, CsvWriteConfig.builder()
                .fields(CsvField.X, CsvField.Y).build());
        String csv = sw.toString();
        String[] lines = csv.strip().split("\n");
        assertThat(lines).hasSize(2); // header + 1 data
        assertThat(lines[1]).contains("5.0"); // centroid X = 5.0
    }

    // =========================================================================
    // 方向 F：DEM + ASCII Grid
    // =========================================================================

    @Test
    void demBuilder_build_shouldProduceGridWithValidPoints() {
        // 3 条等高线：高程 10/20/30，分别在 Y=0/5/10
        ContourSet cs = buildSimpleContourSet();
        DemGrid dem = DemBuilder.build(cs, 1.0);

        assertThat(dem.getNcols()).isGreaterThan(0);
        assertThat(dem.getNrows()).isGreaterThan(0);
        assertThat(dem.validCount()).isGreaterThan(0);
    }

    @Test
    void ascGridWriter_write_shouldProduceValidHeader() throws Exception {
        ContourSet cs = buildSimpleContourSet();
        DemGrid dem = DemBuilder.build(cs, 1.0);

        StringWriter sw = new StringWriter();
        AscGridWriter.write(dem, sw);
        String asc = sw.toString();

        assertThat(asc).contains("ncols");
        assertThat(asc).contains("nrows");
        assertThat(asc).contains("xllcorner");
        assertThat(asc).contains("NODATA_value");
    }

    // =========================================================================
    // 方向 G：坡度/坡向分析（SlopeAnalyzer）
    // =========================================================================

    @Test
    void slopeAnalyzer_flatSurface_shouldHaveZeroSlope() {
        // 均匀高程 → 坡度 0
        double[][] flat = new double[5][5];
        for (double[] row : flat) java.util.Arrays.fill(row, 100.0);
        DemGrid dem = new DemGrid(5, 5, 0, 0, 1.0, -9999, flat);

        SlopeGrid sg = SlopeAnalyzer.analyze(dem);
        // 中心格点坡度应为 0
        assertThat(sg.getSlope(2, 2)).isCloseTo(0.0, offset(1e-6));
        assertThat(sg.getAspect(2, 2)).isEqualTo(-1.0); // 平地
    }

    @Test
    void slopeAnalyzer_uniformGradient_shouldHavePositiveSlope() {
        // Y 方向均匀梯度（每格升高 1m，格距 1m → 坡角 45°）
        double[][] data = new double[5][5];
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 5; c++)
                data[r][c] = (5 - r); // row 0 = 北(高), row 4 = 南(低)
        DemGrid dem = new DemGrid(5, 5, 0, 0, 1.0, -9999, data);

        SlopeGrid sg = SlopeAnalyzer.analyze(dem);
        // 中心格点坡度应接近 45°
        assertThat(sg.getSlope(2, 2)).isCloseTo(45.0, offset(1.0));
        assertThat(sg.meanSlope()).isGreaterThan(0.0);
    }

    @Test
    void slopeAnalyzer_nodataCell_shouldReturnNaN() {
        double nd = -9999.0;
        double[][] data = {{nd, nd}, {nd, nd}};
        DemGrid dem = new DemGrid(2, 2, 0, 0, 1.0, nd, data);

        SlopeGrid sg = SlopeAnalyzer.analyze(dem);
        assertThat(Double.isNaN(sg.getSlope(0, 0))).isTrue();
    }

    // =========================================================================
    // 方向 H：图幅接边检查（SheetEdgeMatcher）
    // =========================================================================

    @Test
    void sheetEdgeMatcher_exactMatch_shouldBeClean() {
        // ls1 (图幅 A) 终点 = ls2 (图幅 B) 起点，共享 (10, 5)
        LineString ls1 = GF.createLineString(new Coordinate[]{new Coordinate(0, 5), new Coordinate(10, 5)});
        LineString ls2 = GF.createLineString(new Coordinate[]{new Coordinate(10, 5), new Coordinate(20, 5)});
        CADEntity e1 = CADEntity.builder("LINE").handle("A").layer("道路").geometry(ls1).build();
        CADEntity e2 = CADEntity.builder("LINE").handle("B").layer("道路").geometry(ls2).build();

        // 接边线 x=10，带宽 0.5，容差 0.001
        SheetEdgeReport r = SheetEdgeMatcher.matchVertical(List.of(e1), List.of(e2), 10.0, 0.5, 0.001);
        assertThat(r.isClean()).isTrue();
        assertThat(r.getCheckedA()).isGreaterThan(0);
        assertThat(r.getCheckedB()).isGreaterThan(0);
    }

    @Test
    void sheetEdgeMatcher_gapDetected_shouldReportGap() {
        // ls1 终点 (9.95, 5)，ls2 起点 (10.05, 5) — 间距 0.1，在容差 0.2 内
        LineString ls1 = GF.createLineString(new Coordinate[]{new Coordinate(0, 5), new Coordinate(9.95, 5)});
        LineString ls2 = GF.createLineString(new Coordinate[]{new Coordinate(10.05, 5), new Coordinate(20, 5)});
        CADEntity e1 = CADEntity.builder("LINE").handle("A").layer("路").geometry(ls1).build();
        CADEntity e2 = CADEntity.builder("LINE").handle("B").layer("路").geometry(ls2).build();

        SheetEdgeReport r = SheetEdgeMatcher.matchVertical(List.of(e1), List.of(e2), 10.0, 0.5, 0.2);
        assertThat(r.isClean()).isFalse();
        assertThat(r.getGaps()).hasSize(1);
        assertThat(r.getGaps().get(0).getDistance()).isCloseTo(0.1, offset(1e-9));
    }

    @Test
    void sheetEdgeMatcher_summary_shouldDescribeResult() {
        LineString ls = GF.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(5, 0)});
        CADEntity e = CADEntity.builder("LINE").handle("X").layer("L").geometry(ls).build();

        SheetEdgeReport r = SheetEdgeMatcher.matchVertical(List.of(e), List.of(), 10.0, 2.0, 0.5);
        assertThat(r.summary()).isNotBlank();
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    /** 构造简单等高线集合用于 DEM 测试（3 条水平线，高程 10/20/30）。 */
    private static ContourSet buildSimpleContourSet() {
        CADEntity e10 = CADEntity.builder("LWPOLYLINE").layer("等高线")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(0, 0), new Coordinate(5, 0), new Coordinate(10, 0)}))
                .property("elevation", 10.0).build();
        CADEntity e20 = CADEntity.builder("LWPOLYLINE").layer("等高线")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(0, 5), new Coordinate(5, 5), new Coordinate(10, 5)}))
                .property("elevation", 20.0).build();
        CADEntity e30 = CADEntity.builder("LWPOLYLINE").layer("等高线")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(0, 10), new Coordinate(5, 10), new Coordinate(10, 10)}))
                .property("elevation", 30.0).build();
        return ContourHelper.extract(List.of(e10, e20, e30));
    }
}
