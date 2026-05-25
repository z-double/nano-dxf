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
import java.util.Map;

/**
 * IMAGE（栅格图像引用）实体解析器。
 *
 * <p>IMAGE 实体在 DXF 中描述嵌入（引用）的光栅图像的位置、尺寸和裁剪信息，
 * 实际图像路径存储在 OBJECTS 段的 IMAGEDEF 对象（code 1）中，
 * IMAGE 实体通过 code 340 句柄引用。
 *
 * <p>本解析器从插入点和 U/V 像素向量重建图像矩形包围框，输出为 JTS {@link Polygon}。
 * 图像文件路径从 {@link DXFContext#objectData} 中按 IMAGEDEF 句柄查找；
 * 若 IMAGEDEF 不在 objectData 中（OBJECTS 段缺失或未解析），路径为空字符串。
 *
 * <p>关键 group code：
 * <ul>
 *   <li>code 10/20/30 - 插入点（图像左下角，WCS）</li>
 *   <li>code 11/21/31 - U 方向单像素向量</li>
 *   <li>code 12/22/32 - V 方向单像素向量</li>
 *   <li>code 13/23   - 像素宽度 / 像素高度</li>
 *   <li>code 340     - IMAGEDEF 对象句柄（图像路径来源）</li>
 * </ul>
 */
public class ImageHandler implements EntityHandler {

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

        // 从 objectData 中按 IMAGEDEF handle 查找图像路径（key "c1" = code 1 value）
        String imagePath = "";
        if (!defH.isEmpty()) {
            Map<String, Object> defData = ctx.objectData.get(defH);
            if (defData != null) {
                Object p = defData.get("c1");
                if (p instanceof String s) imagePath = s;
            }
        }

        CADEntity.Builder b = CADEntity.builder("IMAGE")
                .handle(handle).layer(layer).geometry(poly)
                .property(EntityProperty.IMAGE_PATH, imagePath)
                .property(EntityProperty.IMAGE_DEF_HANDLE, defH);

        return List.of(b.build());
    }
}
