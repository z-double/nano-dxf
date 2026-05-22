package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityDispatcher;
import com.nanodxf.output.ShapefileWriteConfig;
import com.nanodxf.output.ShapefileWriter;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.4.0 — 3D Shapefile + MULTILEADER + SPI + PRJ WKT 扩充。
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>ShapefileWriter 3D：AUTO 检测 → PointZ(11) / PolylineZ(13)</li>
 *   <li>ShapefileWriter 3D：无 Z 数据时 AUTO 退回 2D（Shape Type 1）</li>
 *   <li>ShapefileWriter 3D：ShapeDimension.XY 强制 2D（忽略 Z）</li>
 *   <li>ShapefileWriter PRJ：EPSG:4545（CGCS2000 3° GK CM 108E）写出真实 WKT</li>
 *   <li>MULTILEADER 解析：顶点 → LineString，code 304 MText 清洗</li>
 *   <li>EntityDispatcher SPI：无外部 provider 时内置 MULTILEADER 正常注册</li>
 * </ul>
 */
class V140Test extends NanoDxfTestBase {

    // =========================================================================
    // 3D Shapefile
    // =========================================================================

    @Test
    void shapefileWriter_3d_pointZ_shouldWriteShapeType11() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_3d_");
        try {
            Path shp = tmp.resolve("pts3d.shp");
            List<CADEntity> entities = List.of(
                CADEntity.builder(CADEntity.Types.POINT).layer("高程点")
                    .geometry(GF.createPoint(new Coordinate(100.0, 200.0, 55.5))).build());
            new ShapefileWriter().write(entities, shp);

            assertThat(readShpShapeType(shp)).isEqualTo(11); // PointZ

            // 验证 Z 值写入 SHP 记录：100(SHP header)+8(rec header)+4(type)+8(X)+8(Y)=128
            byte[] shpBytes = Files.readAllBytes(shp);
            double z = ByteBuffer.wrap(shpBytes, 128, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
            assertThat(z).isCloseTo(55.5, org.assertj.core.data.Offset.offset(1e-9));
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_3d_polylineZ_shouldWriteShapeType13() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_3d_");
        try {
            Path shp = tmp.resolve("lines3d.shp");
            LineString ls = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0, 10.0), new Coordinate(1, 1, 20.0)});
            new ShapefileWriter().write(List.of(
                CADEntity.builder(CADEntity.Types.LWPOLYLINE).layer("L").geometry(ls).build()), shp);
            assertThat(readShpShapeType(shp)).isEqualTo(13); // PolylineZ
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_3d_auto_fallsBackTo2d_whenNoZ() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_3d_");
        try {
            Path shp = tmp.resolve("pts2d.shp");
            // new Coordinate(1, 2) → Z = NaN → 应自动使用 2D
            new ShapefileWriter().write(List.of(
                CADEntity.builder(CADEntity.Types.POINT).layer("P")
                    .geometry(GF.createPoint(new Coordinate(1, 2))).build()), shp);
            assertThat(readShpShapeType(shp)).isEqualTo(1); // 普通 Point
        } finally { deleteDir(tmp); }
    }

    @Test
    void shapefileWriter_3d_forceDimension_xy_ignoresZ() throws Exception {
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_3d_");
        try {
            Path shp = tmp.resolve("force2d.shp");
            ShapefileWriteConfig cfg = ShapefileWriteConfig.builder()
                    .dimension(ShapefileWriteConfig.ShapeDimension.XY).build();
            new ShapefileWriter(cfg).write(List.of(
                CADEntity.builder(CADEntity.Types.POINT).layer("P")
                    .geometry(GF.createPoint(new Coordinate(1, 2, 99.0))).build()), shp);
            assertThat(readShpShapeType(shp)).isEqualTo(1); // Point（非 PointZ）
        } finally { deleteDir(tmp); }
    }

    // =========================================================================
    // PRJ WKT 扩充（CGCS2000 Gauss-Kruger）
    // =========================================================================

    @Test
    void shapefileWriter_prj_knownEpsg4545_shouldWriteFullWkt() throws Exception {
        // EPSG:4545 = CGCS2000 3° Gauss-Kruger CM 108E（v1.4.0 新增内置）
        Path tmp = Files.createTempDirectory(Paths.get("target"), "shp_3d_");
        try {
            Path shp = tmp.resolve("crs.shp");
            ShapefileWriteConfig cfg = ShapefileWriteConfig.builder().crs("EPSG:4545").build();
            new ShapefileWriter(cfg).write(List.of(
                CADEntity.builder(CADEntity.Types.POINT).layer("P")
                    .geometry(GF.createPoint(new Coordinate(1, 2))).build()), shp);
            String prj = Files.readString(tmp.resolve("crs.prj"));
            assertThat(prj).startsWith("PROJCS[");  // 真实 WKT，不是注释
            assertThat(prj).contains("Central_Meridian");
            assertThat(prj).contains("108.0");
        } finally { deleteDir(tmp); }
    }

    // =========================================================================
    // MULTILEADER 解析
    // =========================================================================

    @Test
    void multiLeaderHandler_shouldProduceLineStringWithText() throws Exception {
        String dxf = entities(
                "  0\nMULTILEADER\n" +
                "  5\nABC\n" +
                "  8\n标注层\n" +
                " 10\n100.0\n 20\n200.0\n 30\n0.0\n" +
                " 10\n150.0\n 20\n250.0\n 30\n0.0\n" +
                "304\n{\\fArial;建筑面积}\n");
        CADEntity e = single(dxf);
        assertThat(e.getType()).isEqualTo("MULTILEADER");
        assertThat(e.geometry()).isInstanceOf(LineString.class);
        assertThat(e.geometry().getNumPoints()).isEqualTo(2);
        assertThat(e.getProperties().get("text")).isEqualTo("建筑面积");
    }

    // =========================================================================
    // SPI EntityHandlerProvider
    // =========================================================================

    @Test
    void entityDispatcher_spi_shouldLoadFromServiceLoader() {
        // 无外部 SPI provider 时，内置 MULTILEADER 应已正常注册（由 ServiceLoader 无提供者时安全降级）
        EntityDispatcher dispatcher = new EntityDispatcher();
        assertThat(dispatcher.isKnown("MULTILEADER")).isTrue();
        assertThat(dispatcher.isKnown("VIEWPORT")).isTrue();
    }
}
