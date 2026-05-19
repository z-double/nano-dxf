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
 *   <li>打开 DXFReader，驱动 group code 流读取</li>
 *   <li>识别 SECTION 边界，通过 {@link SectionDispatcher} 分发到各段解析器</li>
 *   <li>汇总实体、错误、元数据，组装 {@link ParseResult}</li>
 * </ol>
 *
 * <p>资源与线程安全：每次 {@code parse()} 调用创建独立的 {@link DXFContext}，
 * 不共享任何可变状态，多线程并发解析不同文件时各自使用独立实例即可。
 *
 * <p>典型用法：
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

    /**
     * 解析 DXF 文件。
     * TODO Phase 1：编码检测在 DXFReader.open 中实现后，此处自动受益。
     */
    public ParseResult parse(Path path) throws IOException {
        try (DXFReader reader = DXFReader.open(path)) {
            return doParse(reader);
        }
    }

    /**
     * 从 Reader 解析（常用于单元测试中传入 StringReader）。
     */
    public ParseResult parse(Reader input) throws IOException {
        try (DXFReader reader = DXFReader.of(input)) {
            return doParse(reader);
        }
    }

    private ParseResult doParse(DXFReader reader) throws IOException {
        long startMs = System.currentTimeMillis();
        DXFContext ctx = new DXFContext();

        // 若调用方明确指定了 CRS，写入元数据并标注来源
        if (config.getCrs() != null) {
            ctx.metadata.setCrs(config.getCrs());
            ctx.metadata.setCrsSource("caller_specified");
        }

        // 驱动 group code 流：扫描 SECTION 边界并分发
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;

            if (pair.code() == 0 && "SECTION".equals(pair.value())) {
                GroupCodePair namePair = reader.next();
                if (namePair != null && namePair.code() == 2) {
                    dispatcher.dispatch(namePair.value(), reader, ctx);
                }
            }
            // 其他顶层 code（如 EOF）直接跳过
        }

        long elapsed = System.currentTimeMillis() - startMs;
        return ParseResult.builder()
                .metadata(ctx.metadata)
                .stats(new ParseStats(elapsed, 0, 0, 0))
                .build();
    }
}
