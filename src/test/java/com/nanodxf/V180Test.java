package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.CADBlock;
import com.nanodxf.model.DXFVersion;
import com.nanodxf.output.DXFWriteConfig;
import com.nanodxf.output.DXFWriter;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.8.0 — 句柄统一分配 + $HANDSEED 动态计算、MTEXT 写侧转义/读侧反转义、
 * $INSUNITS 透传、INSERT 展开总量预算。
 */
class V180Test extends NanoDxfTestBase {

    // =========================================================================
    // A. 句柄唯一性 + $HANDSEED
    // =========================================================================

    @Test
    void handles_shouldBeUniqueAcrossTablesBlocksAndEntities() throws Exception {
        CADBlock block = new CADBlock("SYM");
        block.setInsertionPoint(0, 0, 0);
        for (int i = 0; i < 3; i++) {
            block.addEntity(CADEntity.builder(CADEntity.Types.LINE).layer("0")
                    .geometry(GF.createLineString(new Coordinate[]{
                            new Coordinate(i, 0), new Coordinate(i, 1)}))
                    .build());
        }
        List<CADEntity> entities = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            entities.add(CADEntity.builder(CADEntity.Types.LINE).layer("L" + (i % 300))
                    .geometry(GF.createLineString(new Coordinate[]{
                            new Coordinate(i, 0), new Coordinate(i, 10)}))
                    .build());
        }

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build())
                .write(List.of(block), entities, sw);

        List<String[]> pairs = pairs(sw.toString());
        Set<String> handles = new HashSet<>();
        String seed = null;
        int size = 0;
        for (int i = 0; i < pairs.size(); i++) {
            String[] p = pairs.get(i);
            if ("9".equals(p[0]) && "$HANDSEED".equals(p[1])) {
                seed = pairs.get(i + 1)[1];
            }
            if (("5".equals(p[0]) || "105".equals(p[0])) && !p[1].equals(seed)) {
                handles.add(p[1]);
                size++;
            }
        }

        assertThat(seed).as("$HANDSEED 必须写出").isNotNull();
        assertThat(handles).as("句柄不得跨段冲突").hasSize(size);

        int maxHandle = handles.stream().mapToInt(h -> Integer.parseInt(h, 16)).max().orElse(0);
        assertThat(Integer.parseInt(seed, 16))
                .as("$HANDSEED 必须大于所有已分配句柄")
                .isGreaterThan(maxHandle);
    }

    @Test
    void userBlock_ownerHandle_shouldReferenceBlockRecord() throws Exception {
        CADBlock block = new CADBlock("SYM");
        block.setInsertionPoint(0, 0, 0);
        block.addEntity(CADEntity.builder(CADEntity.Types.LINE).layer("0")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(0, 0), new Coordinate(0, 1)}))
                .build());

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build())
                .write(List.of(block), List.of(), sw);

        List<String[]> pairs = pairs(sw.toString());
        String recordHandle = null, blockOwner = null;
        for (int i = 0; i < pairs.size(); i++) {
            String[] p = pairs.get(i);
            if (!"0".equals(p[0])) continue;
            boolean isRecord = "BLOCK_RECORD".equals(p[1]);
            boolean isBlock  = "BLOCK".equals(p[1]);
            if (!isRecord && !isBlock) continue;
            Map<String, String> f = fields(pairs, i);
            if (isRecord && "SYM".equals(f.get("2"))) recordHandle = f.get("5");
            if (isBlock  && "SYM".equals(f.get("2"))) blockOwner   = f.get("330");
        }

        assertThat(recordHandle).isNotNull();
        assertThat(blockOwner).as("BLOCK 的 owner 必须是其 BLOCK_RECORD 句柄").isEqualTo(recordHandle);
    }

    // =========================================================================
    // B. MTEXT 转义往返回 / TEXT 单行化
    // =========================================================================

    @Test
    void mtext_controlChars_shouldRoundTrip() throws Exception {
        String text = "第一行\nD:\\图\\{A}\n尾行";
        CADEntity mtext = CADEntity.builder(CADEntity.Types.MTEXT)
                .layer("注记")
                .geometry(GF.createPoint(new Coordinate(10, 10)))
                .property(EntityProperty.TEXT, text)
                .build();

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build())
                .write(List.of(mtext), sw);
        String dxf = sw.toString();

        // 换行与字面量 \ { } 必须以控制码写出，group value 内不得出现裸换行
        assertThat(dxf).contains("第一行\\PD:\\\\图\\\\\\{A\\}\\P尾行");

        ParseResult result = new CADParser(
                ParseConfig.builder().applyUnitConversion(false).build())
                .parse(new StringReader(dxf));

        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).getProperties().get(EntityProperty.TEXT))
                .isEqualTo(text);
    }

    @Test
    void text_r12_shouldFlattenNewlinesToSpaces() throws Exception {
        CADEntity textEntity = CADEntity.builder(CADEntity.Types.TEXT)
                .layer("0")
                .geometry(GF.createPoint(new Coordinate(0, 0)))
                .property(EntityProperty.TEXT, "甲\n乙\n丙")
                .build();

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R12).build())
                .write(List.of(textEntity), sw);

        assertThat(sw.toString()).contains("甲 乙 丙").doesNotContain("甲\n乙");

        ParseResult result = new CADParser().parse(new StringReader(sw.toString()));
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).getProperties().get(EntityProperty.TEXT))
                .isEqualTo("甲 乙 丙");
    }

    // =========================================================================
    // C. $INSUNITS 透传
    // =========================================================================

    @Test
    void insunits_shouldBeWrittenToHeader() throws Exception {
        CADEntity line = CADEntity.builder(CADEntity.Types.LINE).layer("0")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(0, 0), new Coordinate(1, 1)}))
                .build();

        StringWriter sw = new StringWriter();
        new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).insunits(4).build())
                .write(List.of(line), sw);

        assertThat(sw.toString()).contains("  9\r\n$INSUNITS\r\n 70\r\n4\r\n");
    }

    @Test
    void insunits_default_shouldBeMeters() throws Exception {
        CADEntity line = CADEntity.builder(CADEntity.Types.LINE).layer("0")
                .geometry(GF.createLineString(new Coordinate[]{
                        new Coordinate(0, 0), new Coordinate(1, 1)}))
                .build();

        StringWriter sw = new StringWriter();
        new DXFWriter().write(List.of(line), sw);

        assertThat(sw.toString()).contains("  9\r\n$INSUNITS\r\n 70\r\n6\r\n");
    }

    @Test
    void insunits_outOfRange_shouldThrow() {
        assertThatThrownBy(() -> DXFWriteConfig.builder().insunits(21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insunits");
    }

    // =========================================================================
    // D. INSERT 展开总量预算
    // =========================================================================

    @Test
    void insertExpansion_shouldStopAtBudget_withWarn() throws Exception {
        String dxf = nestedInsertDxf();

        ParseResult normal = new CADParser(
                ParseConfig.builder().applyUnitConversion(false).build())
                .parse(new StringReader(dxf));
        assertThat(normal.getEntities()).as("预算充足时应完整展开 5×10 条").hasSize(50);

        ParseResult capped = new CADParser(
                ParseConfig.builder().applyUnitConversion(false).maxInsertEntities(10).build())
                .parse(new StringReader(dxf));

        assertThat(capped.getEntities()).hasSize(10);
        assertThat(capped.getErrors())
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(ParseErrorLevel.WARN);
                    assertThat(e.getEntityType()).isEqualTo("INSERT");
                    assertThat(e.getMessage()).contains("上限");
                });
    }

    @Test
    void maxInsertEntities_nonPositive_shouldThrow() {
        assertThatThrownBy(() -> ParseConfig.builder().maxInsertEntities(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInsertEntities");
    }

    /** 块 INNER（10 条线）+ 块 OUTER（5 个 INNER 引用）+ 主实体 1 个 OUTER 引用。 */
    private static String nestedInsertDxf() {
        CADBlock inner = new CADBlock("INNER");
        inner.setInsertionPoint(0, 0, 0);
        for (int i = 0; i < 10; i++) {
            inner.addEntity(CADEntity.builder(CADEntity.Types.LINE).layer("0")
                    .geometry(GF.createLineString(new Coordinate[]{
                            new Coordinate(i, 0), new Coordinate(i, 1)}))
                    .build());
        }
        CADBlock outer = new CADBlock("OUTER");
        outer.setInsertionPoint(0, 0, 0);
        for (int i = 0; i < 5; i++) {
            outer.addEntity(CADEntity.builder(CADEntity.Types.INSERT).layer("0")
                    .geometry(GF.createPoint(new Coordinate(i * 10, 0)))
                    .property(EntityProperty.BLOCK_NAME, "INNER")
                    .build());
        }
        CADEntity insert = CADEntity.builder(CADEntity.Types.INSERT).layer("0")
                .geometry(GF.createPoint(new Coordinate(0, 0)))
                .property(EntityProperty.BLOCK_NAME, "OUTER")
                .build();

        StringWriter sw = new StringWriter();
        try {
            new DXFWriter(DXFWriteConfig.builder().version(DXFVersion.R2007).build())
                    .write(List.of(inner, outer), List.of(insert), sw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return sw.toString();
    }

    // =========================================================================
    // 工具
    // =========================================================================

    /** 取第 i 对（实体记录起始）之后、下一个 code 0 之前的字段映射。 */
    private static Map<String, String> fields(List<String[]> pairs, int start) {
        Map<String, String> f = new HashMap<>();
        for (int i = start + 1; i < pairs.size(); i++) {
            String[] p = pairs.get(i);
            if ("0".equals(p[0])) break;
            f.putIfAbsent(p[0], p[1]);
        }
        return f;
    }

    /** 把 DXF 文本按 group code / value 成对切分（行已 trim）。 */
    private static List<String[]> pairs(String dxf) {
        String[] lines = dxf.split("\r\n");
        List<String[]> out = new ArrayList<>(lines.length / 2);
        for (int i = 0; i + 1 < lines.length; i += 2) {
            out.add(new String[]{lines[i].trim(), lines[i + 1].trim()});
        }
        return out;
    }
}
