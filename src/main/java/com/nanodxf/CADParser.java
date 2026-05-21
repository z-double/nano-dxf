package com.nanodxf;

import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.core.SectionDispatcher;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.geometry.UnitConverter;
import com.nanodxf.model.DXFContext;
import com.nanodxf.section.EntitiesParser;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.GeometryTransformer;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.Spliterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * NanoDXF 主解析入口。
 *
 * <p>职责：
 * <ol>
 *   <li>打开 DXFReader（自动编码检测），驱动 group code 流</li>
 *   <li>识别 SECTION 边界，通过 {@link SectionDispatcher} 分发到各段解析器</li>
 *   <li>单位换算：若 {@code $INSUNITS} 非米，将所有实体坐标换算为米</li>
 *   <li>汇总 DXFContext 中的实体、错误、跳过类型、元数据，组装 {@link ParseResult}</li>
 * </ol>
 *
 * <p>线程安全：每次 {@code parse()} 创建独立的 {@link DXFContext}，无共享可变状态。
 *
 * <pre>{@code
 * ParseConfig config = ParseConfig.builder()
 *     .crs("EPSG:4545")
 *     .arcTolerance(0.001)
 *     .build();
 *
 * ParseResult result = new CADParser(config).parse(Paths.get("drawing.dxf"));
 * List<CADEntity> entities = result.getEntities();
 * result.getErrors().forEach(System.err::println);
 * }</pre>
 */
public class CADParser {

    private static final UnitConverter UNIT_CONVERTER = new UnitConverter();

    private final ParseConfig config;
    private final SectionDispatcher dispatcher = new SectionDispatcher();

    public CADParser(ParseConfig config) {
        this.config = config;
    }

    /** 使用默认配置创建解析器。 */
    public CADParser() {
        this(ParseConfig.defaults());
    }

    /** 解析 DXF 文件（自动检测编码，支持 GBK / UTF-8）。 */
    public ParseResult parse(Path path) throws IOException {
        try (DXFReader reader = DXFReader.open(path)) {
            return doParse(reader);
        }
    }

    /** 从 Reader 解析（常用于单元测试，传入 StringReader）。 */
    public ParseResult parse(Reader input) throws IOException {
        try (DXFReader reader = DXFReader.of(input)) {
            return doParse(reader);
        }
    }

    /**
     * 流式解析 DXF 文件，惰性返回模型空间实体。
     *
     * <p>两阶段策略：
     * <ol>
     *   <li><b>快速阶段</b>：全量读取 HEADER / TABLES / BLOCKS / OBJECTS 段（通常远小于 ENTITIES 段），
     *       加载块定义和图层信息到内存。</li>
     *   <li><b>惰性阶段</b>：逐实体解析 ENTITIES 段，INSERT 展开即时执行，不全量缓冲。</li>
     * </ol>
     *
     * <p><b>重要</b>：返回的 {@link Stream} 持有文件句柄，<b>必须</b>在 try-with-resources 中使用：
     * <pre>{@code
     * try (Stream<CADEntity> stream = new CADParser().parseStream(path)) {
     *     stream.filter(e -> CADEntity.Types.LWPOLYLINE.equals(e.getType()))
     *           .limit(10_000)
     *           .forEach(processor::accept);
     * }
     * }</pre>
     *
     * @param path DXF 文件路径
     * @return 模型空间实体的惰性流（INSERT 已展开，图纸空间已过滤）
     * @throws IOException 文件无法打开或编码检测失败时抛出
     */
    public Stream<CADEntity> parseStream(Path path) throws IOException {
        DXFReader reader = DXFReader.open(path);
        try {
            DXFContext ctx = new DXFContext(config);
            if (config.getCrs() != null) {
                ctx.metadata.setCrs(config.getCrs());
                ctx.metadata.setCrsSource("caller_specified");
            }

            // Phase 1: 逐 SECTION 分发，直到 ENTITIES 段
            boolean atEntities = false;
            while (reader.hasNext()) {
                GroupCodePair pair = reader.next();
                if (pair == null) break;
                if (pair.code() == 0 && "SECTION".equals(pair.value())) {
                    GroupCodePair namePair = reader.next();
                    if (namePair != null && namePair.code() == 2) {
                        String sectionName = namePair.value();
                        if ("ENTITIES".equals(sectionName)) {
                            atEntities = true;
                            break;
                        }
                        dispatcher.dispatch(sectionName, reader, ctx);
                    }
                }
            }

            if (!atEntities) {
                reader.close();
                return Stream.empty();
            }

            // Phase 2: 创建惰性实体流
            EntitiesParser ep = new EntitiesParser();
            Spliterator<CADEntity> spliterator = ep.spliterator(reader, ctx);

            return StreamSupport.stream(spliterator, false)
                    .onClose(() -> { try { reader.close(); } catch (IOException ignored) {} });

        } catch (IOException e) {
            reader.close();
            throw e;
        }
    }

    private ParseResult doParse(DXFReader reader) throws IOException {
        long startMs = System.currentTimeMillis();
        DXFContext ctx = new DXFContext(config);

        if (config.getCrs() != null) {
            ctx.metadata.setCrs(config.getCrs());
            ctx.metadata.setCrsSource("caller_specified");
        }

        // 驱动 group code 流，按 SECTION 边界分发
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0 && "SECTION".equals(pair.value())) {
                GroupCodePair namePair = reader.next();
                if (namePair != null && namePair.code() == 2) {
                    dispatcher.dispatch(namePair.value(), reader, ctx);
                }
            }
        }

        // 单位换算：将所有坐标统一换算为米
        if (config.isApplyUnitConversion()) {
            applyUnitConversion(ctx);
        }

        long elapsed = System.currentTimeMillis() - startMs;
        int entityCount = ctx.entities.size();
        int errorCount  = (int) ctx.errors.stream()
                .filter(e -> e.getLevel() == ParseErrorLevel.WARN).count();

        // 未注册实体类型记录为 INFO 级别（在 errorCount 之后计算，不混入 error 计数）
        ctx.skippedEntityTypes.forEach(type ->
            ctx.errors.add(new ParseError(ParseErrorLevel.INFO, type, "",
                "未注册的实体类型，已跳过")));
        int infoCount = ctx.skippedEntityTypes.size();

        ParseResult.Builder builder = ParseResult.builder()
                .metadata(ctx.metadata)
                .stats(new ParseStats(elapsed, entityCount, errorCount, infoCount));

        ctx.entities.forEach(builder::addEntity);
        ctx.errors.forEach(builder::addError);

        return builder.build();
    }

    // -------------------------------------------------------------------------
    // 单位换算
    // -------------------------------------------------------------------------

    /**
     * 根据 {@code $INSUNITS} 将所有实体坐标换算为米。
     * 系数为 1.0（已是米或未知单位）时跳过，不做任何修改。
     */
    private void applyUnitConversion(DXFContext ctx) {
        int insunits = ctx.metadata.getInsunits();
        double factor = UNIT_CONVERTER.scaleFactor(insunits);
        if (factor == 1.0) return; // 无需换算

        ctx.entities.replaceAll(e -> scaleEntity(e, factor));
    }

    /** 对单个实体的所有坐标乘以换算系数，同时更新 elevation 属性。 */
    private static CADEntity scaleEntity(CADEntity entity, double factor) {
        if (entity.geometry() == null) return entity;

        Geometry scaled = new GeometryTransformer() {
            @Override
            protected CoordinateSequence transformCoordinates(
                    CoordinateSequence coords, Geometry parent) {
                CoordinateSequence copy = coords.copy();
                for (int i = 0; i < copy.size(); i++) {
                    copy.setOrdinate(i, 0, coords.getX(i) * factor);
                    copy.setOrdinate(i, 1, coords.getY(i) * factor);
                    double z = coords.getOrdinate(i, 2);
                    if (!Double.isNaN(z)) copy.setOrdinate(i, 2, z * factor);
                }
                return copy;
            }
        }.transform(entity.geometry());

        CADEntity result = entity.withGeometry(scaled);

        // elevation 属性与 Z 坐标同步换算
        Object elev = result.getProperties().get("elevation");
        if (elev instanceof Number n) {
            result = result.withProperty("elevation", n.doubleValue() * factor);
        }
        return result;
    }
}
