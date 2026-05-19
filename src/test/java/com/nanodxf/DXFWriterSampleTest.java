package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.output.DXFWriteConfig;
import com.nanodxf.output.DXFWriter;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成复杂 DXF 样例文件，覆盖典型测绘场景：
 * <ul>
 *   <li>建筑：矩形轮廓、L 形建筑、带内院的复合建筑</li>
 *   <li>道路：主干道（多段折线）、支路、小巷</li>
 *   <li>水系：河流（开放折线）、池塘（闭合多边形）</li>
 *   <li>等高线：多条不同高程的闭合折线（code 38 elevation）</li>
 *   <li>高程点：带 Z 坐标的 POINT 实体</li>
 *   <li>管线：地下管道折线</li>
 *   <li>植被：行道树点位</li>
 *   <li>注记：TEXT 和 MTEXT 实体</li>
 * </ul>
 *
 * <p>输出文件：{@code target/sample/complex_survey.dxf}（约 200m×200m 局部地形图）。
 */
class DXFWriterSampleTest {

    private static final GeometryFactory GF = GeometryBuilder.factory();

    // -------------------------------------------------------------------------
    // ACI 颜色常量
    // -------------------------------------------------------------------------
    private static final int ACI_RED     = 1;  // 高程点
    private static final int ACI_YELLOW  = 2;  // 等高线
    private static final int ACI_GREEN   = 3;  // 建筑/植被
    private static final int ACI_CYAN    = 4;  // 管线
    private static final int ACI_BLUE    = 5;  // 水系
    private static final int ACI_WHITE   = 7;  // 道路/注记

    /**
     * 诊断 A：只有 ENTITIES 段，无 HEADER/TABLES/BLOCKS（与九支渠文件同结构）。
     * 若此文件能打开 → 我们的 HEADER/TABLES 有问题。
     * 若也打不开 → DXFWriter 最基础写入有问题。
     */
    @Test
    void generateDiagA_entitiesOnly() throws IOException {
        Path outDir = Paths.get("target/sample");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("diag_a_entities_only.dxf");

        try (Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(outFile.toFile()), "GBK")) {
            // 手写最简 DXF，与九支渠文件结构完全一致
            String content =
                "  0\r\nSECTION\r\n  2\r\nENTITIES\r\n" +
                "  0\r\nLINE\r\n  8\r\n0\r\n" +
                " 10\r\n0.0\r\n 20\r\n0.0\r\n 30\r\n0.0\r\n" +
                " 11\r\n100.0\r\n 21\r\n0.0\r\n 31\r\n0.0\r\n" +
                "  0\r\nENDSEC\r\n  0\r\nEOF\r\n";
            w.write(content);
        }
        System.out.println("诊断 A：" + outFile.toAbsolutePath());
    }

    /**
     * 诊断 B：HEADER(AC1009) + ENTITIES，无 TABLES（R12 最简）。
     * 若 A 能开、B 不能 → HEADER 有问题。
     */
    @Test
    void generateDiagB_headerEntities() throws IOException {
        Path outDir = Paths.get("target/sample");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("diag_b_header_entities.dxf");

        try (Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(outFile.toFile()), "GBK")) {
            String content =
                "  0\r\nSECTION\r\n  2\r\nHEADER\r\n" +
                "  9\r\n$ACADVER\r\n  1\r\nAC1009\r\n" +
                "  9\r\n$DWGCODEPAGE\r\n  3\r\nANSI_936\r\n" +
                "  9\r\n$INSUNITS\r\n 70\r\n6\r\n" +
                "  0\r\nENDSEC\r\n" +
                "  0\r\nSECTION\r\n  2\r\nENTITIES\r\n" +
                "  0\r\nLINE\r\n  8\r\n0\r\n" +
                " 10\r\n0.0\r\n 20\r\n0.0\r\n 30\r\n0.0\r\n" +
                " 11\r\n100.0\r\n 21\r\n0.0\r\n 31\r\n0.0\r\n" +
                "  0\r\nENDSEC\r\n  0\r\nEOF\r\n";
            w.write(content);
        }
        System.out.println("诊断 B：" + outFile.toAbsolutePath());
    }

    /**
     * 诊断 C：R12 完整格式（DXFWriter 的 R12 路径输出）。
     * 若 B 能开、C 不能 → R12 TABLES 有问题。
     */
    @Test
    void generateDiagC_r12Writer() throws IOException {
        Path outDir = Paths.get("target/sample");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("diag_c_r12_writer.dxf");

        List<CADEntity> entities = List.of(
            CADEntity.builder("LINE").layer("0")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(0, 0, 0), new Coordinate(100, 0, 0)}))
                .build()
        );
        new DXFWriter(DXFWriteConfig.builder()
                .version(com.nanodxf.model.DXFVersion.R12)
                .coordinateDecimalPlaces(4)
                .build()).write(entities, outFile);
        System.out.println("诊断 C：" + outFile.toAbsolutePath());
    }

    /**
     * 诊断 D：R2000 完整格式，GBK 编码，单一 LINE 实体。
     * 若 C 能开、D 不能 → R2000 结构有问题。
     */
    @Test
    void generateDiagD_r2000Single() throws IOException {
        Path outDir = Paths.get("target/sample");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("diag_d_r2000_single.dxf");

        List<CADEntity> entities = List.of(
            CADEntity.builder("LINE").layer("0")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(0, 0, 0), new Coordinate(100, 0, 0)}))
                .build()
        );
        new DXFWriter(DXFWriteConfig.builder()
                .version(com.nanodxf.model.DXFVersion.R2000)
                // 不指定 encoding → 默认 GBK → $DWGCODEPAGE ANSI_936
                .coordinateDecimalPlaces(4)
                .build()).write(entities, outFile);
        System.out.println("诊断 D：" + outFile.toAbsolutePath());
    }

    /**
     * 旧的最简 ASCII 诊断文件（保留，修正了 encoding）。
     */
    @Test
    void generateMinimalAsciiDxf() throws IOException {
        List<CADEntity> entities = new ArrayList<>();
        entities.add(CADEntity.builder("LINE").layer("Road")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(0, 0, 0), new Coordinate(100, 0, 0)}))
                .property("colorAci", 7).build());
        entities.add(CADEntity.builder("POINT").layer("Survey")
                .geometry(GF.createPoint(new Coordinate(50, 50, 25.3)))
                .property("colorAci", 1)
                .property("elevation", 25.3).build());
        entities.add(CADEntity.builder("LWPOLYLINE").layer("Building")
                .geometry(closedRing(c(10,10), c(40,10), c(40,30), c(10,30)))
                .property("colorAci", 3).build());

        Path outDir = Paths.get("target/sample");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("minimal_ascii.dxf");

        new DXFWriter(DXFWriteConfig.builder()
                .version(com.nanodxf.model.DXFVersion.R2000)
                // 不指定 encoding → 默认 GBK → $DWGCODEPAGE ANSI_936（修正了 UTF-8 问题）
                .coordinateDecimalPlaces(4)
                .build()).write(entities, outFile);

        System.out.println("最简 ASCII 文件：" + outFile.toAbsolutePath());
        ParseResult r = new CADParser(
                ParseConfig.builder().applyUnitConversion(false).build())
                .parse(outFile);
        assertThat(r.getEntities()).hasSize(3);
    }

    @Test
    void generateComplexDxfSample() throws IOException {
        List<CADEntity> entities = new ArrayList<>();

        buildRoads(entities);
        buildBuildings(entities);
        buildWater(entities);
        buildContours(entities);
        buildElevationPoints(entities);
        buildPipelines(entities);
        buildVegetation(entities);
        buildAnnotations(entities);

        // 写出文件
        Path outDir = Paths.get("target/sample");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("complex_survey.dxf");

        DXFWriteConfig config = DXFWriteConfig.builder()
                .version(com.nanodxf.model.DXFVersion.R2000)
                .coordinateDecimalPlaces(3)
                .build();
        new DXFWriter(config).write(entities, outFile);

        // 验证：能被解析回来，且实体数量不变
        ParseResult result = new CADParser(
                ParseConfig.builder().applyUnitConversion(false).build())
                .parse(outFile);

        System.out.printf("生成实体数：%d，解析回来：%d，文件大小：%d bytes%n",
                entities.size(), result.getEntities().size(),
                Files.size(outFile));
        System.out.println("输出文件：" + outFile.toAbsolutePath());

        assertThat(result.getEntities()).hasSameSizeAs(entities);
        assertThat(result.getErrors().stream()
                .filter(e -> e.getLevel() == ParseErrorLevel.WARN).count())
                .isEqualTo(0);
    }

    // =========================================================================
    // 道路（道路层）
    // =========================================================================

    private void buildRoads(List<CADEntity> out) {
        // 主干道：东西向，y=120，贯穿全图
        out.add(lwPolyline("道路", ACI_WHITE, false,
                c(0, 120), c(40, 118), c(80, 120), c(120, 122),
                c(160, 120), c(200, 120)));

        // 南北向主干道，x=100
        out.add(lwPolyline("道路", ACI_WHITE, false,
                c(100, 0), c(101, 40), c(100, 80), c(100, 120),
                c(99, 160), c(100, 200)));

        // 次干道：x=50，局部段
        out.add(lwPolyline("道路", ACI_WHITE, false,
                c(50, 60), c(50, 120)));

        // 支路：东西向，y=60
        out.add(lwPolyline("道路", ACI_WHITE, false,
                c(0, 60), c(50, 60), c(100, 58)));

        // 小巷（2 点 LINE）
        out.add(line("道路", ACI_WHITE, 60, 60, 60, 90));
        out.add(line("道路", ACI_WHITE, 80, 90, 80, 120));

        // 人行道轮廓（靠主干道两侧）
        out.add(lwPolyline("道路", ACI_WHITE, false,
                c(0, 123), c(200, 123)));
        out.add(lwPolyline("道路", ACI_WHITE, false,
                c(0, 117), c(200, 117)));
    }

    // =========================================================================
    // 建筑（建筑层）
    // =========================================================================

    private void buildBuildings(List<CADEntity> out) {
        // 建筑 A：标准矩形（20m×15m），左上角区域
        out.add(closedLwPolyline("建筑", ACI_GREEN,
                c(10, 155), c(30, 155), c(30, 170), c(10, 170)));

        // 建筑 B：较大矩形（35m×20m）
        out.add(closedLwPolyline("建筑", ACI_GREEN,
                c(40, 140), c(75, 140), c(75, 160), c(40, 160)));

        // 建筑 C：L 形（主体 + 延伸翼）
        out.add(closedLwPolyline("建筑", ACI_GREEN,
                c(110, 150), c(150, 150), c(150, 165),
                c(135, 165), c(135, 175), c(110, 175)));

        // 建筑 D：带内院（Polygon with hole）→ 外环 + 内环各一条 LWPOLYLINE
        // 外环
        out.add(closedLwPolyline("建筑", ACI_GREEN,
                c(160, 140), c(195, 140), c(195, 175), c(160, 175)));
        // 内院（洞）
        out.add(closedLwPolyline("建筑", ACI_GREEN,
                c(168, 148), c(187, 148), c(187, 167), c(168, 167)));

        // 建筑 E：小型建筑群（3 个相邻）
        for (int i = 0; i < 3; i++) {
            double ox = 10 + i * 22.0;
            out.add(closedLwPolyline("建筑", ACI_GREEN,
                    c(ox, 10), c(ox + 18, 10), c(ox + 18, 25), c(ox, 25)));
        }

        // 建筑 F：斜角切角建筑（六边形近似）
        out.add(closedLwPolyline("建筑", ACI_GREEN,
                c(110, 30), c(130, 28), c(142, 35),
                c(142, 52), c(130, 58), c(110, 55)));
    }

    // =========================================================================
    // 水系（水系层）
    // =========================================================================

    private void buildWater(List<CADEntity> out) {
        // 河流（开放折线，蜿蜒穿越右侧）
        out.add(lwPolyline("水系", ACI_BLUE, false,
                c(200, 80), c(185, 85), c(170, 78),
                c(158, 82), c(148, 75), c(140, 80), c(130, 90)));

        // 河流对岸（平行线）
        out.add(lwPolyline("水系", ACI_BLUE, false,
                c(200, 88), c(185, 93), c(170, 86),
                c(158, 90), c(148, 83), c(140, 88), c(130, 98)));

        // 池塘（闭合多边形）
        out.add(closedLwPolyline("水系", ACI_BLUE,
                c(155, 30), c(170, 25), c(182, 32),
                c(185, 45), c(175, 55), c(160, 52),
                c(150, 42)));

        // 水渠（直线段）
        out.add(line("水系", ACI_BLUE, 100, 0, 100, 58));
        out.add(line("水系", ACI_BLUE, 103, 0, 103, 58));

        // 干沟
        out.add(lwPolyline("水系", ACI_BLUE, false,
                c(0, 45), c(30, 42), c(60, 46), c(100, 45)));
    }

    // =========================================================================
    // 等高线（等高线层，带 elevation）
    // =========================================================================

    private void buildContours(List<CADEntity> out) {
        // 几条模拟地形起伏的等高线（高程 10m~50m）
        double[][] zLevels = {
            {10, 15, 20, 25, 30, 35, 40, 45, 50}
        };
        double[] zVals = {10, 15, 20, 25, 30, 35, 40, 45, 50};

        for (double z : zVals) {
            double offset = z * 0.5; // 随高程向右上偏移，模拟山地
            out.add(contour(z,
                    c(0,         50 + offset),
                    c(25,        48 + offset),
                    c(50,        52 + offset),
                    c(75,        49 + offset),
                    c(100,       53 + offset),
                    c(125,       47 + offset),
                    c(150,       51 + offset),
                    c(175,       48 + offset),
                    c(200,       50 + offset)));
        }

        // 计曲线（高程 50m，较粗）- 使用同样格式但标记为计曲线
        out.add(entity("LINE", "等高线", ACI_YELLOW,
                GF.createLineString(new Coordinate[]{c(0, 75), c(200, 75)}),
                "lineType", "计曲线", "elevation", 50.0));
    }

    // =========================================================================
    // 高程点（高程点层）
    // =========================================================================

    private void buildElevationPoints(List<CADEntity> out) {
        double[][] pts = {
            {20,  50,  12.3},
            {55,  35,  15.7},
            {90,  70,  18.2},
            {130, 45,  22.5},
            {165, 65,  28.1},
            {180, 120, 32.4},
            {50,  160, 10.5},
            {120, 170, 8.8},
            {170, 180, 6.2},
            {35,  90,  14.0},
            {80,  30,  20.3},
            {150, 110, 25.7},
        };
        for (double[] pt : pts) {
            out.add(elevPoint(pt[0], pt[1], pt[2]));
        }
    }

    // =========================================================================
    // 管线（管线层）
    // =========================================================================

    private void buildPipelines(List<CADEntity> out) {
        // 给水主管（沿主干道敷设）
        out.add(entity("LWPOLYLINE", "管线", ACI_CYAN,
                lwPolylineGeom(false,
                        c(0, 121.5), c(100, 121.5), c(100, 200)),
                "pipeType", "给水", "diameter", 300));

        // 污水管（南北向）
        out.add(entity("LWPOLYLINE", "管线", ACI_CYAN,
                lwPolylineGeom(false,
                        c(98, 0), c(98, 60), c(97, 120), c(98, 200)),
                "pipeType", "污水", "diameter", 400));

        // 电力线（架空）
        out.add(lwPolyline("管线", ACI_CYAN, false,
                c(0, 115), c(50, 114), c(100, 115), c(150, 113), c(200, 115)));

        // 通信管道
        out.add(lwPolyline("管线", ACI_CYAN, false,
                c(10, 0), c(10, 60), c(50, 60)));

        // 检修井（点位）
        int[] wellX = {0, 50, 100, 150, 200};
        for (int x : wellX) {
            out.add(point("管线", ACI_CYAN, x, 121.5, 0));
        }
    }

    // =========================================================================
    // 植被（植被层）
    // =========================================================================

    private void buildVegetation(List<CADEntity> out) {
        // 行道树（沿主干道两侧，间距约 10m）
        for (int x = 5; x <= 195; x += 10) {
            out.add(point("植被", ACI_GREEN, x, 126, 0));
            out.add(point("植被", ACI_GREEN, x, 114, 0));
        }

        // 公园绿地（闭合多边形轮廓）
        out.add(closedLwPolyline("植被", ACI_GREEN,
                c(5, 130), c(35, 130), c(35, 155), c(5, 155)));

        // 散植树木
        double[][] trees = {
            {15, 140}, {25, 145}, {20, 150},
            {115, 30}, {125, 35}, {120, 40},
            {190, 30}, {195, 45},
        };
        for (double[] t : trees) {
            out.add(point("植被", ACI_GREEN, t[0], t[1], 0));
        }
    }

    // =========================================================================
    // 注记（注记层）
    // =========================================================================

    private void buildAnnotations(List<CADEntity> out) {
        // 道路名称
        out.add(text("注记", ACI_WHITE, 95, 124, 0, 3.0, "主干道"));
        out.add(text("注记", ACI_WHITE, 103, 90, 0, 2.5, "南北大道"));
        out.add(text("注记", ACI_WHITE, 15, 62, 0, 2.0, "支路"));

        // 建筑编号
        out.add(text("注记", ACI_WHITE, 15, 161, 0, 2.5, "A幢"));
        out.add(text("注记", ACI_WHITE, 52, 148, 0, 2.5, "B幢"));
        out.add(text("注记", ACI_WHITE, 120, 160, 0, 2.5, "C幢"));
        out.add(text("注记", ACI_WHITE, 170, 155, 0, 2.5, "D幢"));
        out.add(text("注记", ACI_WHITE, 12, 15, 0, 2.0, "E1"));
        out.add(text("注记", ACI_WHITE, 34, 15, 0, 2.0, "E2"));
        out.add(text("注记", ACI_WHITE, 56, 15, 0, 2.0, "E3"));

        // 水体名称（MTEXT）
        out.add(mtext("注记", ACI_BLUE, 162, 40, 0, 3.5, "景观\\P池塘"));

        // 图幅说明（MTEXT，右下角）
        out.add(mtext("注记", ACI_WHITE, 120, 5, 0, 4.0,
                "测区地形图\\P比例尺 1:1000\\P坐标系：CGCS2000"));

        // 等高距说明
        out.add(text("注记", ACI_YELLOW, 5, 5, 0, 2.5, "等高距=5m"));
    }

    // =========================================================================
    // 实体构建辅助方法
    // =========================================================================

    private CADEntity line(String layer, int aci, double x1, double y1,
                            double x2, double y2) {
        return CADEntity.builder("LINE")
                .layer(layer)
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(x1, y1, 0),
                        new Coordinate(x2, y2, 0)}))
                .property("colorAci", aci)
                .build();
    }

    private CADEntity lwPolyline(String layer, int aci, boolean closed,
                                  Coordinate... coords) {
        Geometry geom = closed
                ? closedRing(coords)
                : GF.createLineString(coords);
        return CADEntity.builder("LWPOLYLINE")
                .layer(layer).geometry(geom)
                .property("colorAci", aci)
                .build();
    }

    private CADEntity closedLwPolyline(String layer, int aci, Coordinate... coords) {
        return CADEntity.builder("LWPOLYLINE")
                .layer(layer)
                .geometry(closedRing(coords))
                .property("colorAci", aci)
                .build();
    }

    private CADEntity contour(double elevation, Coordinate... coords) {
        LineString geom = GF.createLineString(withZ(coords, elevation));
        return CADEntity.builder("LWPOLYLINE")
                .layer("等高线")
                .geometry(geom)
                .property("colorAci", ACI_YELLOW)
                .property("elevation", elevation)
                .build();
    }

    private CADEntity elevPoint(double x, double y, double z) {
        return CADEntity.builder("POINT")
                .layer("高程点")
                .geometry(GF.createPoint(new Coordinate(x, y, z)))
                .property("colorAci", ACI_RED)
                .property("elevation", z)
                .build();
    }

    private CADEntity point(String layer, int aci, double x, double y, double z) {
        return CADEntity.builder("POINT")
                .layer(layer)
                .geometry(GF.createPoint(new Coordinate(x, y, z)))
                .property("colorAci", aci)
                .build();
    }

    private CADEntity text(String layer, int aci,
                            double x, double y, double z,
                            double height, String content) {
        return CADEntity.builder("TEXT")
                .layer(layer)
                .geometry(GF.createPoint(new Coordinate(x, y, z)))
                .property("colorAci", aci)
                .property("text", content)
                .property("height", height)
                .build();
    }

    private CADEntity mtext(String layer, int aci,
                             double x, double y, double z,
                             double height, String content) {
        return CADEntity.builder("MTEXT")
                .layer(layer)
                .geometry(GF.createPoint(new Coordinate(x, y, z)))
                .property("colorAci", aci)
                .property("text", content)
                .property("height", height)
                .build();
    }

    @SuppressWarnings("unused")
    private CADEntity entity(String type, String layer, int aci, Geometry geom,
                              Object... kvPairs) {
        CADEntity.Builder b = CADEntity.builder(type)
                .layer(layer).geometry(geom)
                .property("colorAci", aci);
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            b.property((String) kvPairs[i], kvPairs[i + 1]);
        }
        return b.build();
    }

    // =========================================================================
    // 几何构建辅助
    // =========================================================================

    private Geometry lwPolylineGeom(boolean closed, Coordinate... coords) {
        return closed ? closedRing(coords) : GF.createLineString(coords);
    }

    private LinearRing closedRing(Coordinate... coords) {
        Coordinate[] closed = new Coordinate[coords.length + 1];
        System.arraycopy(coords, 0, closed, 0, coords.length);
        closed[coords.length] = new Coordinate(coords[0]);
        return GF.createLinearRing(closed);
    }

    private Coordinate[] withZ(Coordinate[] coords, double z) {
        Coordinate[] result = new Coordinate[coords.length];
        for (int i = 0; i < coords.length; i++) {
            result[i] = new Coordinate(coords[i].x, coords[i].y, z);
        }
        return result;
    }

    private static Coordinate c(double x, double y) {
        return new Coordinate(x, y, 0);
    }
}
