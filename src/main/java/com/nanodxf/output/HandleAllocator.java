package com.nanodxf.output;

import java.util.Locale;

/**
 * R2007+ 写出用的统一句柄分配器。
 *
 * <p>所有动态句柄（用户线型、图层、用户块 BLOCK_RECORD/BLOCK/ENDBLK、块内实体、主实体）
 * 共用一个递增计数器，从根本上避免不同区段各自编号产生的句柄冲突。
 * 固定句柄最大到 {@code E9}，因此动态分配从 {@code 0x100} 起。
 */
final class HandleAllocator {

    /** 动态句柄起始值（大于所有固定句柄 0xE9）。 */
    static final int FIRST = 0x100;

    private int next = FIRST;

    /** 分配一个句柄，返回大写十六进制字符串（如 {@code 1A3}）。 */
    String next() {
        return Integer.toHexString(next++).toUpperCase(Locale.ROOT);
    }
}
