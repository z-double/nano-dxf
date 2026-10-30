package com.nanodxf.output;

import com.nanodxf.model.DXFVersion;

/**
 * DXF 文件写入配置。通过 {@link Builder} 创建，{@code build()} 时校验参数。
 *
 * <pre>{@code
 * DXFWriteConfig config = DXFWriteConfig.builder()
 *     .version(DXFVersion.R2000)
 *     .coordinateDecimalPlaces(4)
 *     .build();
 * }</pre>
 */
public class DXFWriteConfig {

    /** 输出 DXF 版本，决定可用特性（True Color 需 R2004+）。默认 R2007（浩辰 CAD 最低支持版本）。 */
    private final DXFVersion version;

    /**
     * 输出文件编码。默认随版本自动选择：
     * R2007（AC1021）及以上用 UTF-8，以下用 GBK（兼容国内测绘软件）。
     */
    private final String encoding;

    /** 坐标小数位数（0~15），默认 4（0.1mm 精度）。 */
    private final int coordinateDecimalPlaces;

    /**
     * 图纸单位（{@code $INSUNITS}）：0=无单位、4=毫米、6=米（默认）。
     * 默认 6 与解析侧「坐标统一换算为米」配套；若解析时关闭了单位换算，
     * 应设为本图原始单位以保持往返一致。
     */
    private final int insunits;

    private DXFWriteConfig(Builder b) {
        this.version                = b.version;
        this.coordinateDecimalPlaces = b.coordinateDecimalPlaces;
        this.insunits               = b.insunits;
        // 编码：未指定时按版本自动选
        this.encoding = b.encoding != null ? b.encoding
                : (b.version.before(DXFVersion.R2007) ? "GBK" : "UTF-8");
    }

    public DXFVersion getVersion()               { return version; }
    public String getEncoding()                  { return encoding; }
    public int getCoordinateDecimalPlaces()      { return coordinateDecimalPlaces; }
    public int getInsunits()                     { return insunits; }

    public static Builder builder()              { return new Builder(); }
    public static DXFWriteConfig defaults()      { return builder().build(); }

    public static class Builder {
        private DXFVersion version = DXFVersion.R2007;  // 浩辰 CAD 最低支持 R2007（AC1021）
        private String encoding = null; // null = 随版本自动
        private int coordinateDecimalPlaces = 4;
        private int insunits = 6;

        /**
         * 输出版本，默认 {@link DXFVersion#R2007}（浩辰 CAD 最低支持版本）。
         * 写出器仅实现两条路径：
         * <ul>
         *   <li>{@link DXFVersion#R12}：最简兼容格式，HEADER + TABLES + BLOCKS + ENTITIES</li>
         *   <li>其他所有版本（R2000/R2004/R2007/R2010+）：统一走 R2007 完整路径，
         *       {@code $ACADVER} 写入所设版本字符串，但结构始终为完整 R2007 格式。</li>
         * </ul>
         * 推荐：R12（最广泛兼容）或 R2007（浩辰 CAD / 中望 CAD）。
         * R2000 / R2004 会产生版本头与结构不完全一致的文件，不建议使用。
         */
        public Builder version(DXFVersion v)            { this.version = v; return this; }

        /**
         * 手动指定编码（如 {@code "UTF-8"}、{@code "GBK"}）。
         * 不指定时按版本自动选择：R2007+ 用 UTF-8，其他用 GBK。
         */
        public Builder encoding(String enc)             { this.encoding = enc; return this; }

        /**
         * 坐标小数位数（0~15），默认 4。
         * 建议：毫米级 3 位，地形图 4 位，高精度工程 6 位。
         */
        public Builder coordinateDecimalPlaces(int v) {
            if (v < 0 || v > 15)
                throw new IllegalArgumentException("coordinateDecimalPlaces 范围 0~15");
            this.coordinateDecimalPlaces = v; return this;
        }

        /**
         * 图纸单位（{@code $INSUNITS}），范围 0~20，默认 6（米）。
         * 常用值：0=无单位、1=英寸、2=英尺、4=毫米、5=厘米、6=米。
         * 默认 6 与 {@code ParseConfig} 默认把坐标换算为米配套；
         * 解析时设了 {@code applyUnitConversion(false)} 的图纸，应写回原始单位。
         */
        public Builder insunits(int v) {
            if (v < 0 || v > 20)
                throw new IllegalArgumentException("insunits 范围 0~20，当前值：" + v);
            this.insunits = v; return this;
        }

        public DXFWriteConfig build() { return new DXFWriteConfig(this); }
    }
}
