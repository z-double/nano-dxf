package com.nanodxf.stat;

import com.nanodxf.entity.CADEntity;
import org.locationtech.jts.geom.*;

import java.util.*;

/**
 * 单图层统计行，由 {@link LayerStats#compute} 产生（不可变）。
 */
public final class LayerStatRow {

    private final String               layer;
    private final int                  count;
    private final double               totalLength;
    private final double               totalArea;
    private final Map<String, Integer> typeBreakdown;

    LayerStatRow(String layer, int count, double totalLength, double totalArea,
                 Map<String, Integer> typeBreakdown) {
        this.layer         = layer;
        this.count         = count;
        this.totalLength   = totalLength;
        this.totalArea     = totalArea;
        this.typeBreakdown = Collections.unmodifiableMap(new LinkedHashMap<>(typeBreakdown));
    }

    /** 图层名。 */
    public String getLayer()                        { return layer; }
    /** 该图层实体总数。 */
    public int getCount()                           { return count; }
    /** 线类实体总长（坐标单位）。 */
    public double getTotalLength()                  { return totalLength; }
    /** 面类实体总面积（坐标单位²）。 */
    public double getTotalArea()                    { return totalArea; }
    /** 按实体类型统计数量（不可变）。 */
    public Map<String, Integer> getTypeBreakdown()  { return typeBreakdown; }

    /** 格式化为固定宽度的表格行（图层 / 实体数 / 总长 / 总面积）。 */
    public String toTableRow() {
        return String.format("%-24s %6d %16.4f %16.4f",
                layer, count, totalLength, totalArea);
    }

    // -------------------------------------------------------------------------
    // 内部累加器（包私有，供 LayerStats 使用）
    // -------------------------------------------------------------------------

    static final class Accumulator {
        final String layer;
        int    count      = 0;
        double totalLength = 0;
        double totalArea   = 0;
        final Map<String, Integer> typeBreakdown = new LinkedHashMap<>();

        Accumulator(String layer) { this.layer = layer; }

        void add(CADEntity e) {
            count++;
            typeBreakdown.merge(e.getType(), 1, Integer::sum);
            Geometry g = e.geometry();
            if (g == null) return;
            if (g instanceof LineString || g instanceof LinearRing)
                totalLength += g.getLength();
            else if (g instanceof Polygon || g instanceof MultiPolygon)
                totalArea += g.getArea();
        }

        LayerStatRow build() {
            return new LayerStatRow(layer, count, totalLength, totalArea, typeBreakdown);
        }

        LayerStatRow build(double lengthFactor, double areaFactor) {
            return new LayerStatRow(layer, count,
                    totalLength * lengthFactor, totalArea * areaFactor, typeBreakdown);
        }
    }
}
