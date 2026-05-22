package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.CADBlock;
import com.nanodxf.model.DXFVersion;
import com.nanodxf.output.DXFWriteConfig;
import com.nanodxf.output.DXFWriter;
import com.nanodxf.EntityProperty;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.2.0 — DXFWriter 扩充：ARC / CIRCLE / HATCH / INSERT + 块定义。
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>ARC 写出（R2007）：角度字段正确性</li>
 *   <li>CIRCLE 写出（R2007）</li>
 *   <li>HATCH 写出（SOLID 填充、含洞往返）</li>
 *   <li>MTEXT 写出（R2007）往返</li>
 *   <li>INSERT + 块定义写出（R2007）往返，展开验证</li>
 *   <li>块定义段顺序（BLOCKS 先于 ENTITIES）</li>
 *   <li>null 参数防御</li>
 * </ul>
 */
class V120Test extends NanoDxfTestBase {

    @Test
    void dxfWriter_arc_shouldProduceArcEntity() throws Exception {
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.ARC)
                .layer("弧形")
                .geometry(GF.createPoint(new Coordinate(50, 50)))
                .property(EntityProperty.RADIUS, 20.0)
                .property(EntityProperty.START_ANGLE, 0.0)
                .property(EntityProperty.END_ANGLE, 270.0)
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        String dxf = sw.toString();
        assertThat(dxf).contains("ARC").contains("50.0000").contains("20.0000").contains("270.0000");
    }

    @Test
    void dxfWriter_arc_angleRoundTrip() throws Exception {
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.ARC)
                .layer("弧形")
                .geometry(GF.createPoint(new Coordinate(0, 0)))
                .property(EntityProperty.RADIUS, 10.0)
                .property(EntityProperty.START_ANGLE, 45.0)
                .property(EntityProperty.END_ANGLE, 225.0)
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        String dxf = sw.toString();
        assertThat(dxf).contains("ARC").contains("45.0000").contains("225.0000").contains("10.0000");
    }

    @Test
    void dxfWriter_circle_shouldProduceCircleEntity() throws Exception {
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.CIRCLE)
                .layer("圆形")
                .geometry(GF.createPoint(new Coordinate(100, 100)))
                .property(EntityProperty.RADIUS, 15.0)
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        assertThat(sw.toString()).contains("CIRCLE").contains("15.0000");
    }

    @Test
    void dxfWriter_hatch_solidFill_roundTrip() throws Exception {
        Coordinate[] coords = {
            new Coordinate(0, 0), new Coordinate(10, 0),
            new Coordinate(10, 10), new Coordinate(0, 10), new Coordinate(0, 0)};
        Polygon poly = GF.createPolygon(coords);
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.HATCH)
                .layer("填充").geometry(poly)
                .property(EntityProperty.HATCH_PATTERN, "SOLID")
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        String dxf = sw.toString();
        assertThat(dxf).contains("HATCH").contains("SOLID");
        ParseResult result = new CADParser(ParseConfig.builder().applyUnitConversion(false).build())
                .parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).getType()).isEqualTo("HATCH");
        assertThat(result.getEntities().get(0).geometry()).isInstanceOf(Polygon.class);
    }

    @Test
    void dxfWriter_hatch_withHole_roundTrip() throws Exception {
        Coordinate[] outer = {
            new Coordinate(0, 0), new Coordinate(20, 0),
            new Coordinate(20, 20), new Coordinate(0, 20), new Coordinate(0, 0)};
        Coordinate[] hole = {
            new Coordinate(5, 5), new Coordinate(15, 5),
            new Coordinate(15, 15), new Coordinate(5, 15), new Coordinate(5, 5)};
        Polygon poly = GF.createPolygon(GF.createLinearRing(outer),
                new LinearRing[]{GF.createLinearRing(hole)});
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.HATCH).layer("填充").geometry(poly).build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        ParseResult result = new CADParser(ParseConfig.builder().applyUnitConversion(false).build())
                .parse(new StringReader(sw.toString()));
        assertThat(result.getEntities()).hasSize(1);
        assertThat(((Polygon) result.getEntities().get(0).geometry()).getNumInteriorRing()).isEqualTo(1);
    }

    @Test
    void dxfWriter_mtext_roundTrip() throws Exception {
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.MTEXT)
                .layer("注记")
                .geometry(GF.createPoint(new Coordinate(10, 10)))
                .property(EntityProperty.TEXT, "测试文字\\P第二行")
                .property(EntityProperty.HEIGHT, 3.0)
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build()).write(entities, sw);
        String dxf = sw.toString();
        assertThat(dxf).contains("MTEXT").contains("测试文字");
        ParseResult result = new CADParser(ParseConfig.builder().applyUnitConversion(false).build())
                .parse(new StringReader(dxf));
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).getType()).isEqualTo("MTEXT");
        assertThat(result.getEntities().get(0).getProperties().get(EntityProperty.TEXT))
                .asString().contains("测试文字");
    }

    @Test
    void dxfWriter_insertWithBlock_r2007_roundTrip() throws Exception {
        CADBlock block = new CADBlock("CROSS");
        block.setInsertionPoint(0, 0, 0);
        block.addEntity(CADEntity.builder(CADEntity.Types.LINE)
            .layer("0")
            .geometry(GF.createLineString(new Coordinate[]{
                new Coordinate(-1, 0), new Coordinate(1, 0)}))
            .build());
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.INSERT)
                .layer("符号")
                .geometry(GF.createPoint(new Coordinate(50, 50)))
                .property(EntityProperty.BLOCK_NAME, "CROSS")
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build())
                .write(List.of(block), entities, sw);
        String dxf = sw.toString();
        assertThat(dxf).contains("INSERT").contains("CROSS");
        ParseResult result = new CADParser(ParseConfig.builder().applyUnitConversion(false).build())
                .parse(new StringReader(dxf));
        assertThat(result.getEntities()).isNotEmpty();
        assertThat(result.getEntities().stream().anyMatch(e -> "LINE".equals(e.getType()))).isTrue();
    }

    @Test
    void dxfWriter_insertWithBlock_shouldContainBlockAndInsert() throws Exception {
        CADBlock block = new CADBlock("SYM");
        block.setInsertionPoint(0, 0, 0);
        block.addEntity(CADEntity.builder(CADEntity.Types.LINE)
            .layer("0")
            .geometry(GF.createLineString(new Coordinate[]{
                new Coordinate(-1, 0), new Coordinate(1, 0)}))
            .build());
        List<CADEntity> entities = List.of(
            CADEntity.builder(CADEntity.Types.INSERT)
                .layer("符号")
                .geometry(GF.createPoint(new Coordinate(50, 50)))
                .property(EntityProperty.BLOCK_NAME, "SYM")
                .build());
        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build())
                .write(List.of(block), entities, sw);
        String dxf = sw.toString();
        assertThat(dxf).contains("BLOCK").contains("SYM").contains("INSERT");
        assertThat(dxf.indexOf("SECTION\r\n  2\r\nBLOCKS"))
                .isLessThan(dxf.indexOf("SECTION\r\n  2\r\nENTITIES"));
    }

    @Test
    void dxfWriter_write_nullArguments_shouldThrow() {
        DXFWriter writer = new DXFWriter();
        assertThatThrownBy(() -> writer.write((List<CADEntity>) null, new StringWriter()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> writer.write(List.of(), (List<CADEntity>) null, new StringWriter()))
                .isInstanceOf(NullPointerException.class);
    }
}
