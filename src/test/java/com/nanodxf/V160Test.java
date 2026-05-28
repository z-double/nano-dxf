package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.geometry.OcsTransformer;
import com.nanodxf.output.SvgWriteConfig;
import com.nanodxf.output.SvgWriter;
import com.nanodxf.survey.ContourHelper;
import com.nanodxf.survey.ContourSet;
import com.nanodxf.survey.ElevationAnnotation;
import com.nanodxf.topology.TopologyCheckConfig;
import com.nanodxf.topology.TopologyChecker;
import com.nanodxf.topology.TopologyReport;
import com.nanodxf.topology.TopologyRule;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * v1.6.0 — OCS 变换、SVG 输出、解析过滤、测绘专项 API、拓扑检查。
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>方向 A：OCS/WCS 坐标变换（OcsTransformer + handler 集成）</li>
 *   <li>方向 B：SVG 矢量输出</li>
 *   <li>方向 C：解析过滤 API（图层/类型白黑名单）</li>
 *   <li>方向 D：测绘专项 API（ContourHelper / ElevationAnnotation / AttDefHandler）</li>
 *   <li>方向 E：拓扑检查 API（TopologyChecker 5 条规则）</li>
 * </ul>
 */
class V160Test extends NanoDxfTestBase {

    // =========================================================================
    // 方向 A：OCS/WCS 坐标变换
    // =========================================================================

    @Test
    void ocsTransformer_defaultExtrusion_shouldReturnOriginalCoords() {
        // N = (0, 0, 1)：isDefault 应为 true，坐标不变
        assertThat(OcsTransformer.isDefault(0, 0, 1)).isTrue();
        Coordinate result = OcsTransformer.toWcs(10.0, 20.0, 5.0, 0.0, 0.0, 1.0);
        assertThat(result.x).isCloseTo(10.0, offset(1e-9));
        assertThat(result.y).isCloseTo(20.0, offset(1e-9));
        assertThat(result.z).isCloseTo(5.0, offset(1e-9));
    }

    @Test
    void ocsTransformer_yAxisExtrusion_shouldTransformCorrectly() {
        // N = (0, 1, 0)：实体平面为 XZ 平面
        // Wx = normalize(Z × N) = normalize((-1,0,0)) = (-1,0,0)
        // Wy = normalize(N × Wx) = normalize((0,1,0) × (-1,0,0)) = normalize((0,0,1)) = (0,0,1)
        // OCS (1, 0, 0) → WCS (-1, 0, 0)
        assertThat(OcsTransformer.isDefault(0, 1, 0)).isFalse();
        Coordinate r1 = OcsTransformer.toWcs(1.0, 0.0, 0.0, 0.0, 1.0, 0.0);
        assertThat(r1.x).isCloseTo(-1.0, offset(1e-9));
        assertThat(r1.y).isCloseTo(0.0, offset(1e-9));
        assertThat(r1.z).isCloseTo(0.0, offset(1e-9));

        // OCS (0, 1, 0) → WCS (0, 0, 1)
        Coordinate r2 = OcsTransformer.toWcs(0.0, 1.0, 0.0, 0.0, 1.0, 0.0);
        assertThat(r2.x).isCloseTo(0.0, offset(1e-9));
        assertThat(r2.y).isCloseTo(0.0, offset(1e-9));
        assertThat(r2.z).isCloseTo(1.0, offset(1e-9));
    }

    // =========================================================================
    // 方向 B：SVG 矢量输出
    // =========================================================================

    @Test
    void svgWriter_lineString_shouldProducePolylineElement() {
        LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(100, 0), new Coordinate(100, 50)});
        CADEntity e = CADEntity.builder("LWPOLYLINE").layer("道路").geometry(ls).build();

        String svg = new SvgWriter().serialize(List.of(e));
        assertThat(svg).contains("<polyline");
        assertThat(svg).contains("id=\"道路\"");
    }

    @Test
    void svgWriter_polygon_withHole_shouldUsePathWithFillRule() {
        LinearRing outer = GF.createLinearRing(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(100, 0),
                new Coordinate(100, 100), new Coordinate(0, 100), new Coordinate(0, 0)});
        LinearRing inner = GF.createLinearRing(new Coordinate[]{
                new Coordinate(20, 20), new Coordinate(80, 20),
                new Coordinate(80, 80), new Coordinate(20, 80), new Coordinate(20, 20)});
        Polygon poly = GF.createPolygon(outer, new LinearRing[]{inner});
        CADEntity e = CADEntity.builder("HATCH").layer("填充").geometry(poly).build();

        String svg = new SvgWriter().serialize(List.of(e));
        assertThat(svg).contains("<path");
        assertThat(svg).contains("fill-rule=\"evenodd\"");
    }

    @Test
    void svgWriter_point_shouldProduceCircleElement() {
        Point pt = GF.createPoint(new Coordinate(50, 50));
        CADEntity e = CADEntity.builder("POINT").layer("控制点").geometry(pt).build();

        String svg = new SvgWriter().serialize(List.of(e));
        assertThat(svg).contains("<circle");
        assertThat(svg).contains("cx=\"50");
    }

    @Test
    void svgWriter_layerGrouping_shouldWrapInGElement() {
        LineString ls1 = GF.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(10, 0)});
        LineString ls2 = GF.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(0, 10)});
        CADEntity e1 = CADEntity.builder("LINE").layer("图层A").geometry(ls1).build();
        CADEntity e2 = CADEntity.builder("LINE").layer("图层B").geometry(ls2).build();

        SvgWriteConfig cfg = SvgWriteConfig.builder().background("#ffffff").build();
        String svg = new SvgWriter(cfg).serialize(List.of(e1, e2));
        assertThat(svg).contains("id=\"图层A\"");
        assertThat(svg).contains("id=\"图层B\"");
        // 背景矩形
        assertThat(svg).contains("<rect");
        assertThat(svg).contains("#ffffff");
    }

    // =========================================================================
    // 方向 C：解析过滤 API
    // =========================================================================

    @Test
    void parseConfig_includeLayer_shouldSkipOtherLayers() throws Exception {
        String dxf = entities(
                "  0\nLINE\n  5\n1\n  8\n道路\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n",
                "  0\nLINE\n  5\n2\n  8\n建筑\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n"
        );
        ParseConfig config = ParseConfig.builder().includeLayers("道路").build();
        ParseResult result = new CADParser(config).parse(new java.io.StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).getLayer()).isEqualTo("道路");
    }

    @Test
    void parseConfig_includeType_shouldSkipOtherTypes() throws Exception {
        String dxf = entities(
                "  0\nLINE\n  5\n1\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n",
                "  0\nPOINT\n  5\n2\n  8\n0\n 10\n5\n 20\n5\n 30\n3\n"
        );
        ParseConfig config = ParseConfig.builder().includeTypes("POINT").build();
        ParseResult result = new CADParser(config).parse(new java.io.StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).getType()).isEqualTo("POINT");
    }

    @Test
    void parseConfig_excludeLayer_shouldSkipMatchingLayer() throws Exception {
        String dxf = entities(
                "  0\nLINE\n  5\n1\n  8\n辅助线\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n",
                "  0\nLINE\n  5\n2\n  8\n道路\n 10\n0\n 20\n0\n 30\n0\n 11\n10\n 21\n0\n 31\n0\n"
        );
        ParseConfig config = ParseConfig.builder().excludeLayers("辅助线").build();
        ParseResult result = new CADParser(config).parse(new java.io.StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).getLayer()).isEqualTo("道路");
    }

    // =========================================================================
    // 方向 D：测绘专项 API
    // =========================================================================

    @Test
    void contourHelper_shouldGroupByElevation() {
        // 3 条等高线：10m × 2，20m × 1
        Coordinate[] c1 = {new Coordinate(0,0), new Coordinate(10,0), new Coordinate(10,10), new Coordinate(0,0)};
        Coordinate[] c2 = {new Coordinate(1,1), new Coordinate(9,1), new Coordinate(9,9), new Coordinate(1,1)};
        Coordinate[] c3 = {new Coordinate(2,2), new Coordinate(8,2), new Coordinate(8,8), new Coordinate(2,2)};

        CADEntity e10a = CADEntity.builder("LWPOLYLINE").layer("等高线")
                .geometry(GF.createLineString(c1)).property("elevation", 10.0).build();
        CADEntity e10b = CADEntity.builder("LWPOLYLINE").layer("等高线")
                .geometry(GF.createLineString(c2)).property("elevation", 10.0).build();
        CADEntity e20  = CADEntity.builder("LWPOLYLINE").layer("计曲线")
                .geometry(GF.createLineString(c3)).property("elevation", 20.0).build();

        ContourSet cs = ContourHelper.extract(List.of(e10a, e10b, e20));
        assertThat(cs.size()).isEqualTo(3);
        assertThat(cs.byElevation()).containsKey(10.0);
        assertThat(cs.byElevation().get(10.0)).hasSize(2);
        assertThat(cs.byElevation()).containsKey(20.0);
        assertThat(cs.range()).containsExactly(10.0, 20.0);
    }

    @Test
    void contourHelper_validate_shouldDetectWrongElevations() {
        Coordinate[] coords = {new Coordinate(0,0), new Coordinate(1,0)};
        CADEntity e5  = CADEntity.builder("LWPOLYLINE").layer("L")
                .geometry(GF.createLineString(coords)).property("elevation", 5.0).build();
        CADEntity e10 = CADEntity.builder("LWPOLYLINE").layer("L")
                .geometry(GF.createLineString(coords)).property("elevation", 10.0).build();
        CADEntity e13 = CADEntity.builder("LWPOLYLINE").layer("L")
                .geometry(GF.createLineString(coords)).property("elevation", 13.0).build(); // 异常

        ContourSet cs = ContourHelper.extract(List.of(e5, e10, e13));
        List<Double> bad = cs.validate(5.0); // 等高距 5m
        assertThat(bad).hasSize(1);
        assertThat(bad.get(0)).isEqualTo(13.0);
    }

    @Test
    void contourHelper_range_shouldReturnMinMax() {
        Coordinate[] c = {new Coordinate(0,0), new Coordinate(1,0)};
        CADEntity e1 = CADEntity.builder("LWPOLYLINE").layer("L")
                .geometry(GF.createLineString(c)).property("elevation", 100.0).build();
        CADEntity e2 = CADEntity.builder("LWPOLYLINE").layer("L")
                .geometry(GF.createLineString(c)).property("elevation", 200.0).build();

        ContourSet cs = ContourHelper.extract(List.of(e1, e2));
        assertThat(cs.range()).containsExactly(100.0, 200.0);
    }

    @Test
    void elevationAnnotation_shouldLinkPointToNearestText() {
        Point pt = GF.createPoint(new Coordinate(10.0, 10.0, 0.0));
        CADEntity point = CADEntity.builder("POINT").layer("控制点")
                .geometry(pt).build();

        Point tp = GF.createPoint(new Coordinate(10.5, 10.0));
        CADEntity text = CADEntity.builder("TEXT").layer("注记")
                .geometry(tp).property("text", "25.30").build();

        Map<CADEntity, Double> result = ElevationAnnotation.link(List.of(point, text), 2.0);
        assertThat(result).containsKey(point);
        assertThat(result.get(point)).isCloseTo(25.30, offset(1e-9));
    }

    @Test
    void elevationAnnotation_shouldExtractHPattern() {
        assertThat(ElevationAnnotation.parseElevation("H=25.30")).isEqualTo(25.30);
        assertThat(ElevationAnnotation.parseElevation("h=100.5")).isEqualTo(100.5);
        assertThat(ElevationAnnotation.parseElevation("▽12.345")).isEqualTo(12.345);
        assertThat(ElevationAnnotation.parseElevation("50.1234")).isEqualTo(50.1234);
        assertThat(ElevationAnnotation.parseElevation("普通文字")).isNull();
    }

    @Test
    void attDefHandler_shouldExtractTagAndPrompt() throws Exception {
        String dxf = entities(
                "  0\nATTDEF\n  5\nBB\n  8\n符号\n" +
                "  1\n25.30\n  2\nELV\n  3\n请输入高程值\n" +
                " 10\n100.0\n 20\n200.0\n 30\n5.0\n" +
                " 40\n3.0\n 70\n0\n"
        );
        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("ATTDEF");
        assertThat(e.getProperties().get(EntityProperty.TAG)).isEqualTo("ELV");
        assertThat(e.getProperties().get(EntityProperty.PROMPT)).isEqualTo("请输入高程值");
        assertThat(e.getProperties().get(EntityProperty.TEXT)).isEqualTo("25.30");
        assertThat(e.geometry()).isInstanceOf(Point.class);
        assertThat(((Point) e.geometry()).getX()).isCloseTo(100.0, offset(1e-9));
    }

    // =========================================================================
    // 方向 E：拓扑检查 API
    // =========================================================================

    @Test
    void topologyChecker_cleanData_shouldReturnNoErrors() {
        LineString ls1 = GF.createLineString(new Coordinate[]{new Coordinate(0,0), new Coordinate(10,0)});
        LineString ls2 = GF.createLineString(new Coordinate[]{new Coordinate(10,0), new Coordinate(20,0)});
        CADEntity e1 = CADEntity.builder("LWPOLYLINE").layer("道路").geometry(ls1).build();
        CADEntity e2 = CADEntity.builder("LWPOLYLINE").layer("道路").geometry(ls2).build();

        TopologyReport report = TopologyChecker.check(List.of(e1, e2),
                TopologyCheckConfig.builder().rules(TopologyRule.DUPLICATE_ENTITY,
                        TopologyRule.SELF_INTERSECTION, TopologyRule.ZERO_LENGTH).build());
        assertThat(report.isValid()).isTrue();
        assertThat(report.getErrors()).isEmpty();
    }

    @Test
    void topologyChecker_duplicateEntity_shouldDetect() {
        LineString ls = GF.createLineString(new Coordinate[]{new Coordinate(0,0), new Coordinate(10,0)});
        CADEntity e1 = CADEntity.builder("LINE").layer("道路").geometry(ls).build();
        CADEntity e2 = CADEntity.builder("LINE").layer("道路").geometry(ls).build();

        TopologyReport report = TopologyChecker.check(List.of(e1, e2),
                TopologyCheckConfig.builder().rules(TopologyRule.DUPLICATE_ENTITY).build());
        assertThat(report.isValid()).isFalse();
        assertThat(report.byRule(TopologyRule.DUPLICATE_ENTITY)).hasSize(1);
    }

    @Test
    void topologyChecker_selfIntersection_shouldDetect() {
        // 自相交多边形（蝴蝶结形）
        Polygon bowtie = GF.createPolygon(new Coordinate[]{
                new Coordinate(0,0), new Coordinate(10,10),
                new Coordinate(10,0), new Coordinate(0,10),
                new Coordinate(0,0)});
        CADEntity e = CADEntity.builder("HATCH").layer("建筑").geometry(bowtie).build();

        TopologyReport report = TopologyChecker.check(List.of(e),
                TopologyCheckConfig.builder().rules(TopologyRule.SELF_INTERSECTION).build());
        assertThat(report.isValid()).isFalse();
        assertThat(report.byRule(TopologyRule.SELF_INTERSECTION)).hasSize(1);
    }

    @Test
    void topologyChecker_zeroLength_shouldDetect() {
        // 两点完全重合 → 零长度线
        LineString zero = GF.createLineString(new Coordinate[]{
                new Coordinate(5, 5), new Coordinate(5, 5)});
        CADEntity e = CADEntity.builder("LINE").layer("道路").geometry(zero).build();

        TopologyReport report = TopologyChecker.check(List.of(e),
                TopologyCheckConfig.builder().rules(TopologyRule.ZERO_LENGTH)
                        .snapTolerance(0.001).build());
        assertThat(report.isValid()).isFalse();
        assertThat(report.byRule(TopologyRule.ZERO_LENGTH)).hasSize(1);
    }

    @Test
    void topologyChecker_danglingEndpoint_shouldDetect() {
        // ls1 与 ls2 共享端点 (10,0)，ls3 孤立，端点 (30,0) 无相接线
        LineString ls1 = GF.createLineString(new Coordinate[]{new Coordinate(0,0), new Coordinate(10,0)});
        LineString ls2 = GF.createLineString(new Coordinate[]{new Coordinate(10,0), new Coordinate(20,0)});
        LineString ls3 = GF.createLineString(new Coordinate[]{new Coordinate(25,0), new Coordinate(30,0)});
        CADEntity e1 = CADEntity.builder("LWPOLYLINE").layer("道路").geometry(ls1).build();
        CADEntity e2 = CADEntity.builder("LWPOLYLINE").layer("道路").geometry(ls2).build();
        CADEntity e3 = CADEntity.builder("LWPOLYLINE").layer("道路").geometry(ls3).build();

        TopologyReport report = TopologyChecker.check(List.of(e1, e2, e3),
                TopologyCheckConfig.builder().rules(TopologyRule.DANGLING_ENDPOINT)
                        .lineConnectLayers("道路").snapTolerance(0.001).build());
        assertThat(report.isValid()).isFalse();
        // ls1 起点 (0,0)、ls2 终点 (20,0)、ls3 两端 (25,0)/(30,0) 均悬挂 → ≥4 个
        assertThat(report.byRule(TopologyRule.DANGLING_ENDPOINT).size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void topologyChecker_contourCrossing_shouldDetect() {
        // 两条等高线 X 形交叉
        LineString c1 = GF.createLineString(new Coordinate[]{new Coordinate(0,5), new Coordinate(10,5)});
        LineString c2 = GF.createLineString(new Coordinate[]{new Coordinate(5,0), new Coordinate(5,10)});
        CADEntity e1 = CADEntity.builder("LWPOLYLINE").layer("等高线").geometry(c1).build();
        CADEntity e2 = CADEntity.builder("LWPOLYLINE").layer("等高线").geometry(c2).build();

        TopologyReport report = TopologyChecker.check(List.of(e1, e2),
                TopologyCheckConfig.builder().rules(TopologyRule.CONTOUR_CROSSING)
                        .contourLayers("等高线").build());
        assertThat(report.isValid()).isFalse();
        assertThat(report.byRule(TopologyRule.CONTOUR_CROSSING)).hasSize(1);
    }

    // =========================================================================
    // 方向 A（OCS 测试继续）
    // =========================================================================

    @Test
    void circleHandler_withZDownExtrusion_shouldProduceWcsGeometry() throws Exception {
        // N = (0, 0, -1)：X 轴翻转
        // 圆心 OCS(100, 50, 0) → WCS(-100, 50, 0)
        String dxf = entities(
                "  0\nCIRCLE\n  5\n1A\n  8\n测试\n" +
                " 10\n100.0\n 20\n50.0\n 30\n0.0\n" +
                " 40\n10.0\n" +
                "210\n0.0\n220\n0.0\n230\n-1.0\n"
        );
        CADEntity e = single(dxf);
        assertThat(e.geometry()).isInstanceOf(LinearRing.class);
        Envelope env = e.geometry().getEnvelopeInternal();
        double cx = (env.getMinX() + env.getMaxX()) / 2.0;
        double cy = (env.getMinY() + env.getMaxY()) / 2.0;
        // WCS 圆心应在 (-100, 50) 附近（离散化误差 < 1e-3）
        assertThat(cx).isCloseTo(-100.0, offset(1e-3));
        assertThat(cy).isCloseTo(50.0, offset(1e-3));
    }
}
