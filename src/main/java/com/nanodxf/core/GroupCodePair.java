package com.nanodxf.core;

/**
 * DXF ASCII 格式的基本数据单元：一个 group code（整数）与对应值（字符串）的不可变对。
 *
 * <p>DXF 文件按行组织，每两行构成一个 pair：
 * <pre>
 *   0        ← code 行（整数，定义下一行的语义）
 *   LINE     ← value 行（字符串）
 *   8
 *   道路中心线
 *   10
 *   100.000
 * </pre>
 *
 * <p>常用 code 语义：0=实体类型，2=名称，5=handle，8=图层，
 * 10/20/30=起点XYZ，40=半径，50=角度，62=ACI颜色，67=空间标志，420=True Color。
 *
 * <p>由 {@link DXFReader#next()} 生成；{@link #asDouble()} / {@link #asInt()}
 * 在解析数值型字段时按需调用，调用方负责保证 value 格式正确（DXFReader 已过滤非整数 code 行）。
 */
public record GroupCodePair(int code, String value) {

    /**
     * 将 value 解析为 double。
     *
     * @throws NumberFormatException 若 value 不是合法浮点数（正常文件不应发生）
     */
    public double asDouble() {
        return Double.parseDouble(value.trim());
    }

    /**
     * 将 value 解析为 int。
     *
     * @throws NumberFormatException 若 value 不是合法整数（正常文件不应发生）
     */
    public int asInt() {
        return Integer.parseInt(value.trim());
    }

    /** 返回 DXF 两行格式的字符串表示，便于调试输出。 */
    @Override
    public String toString() {
        return code + "\n" + value;
    }
}
