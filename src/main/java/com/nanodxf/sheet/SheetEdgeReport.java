package com.nanodxf.sheet;

import java.util.Collections;
import java.util.List;

/**
 * 图幅接边检查报告（不可变）。
 *
 * <p>由 {@link SheetEdgeMatcher#match} 返回。
 */
public final class SheetEdgeReport {

    private final List<EdgeGap> gaps;
    private final double        tolerance;
    private final int           checkedA;
    private final int           checkedB;

    SheetEdgeReport(List<EdgeGap> gaps, double tolerance, int checkedA, int checkedB) {
        this.gaps      = Collections.unmodifiableList(gaps);
        this.tolerance = tolerance;
        this.checkedA  = checkedA;
        this.checkedB  = checkedB;
    }

    /** 发现的接边裂缝列表（空列表表示接边正常）。 */
    public List<EdgeGap> getGaps()   { return gaps; }

    /** 检查时使用的容差（坐标单位）。 */
    public double getTolerance()      { return tolerance; }

    /** 图幅 A 在接边带内参与检查的端点数量。 */
    public int getCheckedA()          { return checkedA; }

    /** 图幅 B 在接边带内参与检查的端点数量。 */
    public int getCheckedB()          { return checkedB; }

    /** 接边是否完全吻合（无裂缝）。 */
    public boolean isClean()          { return gaps.isEmpty(); }

    /** 格式化摘要信息（可打印）。 */
    public String summary() {
        if (gaps.isEmpty()) return String.format("接边检查通过：A端点=%d，B端点=%d，无裂缝。", checkedA, checkedB);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("接边裂缝 %d 处（容差=%.4f，A端点=%d，B端点=%d）:%n",
                gaps.size(), tolerance, checkedA, checkedB));
        for (EdgeGap g : gaps)
            sb.append("  ").append(g).append('\n');
        return sb.toString();
    }
}
