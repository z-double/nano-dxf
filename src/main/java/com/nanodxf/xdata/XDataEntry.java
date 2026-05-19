package com.nanodxf.xdata;

/**
 * XDATA（Extended Data）中的单条记录，由 group code 和对应值组成。
 *
 * <p>XDATA group code 范围 1000~1071：
 * <ul>
 *   <li>1001 - 应用名（CASS、EPSW、MAPMATRIX 等）</li>
 *   <li>1000 - 字符串数据（南方 CASS 的地物编码就在这里）</li>
 *   <li>1010~1013 - 3D 点坐标</li>
 *   <li>1040 - 浮点数</li>
 *   <li>1070 - 16 位整数</li>
 * </ul>
 */
public record XDataEntry(int code, String value) {}
