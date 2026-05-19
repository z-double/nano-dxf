package com.nanodxf.core;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * DXF ASCII 文件的低级读取器，输出 {@link GroupCodePair} 流。
 *
 * <p>DXF 格式的基本单元是"code 行 + value 行"的逐行对：
 * <pre>
 *   0          ← group code（整数）
 *   LINE       ← value
 *   8          ← group code
 *   道路中心线  ← value
 * </pre>
 *
 * <p>支持 {@link #pushBack} 回退一对，供上层解析器预读后回退（lookahead）。
 *
 * <p>资源管理：实现 {@link Closeable}，应通过 try-with-resources 使用：
 * <pre>{@code
 * try (DXFReader reader = DXFReader.open(path)) {
 *     while (reader.hasNext()) { ... }
 * }
 * }</pre>
 *
 * <p><b>TODO（Phase 1）</b>：{@link #open(Path)} 目前使用系统默认编码；
 * 正式实现需集成 juniversalchardet 进行编码检测，优先处理 GBK 和 UTF-8 BOM。
 */
public class DXFReader implements Closeable {
    private final BufferedReader reader;
    /** 支持单对回退，用于 SectionDispatcher/EntityDispatcher 预读后回退。 */
    private final Deque<GroupCodePair> pushBackBuffer = new ArrayDeque<>(2);
    private boolean closed = false;

    private DXFReader(BufferedReader reader) {
        this.reader = reader;
    }

    /**
     * 打开 DXF 文件。
     * TODO Phase 1：在此处加入 juniversalchardet 编码检测逻辑。
     */
    public static DXFReader open(Path path) throws IOException {
        return new DXFReader(new BufferedReader(new FileReader(path.toFile())));
    }

    /** 从已有 Reader 创建（常用于单元测试中传入 StringReader）。 */
    public static DXFReader of(Reader reader) {
        BufferedReader br = reader instanceof BufferedReader br2 ? br2 : new BufferedReader(reader);
        return new DXFReader(br);
    }

    /**
     * 读取并返回下一个 group code 对。
     * 若已到文件末尾，返回 null。
     * 格式错误的 code 行（非整数）会被静默跳过。
     */
    public GroupCodePair next() throws IOException {
        if (!pushBackBuffer.isEmpty()) return pushBackBuffer.pop();
        String codeLine = reader.readLine();
        if (codeLine == null) return null;
        String valueLine = reader.readLine();
        if (valueLine == null) return null;
        try {
            int code = Integer.parseInt(codeLine.trim());
            return new GroupCodePair(code, valueLine.trim());
        } catch (NumberFormatException e) {
            return next(); // 跳过格式错误行，递归读下一对
        }
    }

    /**
     * 检查是否还有可读取的 group code 对。
     * 内部会尝试读取一对，若成功则压入 pushBack 缓冲区供 {@link #next()} 取出。
     */
    public boolean hasNext() throws IOException {
        if (!pushBackBuffer.isEmpty()) return true;
        GroupCodePair pair = next();
        if (pair == null) return false;
        pushBackBuffer.push(pair);
        return true;
    }

    /**
     * 将一个 group code 对回退到流中，供下次 {@link #next()} 重新读取。
     * 常用于 section/entity dispatcher 读取了一对以判断类型后决定不消费它。
     */
    public void pushBack(GroupCodePair pair) {
        pushBackBuffer.push(pair);
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            reader.close();
        }
    }
}
