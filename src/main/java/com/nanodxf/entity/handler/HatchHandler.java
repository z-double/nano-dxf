package com.nanodxf.entity.handler;

import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.Discretizer;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.geometry.GeometryValidator;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.*;

import java.util.ArrayList;
import java.util.List;

/**
 * HATCH（填充/剖面线）实体解析器。
 *
 * <p>HATCH 边界路径结构（关键 group code）：
 * <ul>
 *   <li>code 91 - 边界路径总数</li>
 *   <li>code 92 - 路径类型标志（bit 0=外边界）</li>
 *   <li>code 93 - 该路径的边段数</li>
 *   <li>code 72 - 边段类型（1=直线，2=圆弧，3=椭圆弧，4=样条）</li>
 * </ul>
 *
 * <p>已实现边段类型：
 * <ul>
 *   <li>类型 1（直线）：code 10/20（起点）、11/21（终点）</li>
 *   <li>类型 2（圆弧）：code 10/20（圆心）、40（半径）、50（起角）、51（终角）、73（CCW）</li>
 *   <li>类型 3/4（椭圆弧/样条）：TODO Phase 3</li>
 * </ul>
 *
 * <p>外/内边界判断：第一条路径为外边界（shell），其余为内边界（hole）。
 * 绕行方向由 {@link GeometryValidator#normalizeWindingOrder} 统一修正（RFC 7946）。
 */
public class HatchHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle    = buffer.getString(5, "");
        String layer     = buffer.getString(8, "0");
        double tolerance = ctx.config.getArcTolerance();

        List<GroupCodePair> pairs = buffer.all();
        int[] pos = {0}; // 顺序读取位置

        // 定位 code 91（边界路径数）
        int numPaths = 0;
        while (pos[0] < pairs.size()) {
            GroupCodePair pair = pairs.get(pos[0]++);
            if (pair.code() == 91) { numPaths = pair.asInt(); break; }
        }
        if (numPaths == 0) return List.of();

        List<LinearRing> shells = new ArrayList<>();
        List<LinearRing> holes  = new ArrayList<>();

        for (int p = 0; p < numPaths; p++) {
            parsePath(pairs, pos, tolerance, shells, holes, p == 0);
        }

        if (shells.isEmpty()) return List.of();

        Geometry geom = buildGeometry(shells, holes);
        return List.of(CADEntity.builder("HATCH")
                .handle(handle).layer(layer).geometry(geom).build());
    }

    // -------------------------------------------------------------------------
    // 边界路径解析
    // -------------------------------------------------------------------------

    private void parsePath(List<GroupCodePair> pairs, int[] pos, double tolerance,
                            List<LinearRing> shells, List<LinearRing> holes,
                            boolean isFirst) {
        // code 92：路径类型
        int pathType = 0;
        while (pos[0] < pairs.size()) {
            GroupCodePair pair = pairs.get(pos[0]++);
            if (pair.code() == 92) { pathType = pair.asInt(); break; }
        }

        // code 93：边段数
        int numEdges = 0;
        while (pos[0] < pairs.size()) {
            GroupCodePair pair = pairs.get(pos[0]++);
            if (pair.code() == 93) { numEdges = pair.asInt(); break; }
        }

        List<Coordinate> coords = new ArrayList<>();
        for (int e = 0; e < numEdges; e++) {
            // code 72：边段类型（跳过与边界无关的中间 code）
            int edgeType = 0;
            while (pos[0] < pairs.size()) {
                GroupCodePair pair = pairs.get(pos[0]);
                if (isPathBoundary(pair.code())) break;
                pos[0]++;
                if (pair.code() == 72) { edgeType = pair.asInt(); break; }
            }
            readEdge(edgeType, pairs, pos, coords, tolerance);
        }

        if (coords.size() < 2) return;

        // 强制闭合
        if (!coords.get(0).equals2D(coords.get(coords.size() - 1))) {
            coords.add(new Coordinate(coords.get(0)));
        }
        if (coords.size() < 4) return;

        LinearRing ring = GeometryBuilder.factory()
                .createLinearRing(coords.toArray(new Coordinate[0]));

        boolean isOuter = isFirst || (pathType & 1) != 0;
        ring = GeometryValidator.normalizeWindingOrder(ring, isOuter);

        if (isOuter) shells.add(ring);
        else         holes.add(ring);
    }

    private void readEdge(int edgeType, List<GroupCodePair> pairs, int[] pos,
                           List<Coordinate> coords, double tolerance) {
        switch (edgeType) {
            case 1 -> readLineEdge(pairs, pos, coords);
            case 2 -> readArcEdge(pairs, pos, coords, tolerance);
            // 3=椭圆弧, 4=样条: TODO Phase 3
        }
    }

    /** 直线边段：起点 code 10/20，终点 code 11/21。 */
    private void readLineEdge(List<GroupCodePair> pairs, int[] pos, List<Coordinate> coords) {
        double sx = 0, sy = 0, ex = 0, ey = 0;
        while (pos[0] < pairs.size()) {
            GroupCodePair pair = pairs.get(pos[0]);
            if (isEdgeBoundary(pair.code())) break;
            pos[0]++;
            switch (pair.code()) {
                case 10 -> sx = pair.asDouble();
                case 20 -> sy = pair.asDouble();
                case 11 -> ex = pair.asDouble();
                case 21 -> ey = pair.asDouble();
            }
        }
        if (coords.isEmpty()) coords.add(new Coordinate(sx, sy));
        coords.add(new Coordinate(ex, ey));
    }

    /** 圆弧边段：圆心 10/20，半径 40，起角 50，终角 51，CCW 标志 73。 */
    private void readArcEdge(List<GroupCodePair> pairs, int[] pos,
                              List<Coordinate> coords, double tolerance) {
        double cx = 0, cy = 0, r = 0, sa = 0, ea = 360;
        boolean ccw = true;
        while (pos[0] < pairs.size()) {
            GroupCodePair pair = pairs.get(pos[0]);
            if (isEdgeBoundary(pair.code())) break;
            pos[0]++;
            switch (pair.code()) {
                case 10 -> cx = pair.asDouble();
                case 20 -> cy = pair.asDouble();
                case 40 -> r  = pair.asDouble();
                case 50 -> sa = pair.asDouble();
                case 51 -> ea = pair.asDouble();
                case 73 -> ccw = pair.asInt() != 0;
            }
        }
        if (r <= 0) return;

        // CW 弧线：交换起终角以使 arc() 正向遍历
        if (!ccw) { double tmp = sa; sa = ea; ea = tmp; }

        List<Coordinate> arcPts = Discretizer.arc(
                new double[]{cx, cy}, r, sa, ea, tolerance);
        if (arcPts.isEmpty()) return;
        if (coords.isEmpty()) coords.add(arcPts.get(0));
        for (int i = 1; i < arcPts.size(); i++) coords.add(arcPts.get(i));
    }

    // -------------------------------------------------------------------------
    // 辅助
    // -------------------------------------------------------------------------

    /** code 72=边段类型，92=路径类型，93=边段数，三者均为边界标记，读边段时应停止。 */
    private boolean isEdgeBoundary(int code) {
        return code == 72 || code == 92 || code == 93;
    }

    private boolean isPathBoundary(int code) {
        return code == 92 || code == 93;
    }

    private Geometry buildGeometry(List<LinearRing> shells, List<LinearRing> holes) {
        if (shells.size() == 1) {
            return GeometryBuilder.factory().createPolygon(
                    shells.get(0), holes.toArray(new LinearRing[0]));
        }
        // 多外边界（少见）→ MultiPolygon，holes 归属第一个 shell
        Polygon[] polys = new Polygon[shells.size()];
        polys[0] = GeometryBuilder.factory().createPolygon(
                shells.get(0), holes.toArray(new LinearRing[0]));
        for (int i = 1; i < shells.size(); i++) {
            polys[i] = GeometryBuilder.factory().createPolygon(shells.get(i));
        }
        return GeometryBuilder.factory().createMultiPolygon(polys);
    }
}
