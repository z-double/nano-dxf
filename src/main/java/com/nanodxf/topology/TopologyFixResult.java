package com.nanodxf.topology;

import com.nanodxf.entity.CADEntity;

import java.util.*;

/**
 * 拓扑修复结果，由 {@link TopologyFixer#fix} 返回。
 */
public final class TopologyFixResult {

    private final List<CADEntity>            entities;
    private final int                        originalCount;
    private final Map<TopologyRule, Integer> fixedByRule;
    private final long                       fixMs;

    TopologyFixResult(List<CADEntity> entities, int originalCount,
                      Map<TopologyRule, Integer> fixedByRule, long fixMs) {
        this.entities      = Collections.unmodifiableList(new ArrayList<>(entities));
        this.originalCount = originalCount;
        this.fixedByRule   = Collections.unmodifiableMap(new EnumMap<>(fixedByRule));
        this.fixMs         = fixMs;
    }

    /** 修复后的实体列表（不可变）。 */
    public List<CADEntity> getEntities()    { return entities; }

    /** 修复前实体总数。 */
    public int getOriginalCount()           { return originalCount; }

    /** 修复后实体总数。 */
    public int getResultCount()             { return entities.size(); }

    /**
     * 各规则修复数量：
     * <ul>
     *   <li>DUPLICATE_ENTITY  — 移除的重复实体数</li>
     *   <li>ZERO_LENGTH       — 移除的零长度/零面积实体数</li>
     *   <li>DANGLING_ENDPOINT — 吸附的端点对数</li>
     * </ul>
     */
    public Map<TopologyRule, Integer> fixedByRule() { return fixedByRule; }

    /** 是否发生了任何修复（有规则的修复数 > 0）。 */
    public boolean hasChanges() {
        return fixedByRule.values().stream().anyMatch(v -> v > 0);
    }

    /** 修复耗时（毫秒）。 */
    public long getFixMs() { return fixMs; }

    /** 人可读摘要。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("TopologyFixResult{original=%d, result=%d, fixMs=%d",
                originalCount, entities.size(), fixMs));
        fixedByRule.forEach((rule, cnt) -> {
            if (cnt > 0) sb.append(String.format(", %s=%d", rule.name(), cnt));
        });
        sb.append('}');
        return sb.toString();
    }
}
