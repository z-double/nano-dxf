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
 *   <li>汇总 DXFContext 中的实体、错误、元数据，组装 {@link ParseResult}</li>
 * </ol>
 *
 * <p>线程安全：每次 {@code parse()} 创建独立的 {@link DXFContext}，无共享可变状态，
 * 多线程并发解析不同文件时各自使用独立实例即可。
 *
 * <pre>{@code
 * ParseConfig config = ParseConfig.builder()
 *     .crs("EPSG:4545")
 *     .arcTolerance(0.001)
 *     .build();
 *
 * ParseResult result = new CADParser(config).parse(Paths.get("drawing.dxf"));
 * List<CADEntity> entities = result.getEntities();
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

    /** 解析 DXF 文件（自动检测编码）。 */
    public ParseResult parse(Path path) throws IOException {
        try (DXFReader reader = DXFReader.open(path)) {
            return doParse(reader);
        }
    }

    /** 从 Reader 解析，常用于单元测试（传入 StringReader）。 */
    public ParseResult parse(Reader input) throws IOException {
        try (DXFReader reader = DXFReader.of(input)) {
            return doParse(reader);
        }
    }

    private ParseResult doParse(DXFReader reader) throws IOException {
        long startMs = System.currentTimeMillis();
        DXFContext ctx = new DXFContext(config);

        // 若调用方明确指定 CRS，写入元数据并标注来源
        if (config.getCrs() != null) {
            ctx.metadata.setCrs(config.getCrs());
            ctx.metadata.setCrsSource("caller_specified");
        }

        // 驱动 group code 流：扫描 SECTION 边界，分发到对应 section 解析器
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0 && "SECTION".equals(pair.value())) {
                GroupCodePair namePair = reader.next();
                if (namePair != null && namePair.code() == 2) {
                    dispatcher.dispatch(namePair.value(), reader, ctx);
                }
            }
            // 其他顶层 code（如 0 EOF）直接跳过
        }

        long elapsed = System.currentTimeMillis() - startMs;
        int entityCount = ctx.entities.size();
        long errorCount = ctx.entities.stream()
                .filter(e -> e.geometry() == null).count(); // 几何为 null 视为解析异常

        ParseResult.Builder builder = ParseResult.builder()
                .metadata(ctx.metadata)
                .stats(new ParseStats(elapsed, entityCount, (int) errorCount, 0));

        ctx.entities.forEach(builder::addEntity);

        return builder.build();
    }
}
