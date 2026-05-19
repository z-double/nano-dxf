package com.nanodxf.xdata;

/**
 * XDATA 中的单条记录，由 group code 和对应值组成。
 *
 * <p>code 范围 1000~1071：1001=应用名，1000=字符串（CASS 地物编码在此），
 * 1040=浮点，1070=16 位整数。
 */
public record XDataEntry(int code, String value) {}
