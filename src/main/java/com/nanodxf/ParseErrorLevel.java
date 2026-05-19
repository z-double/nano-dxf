package com.nanodxf;

public enum ParseErrorLevel {
    /** 文件头损坏、无法识别格式，终止解析 */
    FATAL,
    /** 单实体解析失败，跳过该实体 + 记录，继续 */
    WARN,
    /** 使用了默认值填补缺失字段等 */
    INFO
}
