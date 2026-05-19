package com.nanodxf.core;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * DXF ASCII 文件的低级读取器，输出 {@link GroupCodePair} 流。
 *
 * <p>DXF 格式基本单元：code 行（整数）+ value 行的逐行对：
 * <pre>
 *   0          ← group code
 *   LINE       ← value
 *   8
 *   道路中心线
 * </pre>
 *
 * <p>支持 {@link #pushBack} 回退一对，供解析器 lookahead 使用。
 *
 * <p>编码检测策略（{@link #detectEncoding}）：
 * <ol>
 *   <li>BOM（EF BB BF）→ UTF-8</li>
 *   <li>juniversalchardet 检测 → 使用检测结果（GB 系列统一转 GBK）</li>
 *   <li>检测失败但 $ACADVER ≥ AC1021（R2007）→ UTF-8</li>
 *   <li>最终兜底 → GBK（国内测绘 CAD 主流）</li>
 * </ol>
 *
 * <p>使用 try-with-resources 管理生命周期：
 * <pre>{@code
 * try (DXFReader reader = DXFReader.open(path)) {
 *     while (reader.hasNext()) { ... }
 * }
 * }</pre>
 */
public class DXFReader implements Closeable {

    private final BufferedReader reader;
    /** 支持单对回退，供 SectionDispatcher/EntityDispatcher 预读后回退。 */
    private final Deque<GroupCodePair> pushBackBuffer = new ArrayDeque<>(2);
    private boolean closed = false;

    private DXFReader(BufferedReader reader) {
        this.reader = reader;
    }

    /**
     * 打开 DXF 文件，自动检测编码。
     * 检测失败时记录日志并以 GBK 兜底，不抛出异常。
     */
    public static DXFReader open(Path path) throws IOException {
        String encoding = detectEncoding(path);
        return new DXFReader(new BufferedReader(
            new InputStreamReader(Files.newInputStream(path),
                Charset.forName(encoding))
        ));
    }

    /** 从已有 Reader 创建（常用于单元测试中传入 StringReader）。 */
    public static DXFReader of(Reader reader) {
        BufferedReader br = reader instanceof BufferedReader br2 ? br2 : new BufferedReader(reader);
        return new DXFReader(br);
    }

    /**
     * 读取并返回下一个 group code 对。
     * 文件末尾返回 null；格式错误的 code 行（非整数）被静默跳过。
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
     * 内部预读一对并压入 pushBack 缓冲区以供 {@link #next()} 取出。
     */
    public boolean hasNext() throws IOException {
        if (!pushBackBuffer.isEmpty()) return true;
        GroupCodePair pair = next();
        if (pair == null) return false;
        pushBackBuffer.push(pair);
        return true;
    }

    /**
     * 将一个 group code 对回退到流中。
     * 常用于解析器读取了下一个 code=0 边界后需要将其归还。
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

    // -------------------------------------------------------------------------
    // 编码检测
    // -------------------------------------------------------------------------

    /**
     * 检测 DXF 文件编码。读取前 4096 字节进行分析，不影响后续读取。
     *
     * @return 编码名称，如 "UTF-8"、"GBK"
     */
    static String detectEncoding(Path path) throws IOException {
        byte[] probe = readProbeBytes(path, 4096);

        // 1. UTF-8 BOM 检测
        if (probe.length >= 3
                && (probe[0] & 0xFF) == 0xEF
                && (probe[1] & 0xFF) == 0xBB
                && (probe[2] & 0xFF) == 0xBF) {
            return "UTF-8";
        }

        // 2. juniversalchardet 自动检测
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(probe, 0, probe.length);
        detector.dataEnd();
        String detected = detector.getDetectedCharset();

        if (detected != null) {
            // GB 系列统一归并为 GBK（GB2312/GB18030 均可用 GBK 解码）
            if (detected.startsWith("GB") || detected.equalsIgnoreCase("GBK")) {
                return "GBK";
            }
            return detected;
        }

        // 3. 检测失败时，读取版本号辅助判断
        // R2007（AC1021）起官方规范使用 UTF-8
        String ascii = new String(probe, StandardCharsets.US_ASCII);
        if (ascii.contains("AC1021") || ascii.contains("AC1024")
                || ascii.contains("AC1027") || ascii.contains("AC1032")) {
            return "UTF-8";
        }

        // 4. 最终兜底：GBK（国内测绘 DXF 最常见编码）
        return "GBK";
    }

    /** 读取文件开头最多 {@code maxBytes} 字节用于探测，不抛出超出文件大小的异常。 */
    private static byte[] readProbeBytes(Path path, int maxBytes) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return is.readNBytes(maxBytes);
        }
    }
}
