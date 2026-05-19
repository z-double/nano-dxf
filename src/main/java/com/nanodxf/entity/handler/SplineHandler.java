package com.nanodxf.entity.handler;

import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.Discretizer;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import java.util.ArrayList;
import java.util.List;

/**
 * SPLINE 实体解析器（B 样条曲线）。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 71 - 阶数 degree（阶数 = 次数 + 1，3 次样条 degree=3）</li>
 *   <li>code 72 - 节点向量长度（knot count）</li>
 *   <li>code 73 - 控制点数量</li>
 *   <li>code 40 - 节点值（重复出现，按序排列，构成节点向量）</li>
 *   <li>code 10/20/30 - 控制点坐标（重复，X/Y/Z 三码一组）</li>
 * </ul>
 *
 * <p>使用 {@link Discretizer#spline} 的 de Boor 算法求值，均匀参数采样。
 * 控制点的顺序解析：以 code 10 触发，配合 code 20/30 组成三维点；
 * 若文件缺少 code 30（二维样条），Z 默认为 0。
 *
 * <p>有理样条（code 41 权重）暂不支持，按非有理处理（等权重 1.0）。
 */
public class SplineHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");
        int degree    = buffer.getInt(71, 3);

        // 节点向量：code 40（重复）
        double[] knots = buffer.all(40).stream()
                .mapToDouble(v -> {
                    try { return Double.parseDouble(v.trim()); }
                    catch (NumberFormatException e) { return 0.0; }
                })
                .toArray();

        // 控制点：code 10/20/30 按出现顺序组成三维点
        List<double[]> ctrlPtList = parseControlPoints(buffer);

        if (ctrlPtList.isEmpty() || knots.length < 2) return List.of();

        double[][] ctrlPts = ctrlPtList.toArray(new double[0][]);

        List<Coordinate> coords = Discretizer.spline(
                degree, knots, ctrlPts, ctx.config.getArcTolerance());

        if (coords.size() < 2) return List.of();

        LineString geom = GeometryBuilder.factory()
                .createLineString(coords.toArray(new Coordinate[0]));

        return List.of(CADEntity.builder("SPLINE")
                .handle(handle).layer(layer).geometry(geom).build());
    }

    /**
     * 从 buffer 中顺序解析控制点坐标（code 10/20/30 三码一组）。
     * 若缺少 code 30（二维样条），以 code 10+20 配对，Z 默认 0。
     */
    private List<double[]> parseControlPoints(EntityBuffer buffer) {
        List<double[]> pts = new ArrayList<>();
        double px = 0, py = 0;
        boolean hasX = false, hasY = false;

        for (GroupCodePair pair : buffer.all()) {
            switch (pair.code()) {
                case 10 -> { px = pair.asDouble(); hasX = true; }
                case 20 -> { py = pair.asDouble(); hasY = true; }
                case 30 -> {
                    if (hasX && hasY) {
                        pts.add(new double[]{px, py, pair.asDouble()});
                        hasX = hasY = false;
                    }
                }
            }
        }

        // 兜底：若始终未收到 code 30（二维样条），从 code 10/20 列表构建
        if (pts.isEmpty()) {
            List<String> xs = buffer.all(10);
            List<String> ys = buffer.all(20);
            int count = Math.min(xs.size(), ys.size());
            for (int i = 0; i < count; i++) {
                try {
                    pts.add(new double[]{
                        Double.parseDouble(xs.get(i).trim()),
                        Double.parseDouble(ys.get(i).trim()),
                        0.0
                    });
                } catch (NumberFormatException ignored) {}
            }
        }
        return pts;
    }
}
