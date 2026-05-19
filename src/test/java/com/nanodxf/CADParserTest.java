package com.nanodxf;

import com.nanodxf.model.DXFVersion;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CADParser 基础功能验证。
 *
 * <p>测试目标：
 * <ul>
 *   <li>ParseConfig.builder() 配置与校验</li>
 *   <li>最简 DXF 文件的解析流程（HEADER → ENDSEC → EOF）</li>
 *   <li>ParseResult 数据完整性（metadata、errors、stats）</li>
 * </ul>
 */
class CADParserTest {

    /**
     * 最简合法 DXF：只含 HEADER 段，声明版本为 R2000（AC1015）。
     * 格式：group code 与 value 各占一行，code 前可有空格。
     */
    private static final String MINIMAL_DXF =
        "  0\nSECTION\n  2\nHEADER\n" +
        "  9\n$ACADVER\n  1\nAC1015\n" +
        "  9\n$INSUNITS\n 70\n4\n" +     // 毫米单位
        "  0\nENDSEC\n  0\nEOF\n";

    @Test
    void parseMinimalDxf_shouldExtractVersionAndUnits() throws Exception {
        ParseConfig config = ParseConfig.builder()
                .crs("EPSG:4545")
                .build();

        ParseResult result = new CADParser(config).parse(new StringReader(MINIMAL_DXF));

        assertThat(result).isNotNull();
        assertThat(result.getMetadata()).isNotNull();
        // 验证版本从 $ACADVER 正确解析
        assertThat(result.getMetadata().getVersion()).isEqualTo(DXFVersion.R2000);
        // 验证单位从 $INSUNITS 正确解析（4 = 毫米）
        assertThat(result.getMetadata().getInsunits()).isEqualTo(4);
        // 验证 CRS 由调用方传入
        assertThat(result.getMetadata().getCrs()).isEqualTo("EPSG:4545");
        assertThat(result.getMetadata().getCrsSource()).isEqualTo("caller_specified");
        // 框架阶段实体为空（handler 尚未实现）
        assertThat(result.getEntities()).isEmpty();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void parseDefaultConfig_shouldWork() throws Exception {
        ParseResult result = new CADParser().parse(new StringReader(MINIMAL_DXF));
        assertThat(result.getMetadata().getVersion()).isEqualTo(DXFVersion.R2000);
    }

    @Test
    void parseConfig_arcToleranceMustBePositive() {
        // arcTolerance 不能为负或零
        assertThatThrownBy(() -> ParseConfig.builder().arcTolerance(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arcTolerance");
    }

    @Test
    void parseConfig_coordinateDecimalPlacesMustBeInRange() {
        // coordinateDecimalPlaces 范围 0~15
        assertThatThrownBy(() -> ParseConfig.builder().coordinateDecimalPlaces(16).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coordinateDecimalPlaces");
    }

    @Test
    void parseStats_elapsedTimeShouldBeNonNegative() throws Exception {
        ParseResult result = new CADParser().parse(new StringReader(MINIMAL_DXF));
        assertThat(result.getStats().parseMs()).isGreaterThanOrEqualTo(0);
    }
}
