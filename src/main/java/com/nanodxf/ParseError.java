package com.nanodxf;

/**
 * 解析过程中产生的单条错误记录。
 *
 * <p>错误分三级（{@link ParseErrorLevel}）：
 * <ul>
 *   <li>{@code FATAL} — 文件格式根本性损坏，解析已终止</li>
 *   <li>{@code WARN}  — 单个实体解析失败，已跳过，其余实体不受影响</li>
 *   <li>{@code INFO}  — 使用了默认值、遇到未注册实体类型等轻微提示</li>
 * </ul>
 *
 * <p>所有错误汇总在 {@link ParseResult#getErrors()} 中，调用方可按级别过滤：
 * <pre>{@code
 * result.getErrors().stream()
 *     .filter(e -> e.getLevel() == ParseErrorLevel.WARN)
 *     .forEach(System.err::println);
 * }</pre>
 */
public class ParseError {

    /** 错误级别。 */
    private final ParseErrorLevel level;

    /** 产生错误的 DXF 实体类型（如 "HATCH"），未知时为空字符串。 */
    private final String entityType;

    /** 产生错误的实体 handle（code 5），未知时为空字符串。 */
    private final String handle;

    /** 人类可读的错误描述。 */
    private final String message;

    public ParseError(ParseErrorLevel level, String entityType, String handle, String message) {
        this.level      = level;
        this.entityType = entityType;
        this.handle     = handle;
        this.message    = message;
    }

    public ParseErrorLevel getLevel()      { return level; }
    public String getEntityType()          { return entityType; }
    public String getHandle()              { return handle; }
    public String getMessage()             { return message; }

    @Override
    public String toString() {
        return "[" + level + "] " + entityType + " (handle=" + handle + "): " + message;
    }
}
