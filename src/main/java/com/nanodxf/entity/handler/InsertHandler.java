package com.nanodxf.entity.handler;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.CADBlock;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.util.GeometryTransformer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * INSERT 实体解析器：将块引用递归展开为实际几何实体。
 *
 * <p>展开流程：
 * <ol>
 *   <li>在 {@code ctx.blocks} 中查找块定义</li>
 *   <li>对块内每个实体应用仿射变换（Scale → Rotate → Translate）</li>
 *   <li>若块内含嵌套 INSERT，递归展开并组合变换</li>
 *   <li>ATTRIB 子实体的 tag-value 对注入到展开实体的 properties["insertAttributes"]</li>
 * </ol>
 *
 * <p><b>循环引用检测</b>：使用路径集合（{@link Deque}）而非深度计数——
 * 路径集合能检测 A→B→A 这类只有 2 层但真正循环的引用；深度计数只能捕获超深嵌套。
 * 同时保留深度 ≤ 16 的保护（防文件损坏导致的异常递归）。
 *
 * <p><b>坐标变换公式</b>（2D 仿射 + Z 缩放）：
 * <pre>
 *   double rad = Math.toRadians(rotation);
 *   x' = scaleX * (x * cos - y * sin) + insertX
 *   y' = scaleY * (x * sin + y * cos) + insertY
 *   z' = scaleZ * z + insertZ
 * </pre>
 *
 * <p>块定义中 INSERT 的变换采用<b>占位策略</b>：
 * {@link com.nanodxf.section.BlocksParser} 在解析 BLOCKS 段时，遇到 INSERT 不展开，
 * 只存储变换参数。InsertHandler 在 ENTITIES 阶段看到这些占位实体时，递归组合变换展开。
 * 这避免了 ctx.blocks 尚未完整时的鸡生蛋问题。
 */
public class InsertHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle    = buffer.getString(5, "");
        String layer     = buffer.getString(8, "0");
        String blockName = buffer.getString(2, "");

        double ix  = buffer.getDouble(10, 0);
        double iy  = buffer.getDouble(20, 0);
        double iz  = buffer.getDouble(30, 0);
        double sx  = buffer.getDouble(41, 1.0);
        double sy  = buffer.getDouble(42, 1.0);
        double sz  = buffer.getDouble(43, 1.0);
        double rot = buffer.getDouble(50, 0);

        // 收集 ATTRIB 子实体（tag → value）
        Map<String, String> attributes = new LinkedHashMap<>();
        for (EntityBuffer attrib : buffer.getChildren()) {
            String tag   = attrib.getString(2, "");
            String value = attrib.getString(1, "");
            if (!tag.isEmpty()) attributes.put(tag, value);
        }

        // 查找块定义
        CADBlock block = ctx.blocks.get(blockName);
        if (block == null || block.entities().isEmpty()) {
            // 块未定义或为空：退化为插入点 Point
            return List.of(buildFallback(handle, layer, blockName, ix, iy, iz, sx, sy, rot, attributes));
        }

        // 递归展开
        List<CADEntity> result = expandBlock(
            block, handle, layer, sx, sy, sz, rot, ix, iy, iz, attributes, ctx, new ArrayDeque<>());

        // 若展开结果为空（全部被过滤/循环引用）也退化为插入点
        if (result.isEmpty()) {
            return List.of(buildFallback(handle, layer, blockName, ix, iy, iz, sx, sy, rot, attributes));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 块展开（递归）
    // -------------------------------------------------------------------------

    /**
     * 递归展开块定义，对每个实体应用仿射变换。
     *
     * @param block      要展开的块定义
     * @param parentHandle 父 INSERT 的 handle（用于生成子实体 handle）
     * @param insertLayer INSERT 的图层（覆盖块内实体的图层，使 INSERT 图层生效）
     * @param sx/sy/sz   比例系数
     * @param rot        旋转角度（度）
     * @param ix/iy/iz   插入点坐标
     * @param attributes ATTRIB 属性 map（注入到文字类实体）
     * @param ctx        解析上下文（用于查找嵌套块）
     * @param path       当前展开路径（循环引用检测）
     */
    private List<CADEntity> expandBlock(CADBlock block, String parentHandle,
                                         String insertLayer,
                                         double sx, double sy, double sz,
                                         double rot, double ix, double iy, double iz,
                                         Map<String, String> attributes,
                                         DXFContext ctx, Deque<String> path) {
        // 循环引用检测：路径中已存在此块名 → 跳过
        if (path.contains(block.getName())) {
            return List.of();
        }
        // 超深嵌套保护（防文件损坏导致的异常递归）
        if (path.size() > 16) {
            return List.of();
        }

        path.push(block.getName());
        try {
            List<CADEntity> result = new ArrayList<>();

            for (CADEntity entity : block.entities()) {
                if ("INSERT".equals(entity.getType())) {
                    // 递归展开嵌套 INSERT（使用占位实体中保存的变换参数）
                    result.addAll(expandNestedInsert(entity, parentHandle, insertLayer,
                        sx, sy, sz, rot, ix, iy, iz, attributes, ctx, path));
                } else {
                    // 普通实体：应用仿射变换
                    Geometry transformed = applyTransform(entity.geometry(), sx, sy, sz, rot, ix, iy, iz);
                    if (transformed == null) continue;

                    CADEntity.Builder b = CADEntity.builder(entity.getType())
                        .handle(entity.getHandle() + "@" + parentHandle)
                        // INSERT 的图层覆盖块内实体图层（DXF 约定）
                        .layer(insertLayer.isEmpty() ? entity.getLayer() : insertLayer)
                        .geometry(transformed);

                    // 继承块内实体的属性
                    entity.getProperties().forEach(b::property);

                    // 将 ATTRIB 注入文字类实体
                    if (!attributes.isEmpty()
                            && ("TEXT".equals(entity.getType()) || "MTEXT".equals(entity.getType()))) {
                        b.property("insertAttributes", attributes);
                    }

                    result.add(b.build());
                }
            }
            return result;
        } finally {
            path.pop();
        }
    }

    /**
     * 展开块内的嵌套 INSERT 占位实体。
     * 先读取占位实体中的变换参数，再与外层变换组合，递归展开嵌套块。
     */
    private List<CADEntity> expandNestedInsert(CADEntity placeholder, String parentHandle,
                                                String insertLayer,
                                                double outerSX, double outerSY, double outerSZ,
                                                double outerRot, double outerIX, double outerIY, double outerIZ,
                                                Map<String, String> attributes,
                                                DXFContext ctx, Deque<String> path) {
        String nestedName = (String) placeholder.getProperties().get("blockName");
        if (nestedName == null) return List.of();

        CADBlock nested = ctx.blocks.get(nestedName);
        if (nested == null) return List.of();

        // 嵌套 INSERT 自身的变换参数（存在占位实体的 properties 中）
        double nSX  = getDouble(placeholder, "scaleX",   1.0);
        double nSY  = getDouble(placeholder, "scaleY",   1.0);
        double nSZ  = getDouble(placeholder, "scaleZ",   1.0);
        double nRot = getDouble(placeholder, "rotation", 0.0);
        // 嵌套 INSERT 的插入点（在父块坐标系中）
        double nX = placeholder.geometry().getCoordinate().x;
        double nY = placeholder.geometry().getCoordinate().y;
        double nZ = placeholder.geometry().getCoordinate().getZ();
        if (Double.isNaN(nZ)) nZ = 0;

        // 将嵌套插入点转换到当前（外层）坐标系
        double[] transformed = applyTransformPoint(nX, nY, nZ, outerSX, outerSY, outerSZ, outerRot, outerIX, outerIY, outerIZ);

        // 组合变换：比例相乘，旋转角相加
        double composedSX  = outerSX * nSX;
        double composedSY  = outerSY * nSY;
        double composedSZ  = outerSZ * nSZ;
        double composedRot = outerRot + nRot;

        return expandBlock(nested, parentHandle, insertLayer,
            composedSX, composedSY, composedSZ, composedRot,
            transformed[0], transformed[1], transformed[2],
            attributes, ctx, path);
    }

    // -------------------------------------------------------------------------
    // 仿射变换
    // -------------------------------------------------------------------------

    /**
     * 将 JTS 几何的所有坐标应用仿射变换（Scale → Rotate → Translate），
     * 使用 {@link GeometryTransformer} 保持几何类型不变（Polygon 洞、MultiGeometry 等）。
     */
    private Geometry applyTransform(Geometry geom,
                                     final double scaleX, final double scaleY, final double scaleZ,
                                     final double rotation,
                                     final double tx, final double ty, final double tz) {
        if (geom == null) return null;
        final double rad = Math.toRadians(rotation);
        final double cos = Math.cos(rad), sin = Math.sin(rad);

        return new GeometryTransformer() {
            @Override
            protected CoordinateSequence transformCoordinates(CoordinateSequence coords, Geometry parent) {
                CoordinateSequence copy = coords.copy();
                for (int i = 0; i < copy.size(); i++) {
                    double x = coords.getX(i);
                    double y = coords.getY(i);
                    double z = coords.getOrdinate(i, 2); // Z
                    // Scale → Rotate → Translate
                    double sx = x * scaleX, sy = y * scaleY;
                    copy.setOrdinate(i, 0, sx * cos - sy * sin + tx); // X'
                    copy.setOrdinate(i, 1, sx * sin + sy * cos + ty); // Y'
                    if (!Double.isNaN(z)) {
                        copy.setOrdinate(i, 2, z * scaleZ + tz);      // Z'
                    }
                }
                return copy;
            }
        }.transform(geom);
    }

    /** 对单个点应用仿射变换，返回 [x', y', z']。 */
    private double[] applyTransformPoint(double x, double y, double z,
                                          double scaleX, double scaleY, double scaleZ,
                                          double rotation, double tx, double ty, double tz) {
        double rad = Math.toRadians(rotation);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double sx = x * scaleX, sy = y * scaleY;
        return new double[]{
            sx * cos - sy * sin + tx,
            sx * sin + sy * cos + ty,
            (Double.isNaN(z) ? 0 : z) * scaleZ + tz
        };
    }

    // -------------------------------------------------------------------------
    // 退化输出（块未定义时的兜底）
    // -------------------------------------------------------------------------

    private CADEntity buildFallback(String handle, String layer, String blockName,
                                     double ix, double iy, double iz,
                                     double sx, double sy, double rotation,
                                     Map<String, String> attributes) {
        Point geom = GeometryBuilder.factory().createPoint(new Coordinate(ix, iy, iz));
        CADEntity.Builder b = CADEntity.builder("INSERT")
            .handle(handle).layer(layer).geometry(geom)
            .property("blockName", blockName)
            .property("scaleX",    sx)
            .property("scaleY",    sy)
            .property("rotation",  rotation);
        if (!attributes.isEmpty()) b.property("attributes", attributes);
        return b.build();
    }

    private double getDouble(CADEntity entity, String key, double defaultVal) {
        Object v = entity.getProperties().get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return defaultVal;
    }
}
