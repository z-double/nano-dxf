package com.nanodxf.geometry;

/**
 * 几何简化算法选择。
 *
 * @see GeomSimplifier
 */
public enum SimplifyMode {

    /**
     * 拓扑保持简化（JTS {@code TopologyPreservingSimplifier}）。
     * 保证 {@link org.locationtech.jts.geom.LinearRing} 简化后仍为有效闭合环。
     * 适用于等高线、建筑轮廓等闭合要素，<b>默认模式</b>。
     */
    TOPOLOGY_PRESERVING,

    /**
     * Douglas-Peucker 简化（JTS {@code DouglasPeuckerSimplifier}）。
     * 速度更快，但可能破坏闭合环的拓扑（使其自相交）。
     * 仅适用于开放 {@link org.locationtech.jts.geom.LineString}。
     */
    DOUGLAS_PEUCKER
}
