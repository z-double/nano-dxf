package com.nanodxf.output;

/**
 * CSV 输出列定义。
 *
 * <p>用于 {@link CsvWriteConfig} 指定输出哪些列以及列的顺序。
 *
 * @see CsvWriteConfig
 * @see CsvWriter
 */
public enum CsvField {

    /** 实体句柄（DXF HANDLE）。 */
    HANDLE,

    /** 图层名。 */
    LAYER,

    /** 实体类型（POINT / TEXT …）。 */
    TYPE,

    /** X 坐标（几何质心或唯一顶点）。 */
    X,

    /** Y 坐标。 */
    Y,

    /** Z 坐标；无 Z 信息时输出 {@link CsvWriteConfig#getNoDataValue()}。 */
    Z,

    /** 高程属性（{@code EntityProperty.ELEVATION}）；未设置时输出 noDataValue。 */
    ELEVATION,

    /** 地物编码属性（{@code EntityProperty.FEATURE_CODE}）。 */
    FEATURE_CODE,

    /** 实体颜色（ACI 颜色索引 {@code EntityProperty.COLOR_ACI}，整数；未设置时输出 noDataValue）。 */
    COLOR
}
