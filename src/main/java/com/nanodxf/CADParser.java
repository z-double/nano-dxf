package com.nanodxf;

import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.core.SectionDispatcher;
import com.nanodxf.model.DXFContext;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;

/**
 * NanoDXF 主解析入口。
 *
 * <p>职责：
 * <ol>
 *   <li>打开 DXFReader（自动编码检测），驱动 group code 流</li>
 *   <li>识别 SECTION 边界，通过 {@link SectionDispatcher} 分发到各段解析器</li>
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

        long elapsed = System.currentTimeMillis() - startMs;
        int entityCount  = ctx.entities.size();
        int warnCount    = (int) ctx.errors.stream()
                .filter(e -> e.getLevel() == ParseErrorLevel.WARN).count();

        // 未注册实体类型记录为 INFO 级别
        ctx.skippedEntityTypes.forEach(type ->
            ctx.errors.add(new ParseError(ParseErrorLevel.INFO, type, "",
                "未注册的实体类型，已跳过")));

        ParseResult.Builder builder = ParseResult.builder()
                .metadata(ctx.metadata)
                .stats(new ParseStats(elapsed, entityCount, warnCount, 0));

        ctx.entities.forEach(builder::addEntity);
        ctx.errors.forEach(builder::addError);

        return builder.build();
    }
}
