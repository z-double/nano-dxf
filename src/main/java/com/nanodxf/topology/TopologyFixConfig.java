package com.nanodxf.topology;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 拓扑修复配置（Builder 模式）。
 *
 * <pre>{@code
 * TopologyFixConfig config = TopologyFixConfig.builder()
 *     .rules(TopologyRule.DUPLICATE_ENTITY, TopologyRule.ZERO_LENGTH)
 *     .snapTolerance(0.01)
 *     .build();
 * TopologyFixResult result = TopologyFixer.fix(entities, config);
 * }</pre>
 *
 * <p>仅支持可自动修复的 3 条规则：
 * {@link TopologyRule#DUPLICATE_ENTITY}、
 * {@link TopologyRule#ZERO_LENGTH}、
 * {@link TopologyRule#DANGLING_ENDPOINT}。
 * {@link TopologyRule#SELF_INTERSECTION} 和 {@link TopologyRule#CONTOUR_CROSSING}
 * 需要人工判断，不在自动修复范围内。
 */
public final class TopologyFixConfig {

    private static final Set<TopologyRule> FIXABLE = Set.of(
            TopologyRule.DUPLICATE_ENTITY,
            TopologyRule.ZERO_LENGTH,
            TopologyRule.DANGLING_ENDPOINT);

    private final Set<TopologyRule> rules;
    private final double            snapTolerance;
    private final int               maxFixes;

    private TopologyFixConfig(Builder b) {
        this.rules         = Collections.unmodifiableSet(EnumSet.copyOf(b.rules));
        this.snapTolerance = b.snapTolerance;
        this.maxFixes      = b.maxFixes;
    }

    public Set<TopologyRule> getRules()      { return rules; }
    public double getSnapTolerance()         { return snapTolerance; }
    public int    getMaxFixes()              { return maxFixes; }

    public static TopologyFixConfig defaults() { return builder().build(); }
    public static Builder builder()            { return new Builder(); }

    public static final class Builder {
        private Set<TopologyRule> rules = EnumSet.copyOf(FIXABLE);
        private double snapTolerance    = 0.001;
        private int    maxFixes         = 10_000;

        public Builder rules(TopologyRule... r) {
            if (r.length == 0) { this.rules = EnumSet.copyOf(FIXABLE); return this; }
            EnumSet<TopologyRule> s = EnumSet.copyOf(Arrays.asList(r));
            s.retainAll(FIXABLE);
            if (s.isEmpty()) throw new IllegalArgumentException(
                    "至少指定一条可修复规则：" + FIXABLE);
            this.rules = s;
            return this;
        }

        public Builder snapTolerance(double t) {
            if (t < 0) throw new IllegalArgumentException("snapTolerance 不能为负");
            this.snapTolerance = t; return this;
        }

        public Builder maxFixes(int max) {
            if (max <= 0) throw new IllegalArgumentException("maxFixes 必须 > 0");
            this.maxFixes = max; return this;
        }

        public TopologyFixConfig build() { return new TopologyFixConfig(this); }
    }
}
