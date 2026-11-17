package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.CADBlock;
import com.nanodxf.model.DXFVersion;
import com.nanodxf.output.DXFWriteConfig;
import com.nanodxf.output.DXFWriter;
import com.nanodxf.output.ShapefileWriteConfig;
import com.nanodxf.output.ShapefileWriteConfig.ShapeDimension;
import com.nanodxf.output.ShapefileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * v1.8.1 — Shapefile 环向（ESRI 规范：外环 CW、洞 CCW）、
 * 单位换算补齐长度类属性、INSERT 展开扣除块基点。
 */
class V181Test extends NanoDxfTestBase {

    @TempDir
    Path tempDir;

    // =========================================================================
    // A. Shapefile 环向
    // =========================================================================

    @Test
    void shapefilePolygon_windingShouldFollowEsriSpec() throws Exception {
        // 输入故意与规范相反：外环 CCW、洞 CW，写出后必须被翻转
        LinearRing shellCcw = GF.createLinearRing(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0),
                new Coordinate(10, 10), new Coordinate(0, 10), new Coordinate(0, 0)});
        LinearRing holeCw = GF.createLinearRing(new Coordinate[]{
                new Coordinate(2, 2), new Coordinate(2, 4),
                new Coordinate(4, 4), new Coordinate(4, 2), new Coordinate(2, 2)});
        Polygon p1 = GF.createPolygon(shellCcw, new LinearRing[]{holeCw});

        // 输入已符合规范：外环 CW，写出后保持不变
        LinearRing shellCw = GF.createLinearRing(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(0, 10),
                new Coordinate(10, 10), new Coordinate(10, 0), new Coordinate(0, 0)});
        Polygon p2 = GF.createPolygon(shellCw);

        List<CADEntity> entities = List.of(
                CADEntity.builder("LWPOLYLINE").layer("0").geometry(p1).build(),
                CADEntity.builder("LWPOLYLINE").layer("0").geometry(p2).build());

        Path shp = tempDir.resolve("winding.shp");
        new ShapefileWriter(ShapefileWriteConfig.builder()
                .dimension(ShapeDimension.XY).build())
                .write(entities, shp);

        List<double[]> records = polygonPartAreas(shp);
        assertThat(records).hasSize(2);

        // 记录 1：外环 CW（有符号面积 < 0），洞 CCW（> 0）
        assertThat(records.get(0)).hasSize(2);
        assertThat(records.get(0)[0]).as("外环应为顺时针").isLessThan(0);
        assertThat(records.get(0)[1]).as("洞应为逆时针").isGreaterThan(0);

        // 记录 2：已 CW 的外环保持 CW
        assertThat(records.get(1)).hasSize(1);
        assertThat(records.get(1)[0]).as("外环应为顺时针").isLessThan(0);
    }

    /** 逐条读取 SHP 中的 Polygon(5) 记录，返回每个 part 的有符号面积（CCW 为正）。 */
    private static List<double[]> polygonPartAreas(Path shp) throws IOException {
        byte[] all = Files.readAllBytes(shp);
        List<double[]> out = new ArrayList<>();
        int pos = 100; // 文件头
        while (pos + 8 <= all.length) {
            int lenWords = ByteBuffer.wrap(all, pos + 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            int contentStart = pos + 8;
            int contentBytes = lenWords * 2;
            pos = contentStart + contentBytes;

            ByteBuffer c = ByteBuffer.wrap(all, contentStart, contentBytes)
                    .order(ByteOrder.LITTLE_ENDIAN);
            if (c.getInt() != 5) continue; // 只处理 Polygon
            c.position(c.position() + 32);  // 跳过 bbox
            int np = c.getInt(), npt = c.getInt();
            int[] off = new int[np];
            for (int i = 0; i < np; i++) off[i] = c.getInt();
            double[][] pts = new double[npt][2];
            for (int i = 0; i < npt; i++) { pts[i][0] = c.getDouble(); pts[i][1] = c.getDouble(); }

            double[] areas = new double[np];
            for (int p = 0; p < np; p++) {
                int end = p + 1 < np ? off[p + 1] : npt;
                double a = 0;
                for (int i = off[p]; i < end - 1; i++)
                    a += pts[i][0] * pts[i + 1][1] - pts[i + 1][0] * pts[i][1];
                areas[p] = a / 2;
            }
            out.add(areas);
        }
        return out;
    }

    // =========================================================================
    // B. 单位换算补齐长度类属性
    // =========================================================================

    @Test
    void unitConversion_shouldScaleLengthProperties() throws Exception {
        String dxf = mmDxf(
                // TEXT：字高 2500mm → 2.5m
                "  0\nTEXT\n  5\nA1\n  8\n0\n 10\n1000\n 20\n2000\n 30\n0\n 40\n2500\n  1\nBM\n",
                // 线性 DIMENSION：实测值 3000mm → 3m，定义点同步换算
                "  0\nDIMENSION\n  5\nA2\n  8\n0\n 10\n0\n 20\n0\n 30\n0\n"
                        + " 11\n5000\n 21\n6000\n 31\n0\n 70\n0\n 42\n3000\n"
                        + " 13\n1000\n 23\n2000\n 14\n4000\n 24\n2000\n",
                // SPLINE：控制点换算
                "  0\nSPLINE\n  5\nA4\n  8\n0\n 70\n0\n 71\n3\n 72\n8\n 73\n4\n 74\n0\n"
                        + " 40\n0\n 40\n0\n 40\n0\n 40\n0\n 40\n1\n 40\n1\n 40\n1\n 40\n1\n"
                        + " 10\n0\n 20\n0\n 30\n0\n 10\n1000\n 20\n0\n 30\n0\n"
                        + " 10\n1000\n 20\n1000\n 30\n0\n 10\n2000\n 20\n1000\n 30\n0\n",
                // POINT：elevation 1500mm → 1.5m
                "  0\nPOINT\n  5\nA5\n  8\n0\n 10\n3000\n 20\n4000\n 30\n1500\n");

        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(4);

        CADEntity text = result.getEntities().get(0);
        assertThat((Double) text.getProperties().get(EntityProperty.HEIGHT))
                .isCloseTo(2.5, within(1e-9));
        assertThat(text.geometry().getCoordinate().x).isCloseTo(1.0, within(1e-9));
        assertThat(text.geometry().getCoordinate().y).isCloseTo(2.0, within(1e-9));

        CADEntity dim = result.getEntities().get(1);
        assertThat((Double) dim.getProperties().get(EntityProperty.DIMENSION_VALUE))
                .isCloseTo(3.0, within(1e-9));
        assertThat((double[]) dim.getProperties().get(EntityProperty.DIM_POINT1))
                .containsExactly(1.0, 2.0);
        assertThat((double[]) dim.getProperties().get(EntityProperty.DIM_POINT2))
                .containsExactly(4.0, 2.0);
        assertThat(dim.geometry().getCoordinate().x).isCloseTo(5.0, within(1e-9));

        CADEntity spline = result.getEntities().get(2);
        @SuppressWarnings("unchecked")
        List<double[]> ctrl = (List<double[]>) spline.getProperties().get(EntityProperty.CONTROL_POINTS);
        assertThat(ctrl).hasSize(4);
        assertThat(ctrl.get(1)).containsExactly(1.0, 0.0, 0.0);
        assertThat(ctrl.get(3)).containsExactly(2.0, 1.0, 0.0);

        CADEntity point = result.getEntities().get(3);
        assertThat((Double) point.getProperties().get(EntityProperty.ELEVATION))
                .isCloseTo(1.5, within(1e-9));
    }

    @Test
    void unitConversion_angularDimensionValue_shouldNotScale() throws Exception {
        // 角度标注（类型 2）的实测值是度，不随单位换算
        String dxf = mmDxf(
                "  0\nDIMENSION\n  5\nA3\n  8\n0\n 10\n0\n 20\n0\n 11\n0\n 21\n0\n 70\n2\n 42\n90\n");
        CADEntity dim = single(dxf);
        assertThat((Double) dim.getProperties().get(EntityProperty.DIMENSION_VALUE))
                .isCloseTo(90.0, within(1e-9));
    }

    /** $INSUNITS=4（毫米）的 DXF 骨架。 */
    private static String mmDxf(String... entities) {
        StringBuilder sb = new StringBuilder();
        sb.append("  0\nSECTION\n  2\nHEADER\n  9\n$INSUNITS\n 70\n4\n  0\nENDSEC\n");
        sb.append("  0\nSECTION\n  2\nENTITIES\n");
        for (String e : entities) sb.append(e);
        sb.append("  0\nENDSEC\n  0\nEOF\n");
        return sb.toString();
    }

    // =========================================================================
    // C. INSERT 展开扣除块基点
    // =========================================================================

    @Test
    void insertExpansion_shouldSubtractBlockBasePoint() throws Exception {
        CADBlock block = new CADBlock("B");
        block.setInsertionPoint(100, 200, 0);
        block.addEntity(CADEntity.builder(CADEntity.Types.LINE).layer("0")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(100, 200), new Coordinate(110, 210)}))
                .build());
        CADEntity insert = CADEntity.builder(CADEntity.Types.INSERT).layer("0")
                .geometry(GF.createPoint(new Coordinate(1000, 2000)))
                .property(EntityProperty.BLOCK_NAME, "B")
                .build();

        ParseResult result = new CADParser().parse(new StringReader(roundTripDxf(List.of(block), List.of(insert))));
        assertThat(result.getEntities()).hasSize(1);
        Coordinate[] coords = result.getEntities().get(0).geometry().getCoordinates();
        // world = insert + (p − base)：(1000,2000) → (1010,2010)
        assertThat(coords[0].x).isCloseTo(1000.0, within(1e-9));
        assertThat(coords[0].y).isCloseTo(2000.0, within(1e-9));
        assertThat(coords[1].x).isCloseTo(1010.0, within(1e-9));
        assertThat(coords[1].y).isCloseTo(2010.0, within(1e-9));
    }

    @Test
    void insertExpansion_basePointWithRotationAndScale() throws Exception {
        CADBlock block = new CADBlock("B");
        block.setInsertionPoint(100, 200, 0);
        block.addEntity(CADEntity.builder(CADEntity.Types.POINT).layer("0")
                .geometry(GF.createPoint(new Coordinate(110, 200)))
                .build());
        CADEntity insert = CADEntity.builder(CADEntity.Types.INSERT).layer("0")
                .geometry(GF.createPoint(new Coordinate(1000, 2000)))
                .property(EntityProperty.BLOCK_NAME, "B")
                .property(EntityProperty.SCALE_X, 2.0)
                .property(EntityProperty.SCALE_Y, 2.0)
                .property(EntityProperty.ROTATION, 90.0)
                .build();

        ParseResult result = new CADParser().parse(new StringReader(roundTripDxf(List.of(block), List.of(insert))));
        assertThat(result.getEntities()).hasSize(1);
        Coordinate c = result.getEntities().get(0).geometry().getCoordinate();
        // p − base = (10,0)，缩放 2 → (20,0)，旋转 90° → (0,20)，+ 插入点 → (1000,2020)
        assertThat(c.x).isCloseTo(1000.0, within(1e-9));
        assertThat(c.y).isCloseTo(2020.0, within(1e-9));
    }

    @Test
    void nestedInsert_basePointsShouldCompose() throws Exception {
        CADBlock inner = new CADBlock("B");
        inner.setInsertionPoint(100, 200, 0);
        inner.addEntity(CADEntity.builder(CADEntity.Types.POINT).layer("0")
                .geometry(GF.createPoint(new Coordinate(110, 200)))
                .build());

        CADBlock outer = new CADBlock("OUTER");
        outer.setInsertionPoint(50, 60, 0);
        outer.addEntity(CADEntity.builder(CADEntity.Types.INSERT).layer("0")
                .geometry(GF.createPoint(new Coordinate(500, 600)))
                .property(EntityProperty.BLOCK_NAME, "B")
                .build());

        CADEntity insert = CADEntity.builder(CADEntity.Types.INSERT).layer("0")
                .geometry(GF.createPoint(new Coordinate(1000, 2000)))
                .property(EntityProperty.BLOCK_NAME, "OUTER")
                .build();

        ParseResult result = new CADParser().parse(new StringReader(
                roundTripDxf(List.of(inner, outer), List.of(insert))));
        assertThat(result.getEntities()).hasSize(1);
        Coordinate c = result.getEntities().get(0).geometry().getCoordinate();
        // 嵌套插入点世界坐标 = (1000+500−50, 2000+600−60) = (1450,2540)
        // 内层 p − base = (10,0) → (1460,2540)
        assertThat(c.x).isCloseTo(1460.0, within(1e-9));
        assertThat(c.y).isCloseTo(2540.0, within(1e-9));
    }

    /** 用 DXFWriter 生成 R2007 DXF（块基点随 BLOCK 头部写出），再交给解析器回读。 */
    private static String roundTripDxf(List<CADBlock> blocks, List<CADEntity> entities) {
        StringWriter sw = new StringWriter();
        try {
            new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build())
                    .write(blocks, entities, sw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return sw.toString();
    }
}