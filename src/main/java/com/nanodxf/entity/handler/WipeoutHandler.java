package com.nanodxf.entity.handler;

import com.nanodxf.EntityProperty;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.entity.EntityBuffer;
import com.nanodxf.entity.EntityHandler;
import com.nanodxf.geometry.GeometryBuilder;
import com.nanodxf.model.DXFContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Polygon;

import java.util.List;

/**
 * WIPEOUT（遮罩）实体解析器。
 *
 * <p>WIPEOUT 是一块白色/不透明矩形，用于遮住底图中的区域。
 * 本解析器从插入点（code 10/20/30）和 U/V 像素向量（code 11/12 系列）重建
 * 矩形包围框并输出为 JTS {@link Polygon}，同时将 {@link EntityProperty#WIPEOUT}
 * 设为 {@code true} 以区别于普通面实体。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 10/20/30 - 插入点（图像左下角，WCS）</li>
 *   <li>code 11/21/31 - U 方向单像素向量（X 轴方向，WCS）</li>
 *   <li>code 12/22/32 - V 方向单像素向量（Y 轴方向，WCS）</li>
 *   <li>code 13/23   - 像素宽度 / 像素高度（整数）</li>
 *   <li>code 340     - 关联 IMAGEDEF 对象句柄</li>
 *   <li>code 71      - 裁剪类型（0=矩形，1=多边形；本实现均输出矩形包围框）</li>
 * </ul>
 */
public class WipeoutHandler implements EntityHandler {

    @Override
    public List<CADEntity> handle(EntityBuffer buffer, DXFContext ctx) {
        String handle = buffer.getString(5, "");
        String layer  = buffer.getString(8, "0");
        String defH   = buffer.getString(340, "");

        double ix = buffer.getDouble(10, 0), iy = buffer.getDouble(20, 0), iz = buffer.getDouble(30, 0);
        double ux = buffer.getDouble(11, 1), uy = buffer.getDouble(21, 0);
        double vx = buffer.getDouble(12, 0), vy = buffer.getDouble(22, 1);
        double nw = buffer.getDouble(13, 0), nh = buffer.getDouble(23, 0);

        if (nw <= 0 || nh <= 0 || (Math.abs(ux) + Math.abs(uy) + Math.abs(vx) + Math.abs(vy)) < 1e-12)
            return List.of();

        // 4 corners: LL → LR → UR → UL → LL
        Coordinate c0 = new Coordinate(ix, iy, iz);
        Coordinate c1 = new Coordinate(ix + nw * ux, iy + nw * uy, iz);
        Coordinate c2 = new Coordinate(ix + nw * ux + nh * vx, iy + nw * uy + nh * vy, iz);
        Coordinate c3 = new Coordinate(ix + nh * vx, iy + nh * vy, iz);

        Polygon poly = GeometryBuilder.factory().createPolygon(
                new Coordinate[]{c0, c1, c2, c3, c0});

        CADEntity.Builder b = CADEntity.builder("WIPEOUT")
                .handle(handle).layer(layer).geometry(poly)
                .property(EntityProperty.WIPEOUT, Boolean.TRUE);
        if (!defH.isEmpty()) b.property(EntityProperty.IMAGE_DEF_HANDLE, defH);

        return List.of(b.build());
    }
}