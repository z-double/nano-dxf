package com.nanodxf;

/**
 * 解析统计摘要（不可变 record）。由 {@link ParseResult#getStats()} 获取。
 *
 * @param parseMs      总解析耗时（毫秒），从 {@code parse()} 调用开始到返回结束
 * @param entityCount  成功解析并输出的实体数量（已展开 INSERT）
 * @param errorCount   {@link ParseErrorLevel#WARN} 级别错误数（单实体解析失败）
 * @param warningCount {@link ParseErrorLevel#INFO} 级别提示数（未注册实体类型等）
 */
public record ParseStats(long parseMs, int entityCount, int errorCount, int warningCount) {}
