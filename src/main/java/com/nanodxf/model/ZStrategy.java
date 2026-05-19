package com.nanodxf.model;

public enum ZStrategy {
    /** 保留 Z，输出 3D geometry（测绘推荐） */
    KEEP_3D,
    /** 丢弃 Z，输出 2D geometry */
    FLATTEN_2D,
    /** 2D geometry + Z 存入 properties["elevation"] */
    Z_AS_ATTRIBUTE
}
