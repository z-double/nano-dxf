package com.nanodxf.output;

import com.nanodxf.EntityProperty;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.geometry.AciColorTable;
import org.locationtech.jts.geom.*;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 将 {@link CADEntity} 列表输出为 SVG 1.1 文件。
 *
 * <p>特性：
 * <ul>
 *   <li>几何映射：Point→{@code <circle>}，LineString→{@code <polyline>}，
 *       LinearRing→{@code <polygon>}，Polygon→{@code <path fill-rule="evenodd">}（含洞）</li>
 *   <li>图层分组：每个图层输出为 {@code <g id="layerName">} 元素</li>
 *   <li>颜色：优先使用 {@code colorRgb} 属性，其次 {@code colorAci} → ACI 色表，默认黑色</li>
 *   <li>ViewBox：从实体几何包围盒自动计算（不依赖 DXF 的 $EXTMIN/$EXTMAX）</li>
 *   <li>Y 轴：DXF Y 向上，SVG Y 向下，通过 {@code transform="scale(1,-1)"} 翻转</li>
 *   <li>纯 Java 实现，零额外依赖</li>
 * </ul>
 */
public class SvgWriter {

    private static final String DEFAULT_COLOR = "#000000";

    private final SvgWriteConfig config;

    public SvgWriter() {
        this(SvgWriteConfig.defaults());
    }

    public SvgWriter(SvgWriteConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /** 将实体列表写出到文件。 */
    public void write(List<CADEntity> entities, Path path) throws IOException {
        String svg = serialize(entities);
        Files.writeString(path, svg, StandardCharsets.UTF_8);
    }

    /** 将实体列表序列化为 SVG 字符串。 */
    public String serialize(List<CADEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return buildEmptySvg();
        }

        // 计算包围盒（从实际几何）
        Envelope bbox = computeBbox(entities);
        if (bbox.isNull()) return buildEmptySvg();

        double padding = Math.max(bbox.getWidth(), bbox.getHeight()) * config.getPaddingFraction();
        double vxMin = bbox.getMinX() - padding;
        double vyMin = bbox.getMinY() - padding;
        double vWidth  = bbox.getWidth() + 2 * padding;
        double vHeight = bbox.getHeight() + 2 * padding;
        if (vWidth < 1e-9 || vHeight < 1e-9) { vWidth = 1; vHeight = 1; }

        int svgW = config.getWidth();
        int svgH = (int) Math.round(svgW * (vHeight / vWidth));
        if (svgH < 1) svgH = 1;

        // 按图层分组
        Map<String, List<CADEntity>> byLayer = groupByLayer(entities);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append(String.format(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" " +
                "viewBox=\"%.6f %.6f %.6f %.6f\">\n",
                svgW, svgH,
                vxMin, -(vyMin + vHeight), vWidth, vHeight));

        // 背景矩形
        if (config.getBackground() != null) {
            sb.append(String.format(
                    "  <rect x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\" fill=\"%s\"/>\n",
                    vxMin, -(vyMin + vHeight), vWidth, vHeight,
                    escapeAttr(config.getBackground())));
        }

        // DXF Y 向上 → SVG Y 向下：在整体 g 上加 scale(1,-1)，Y 轴翻转
        sb.append("  <g transform=\"scale(1,-1)\">\n");

        // 确定图层渲染顺序
        List<String> orderedLayers = buildLayerOrder(byLayer.keySet());
        for (String layer : orderedLayers) {
            List<CADEntity> layerEntities = byLayer.get(layer);
            if (layerEntities == null || layerEntities.isEmpty()) continue;

            sb.append(String.format("    <g id=\"%s\">\n", escapeAttr(layer)));
            for (CADEntity e : layerEntities) {
                renderEntity(e, sb);
            }
            sb.append("    </g>\n");
        }

        sb.append("  </g>\n");
        sb.append("</svg>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // 实体渲染
    // -------------------------------------------------------------------------

    private void renderEntity(CADEntity e, StringBuilder sb) {
        Geometry geom = e.geometry();
        if (geom == null || geom.isEmpty()) return;
        String color = resolveColor(e);
        renderGeometry(geom, color, e, sb);
    }

    private void renderGeometry(Geometry geom, String color, CADEntity e, StringBuilder sb) {
        if (geom instanceof Point pt) {
            renderPoint(pt, color, sb);
        } else if (geom instanceof LinearRing ring) {
            renderLinearRing(ring, color, sb);
        } else if (geom instanceof LineString ls) {
            renderLineString(ls, color, sb);
        } else if (geom instanceof Polygon poly) {
            renderPolygon(poly, color, sb);
        } else if (geom instanceof MultiPolygon mp) {
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                renderGeometry(mp.getGeometryN(i), color, e, sb);
            }
        } else if (geom instanceof GeometryCollection gc) {
            for (int i = 0; i < gc.getNumGeometries(); i++) {
                renderGeometry(gc.getGeometryN(i), color, e, sb);
            }
        }
    }

    private void renderPoint(Point pt, String color, StringBuilder sb) {
        sb.append(String.format(
                "      <circle cx=\"%.6f\" cy=\"%.6f\" r=\"%.4f\" fill=\"%s\" stroke=\"none\"/>\n",
                pt.getX(), pt.getY(),
                config.getPointRadiusPx(),
                color));
    }

    private void renderLineString(LineString ls, String color, StringBuilder sb) {
        sb.append(String.format(
                "      <polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.4f\"/>\n",
                coordsToPoints(ls.getCoordinates()),
                color,
                config.getStrokeWidthPx()));
    }

    private void renderLinearRing(LinearRing ring, String color, StringBuilder sb) {
        sb.append(String.format(
                "      <polygon points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.4f\"/>\n",
                coordsToPoints(ring.getCoordinates()),
                color,
                config.getStrokeWidthPx()));
    }

    private void renderPolygon(Polygon poly, String color, StringBuilder sb) {
        // 使用 path + fill-rule="evenodd" 支持洞
        StringBuilder d = new StringBuilder();
        appendRingPath(d, poly.getExteriorRing().getCoordinates());
        for (int i = 0; i < poly.getNumInteriorRing(); i++) {
            appendRingPath(d, poly.getInteriorRingN(i).getCoordinates());
        }
        sb.append(String.format(
                "      <path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.4f\" fill-rule=\"evenodd\"/>\n",
                d.toString().trim(),
                color,
                config.getStrokeWidthPx()));
    }

    private static void appendRingPath(StringBuilder d, Coordinate[] coords) {
        if (coords.length == 0) return;
        d.append(String.format("M %.6f %.6f", coords[0].x, coords[0].y));
        for (int i = 1; i < coords.length; i++) {
            d.append(String.format(" L %.6f %.6f", coords[i].x, coords[i].y));
        }
        d.append(" Z ");
    }

    // -------------------------------------------------------------------------
    // 颜色解析
    // -------------------------------------------------------------------------

    private static String resolveColor(CADEntity e) {
        // 优先 colorRgb（True Color）
        Object rgbObj = e.getProperties().get(EntityProperty.COLOR_RGB);
        if (rgbObj instanceof int[] rgb && rgb.length >= 3) {
            return String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
        }
        // 其次 colorAci → ACI 色表
        Object aciObj = e.getProperties().get(EntityProperty.COLOR_ACI);
        if (aciObj instanceof Integer aci) {
            int[] rgb = AciColorTable.toRgb(aci);
            if (rgb != null) return String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
        }
        return DEFAULT_COLOR;
    }

    // -------------------------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------------------------

    private static Envelope computeBbox(List<CADEntity> entities) {
        Envelope env = new Envelope();
        for (CADEntity e : entities) {
            Geometry g = e.geometry();
            if (g != null && !g.isEmpty()) env.expandToInclude(g.getEnvelopeInternal());
        }
        return env;
    }

    private Map<String, List<CADEntity>> groupByLayer(List<CADEntity> entities) {
        Map<String, List<CADEntity>> map = new LinkedHashMap<>();
        for (CADEntity e : entities) {
            String layer = e.getLayer() != null ? e.getLayer() : "0";
            map.computeIfAbsent(layer, k -> new ArrayList<>()).add(e);
        }
        return map;
    }

    private List<String> buildLayerOrder(Set<String> layers) {
        List<String> ordered = new ArrayList<>(config.getLayerOrder());
        // 规定顺序中出现的图层先渲染，其余按自然排序追加
        List<String> rest = new ArrayList<>(layers);
        rest.removeAll(ordered);
        Collections.sort(rest);
        ordered.addAll(rest);
        // 过滤掉实际不存在的图层
        ordered.retainAll(layers);
        return ordered;
    }

    private static String coordsToPoints(Coordinate[] coords) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%.6f,%.6f", coords[i].x, coords[i].y));
        }
        return sb.toString();
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String buildEmptySvg() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + config.getWidth() +
               "\" height=\"" + config.getWidth() + "\"/>\n";
    }
}
