package com.nanodxf.topology;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 拓扑检查报告，由 {@link TopologyChecker#check} 返回。
 */
public final class TopologyReport {

    private final List<TopologyError>           errors;
    private final Map<TopologyRule, Integer>    countByRule;
    private final long                          checkMs;

    TopologyReport(List<TopologyError> errors, long checkMs) {
        this.errors     = Collections.unmodifiableList(new ArrayList<>(errors));
        this.checkMs    = checkMs;
        Map<TopologyRule, Integer> cnt = new EnumMap<>(TopologyRule.class);
        for (TopologyError e : errors) cnt.merge(e.getRule(), 1, Integer::sum);
        this.countByRule = Collections.unmodifiableMap(cnt);
    }

    /** 无错误时返回 true。 */
    public boolean isValid() { return errors.isEmpty(); }

    /** 全部错误列表（不可变）。 */
    public List<TopologyError> getErrors() { return errors; }

    /** 按规则筛选错误列表。 */
    public List<TopologyError> byRule(TopologyRule rule) {
        return errors.stream().filter(e -> e.getRule() == rule).collect(Collectors.toList());
    }

    /** 各规则错误数量统计（不可变）。 */
    public Map<TopologyRule, Integer> errorCountByRule() { return countByRule; }

    /** 检查耗时（毫秒）。 */
    public long getCheckMs() { return checkMs; }

    /** 人可读摘要。 */
    public String summary() {
        if (isValid()) return String.format("TopologyReport{CLEAN, checkMs=%d}", checkMs);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("TopologyReport{errors=%d, checkMs=%d", errors.size(), checkMs));
        countByRule.forEach((rule, cnt) -> sb.append(String.format(", %s=%d", rule.name(), cnt)));
        sb.append('}');
        return sb.toString();
    }
}
