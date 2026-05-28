package com.nanodxf.survey;

import com.nanodxf.entity.CADEntity;

import java.util.*;

/**
 * 等高线集合，由 {@link ContourHelper#extract} 产生。
 *
 * <p>内部以高程升序的 {@link TreeMap} 组织，每个高程对应一组 {@link CADEntity}。
 * 可用于等高距验证、高程范围查询、逐高程遍历等典型测绘分析场景。
 */
public class ContourSet {

    private final TreeMap<Double, List<CADEntity>> map;

    ContourSet(TreeMap<Double, List<CADEntity>> map) {
        this.map = map;
    }

    /**
     * 按高程升序返回不可修改的等高线分组视图。
     * key = 高程值，value = 该高程的实体列表（列表本身不可修改）。
     */
    public NavigableMap<Double, List<CADEntity>> byElevation() {
        return Collections.unmodifiableNavigableMap(map);
    }

    /**
     * 返回所有高程值（升序数组）。
     */
    public double[] elevations() {
        return map.keySet().stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * 返回高程范围 {@code [min, max]}。若无等高线返回 {@code [NaN, NaN]}。
     */
    public double[] range() {
        if (map.isEmpty()) return new double[]{Double.NaN, Double.NaN};
        return new double[]{map.firstKey(), map.lastKey()};
    }

    /**
     * 等高线总条数。
     */
    public int size() {
        return map.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 验证等高距一致性：检查所有高程值中，不是 {@code contourInterval} 整数倍（含 0）的异常值。
     *
     * <p>测绘规范：等高线高程应为等高距的整数倍（如 1m 等高距的等高线高程应为 0, 1, 2, 3…）。
     * 判断时使用 {@code tolerance = contourInterval * 0.01}（1% 容差）。
     *
     * @param contourInterval 等高距（须 &gt; 0，否则返回空列表）
     * @return 不符合等高距规律的高程值列表（升序）；全部合规时返回空列表
     */
    public List<Double> validate(double contourInterval) {
        if (contourInterval <= 0 || map.isEmpty()) return List.of();
        double tol = contourInterval * 0.01;
        List<Double> bad = new ArrayList<>();
        for (double elev : map.keySet()) {
            double rem = Math.abs(elev % contourInterval);
            // 取余后与 0 或 contourInterval 的距离
            double dist = Math.min(rem, Math.abs(rem - contourInterval));
            if (dist > tol) bad.add(elev);
        }
        return Collections.unmodifiableList(bad);
    }

    /** 返回简要统计描述（高程范围、等高线总数、高程层数）。 */
    public String summary() {
        if (map.isEmpty()) return "ContourSet{empty}";
        double[] r = range();
        return String.format("ContourSet{elevations=%d, contours=%d, range=[%.4f, %.4f]}",
                map.size(), size(), r[0], r[1]);
    }
}
