package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.geometry.GeometryBuilder;
import org.locationtech.jts.geom.GeometryFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 所有版本测试的共享基类，提供 DXF 构建辅助、断言辅助和 Shapefile 读取工具。
 */
abstract class NanoDxfTestBase {

    protected static final GeometryFactory GF = GeometryBuilder.factory();

    // -------------------------------------------------------------------------
    // DXF 构建辅助
    // -------------------------------------------------------------------------

    /** 将若干实体片段包裹成完整的 DXF 字符串（含 HEADER / ENTITIES 段）。 */
    protected static String entities(String... blocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n");
        sb.append("  0\nSECTION\n  2\nENTITIES\n");
        for (String b : blocks) sb.append(b);
        sb.append("  0\nENDSEC\n  0\nEOF\n");
        return sb.toString();
    }

    /** 解析 DXF 字符串，断言恰好有 1 个实体并返回它。 */
    protected static CADEntity single(String dxf) throws Exception {
        ParseResult result = new CADParser().parse(new StringReader(dxf));
        assertThat(result.getEntities()).as("应恰好有 1 个实体").hasSize(1);
        return result.getEntities().get(0);
    }

    // -------------------------------------------------------------------------
    // Shapefile 读取辅助
    // -------------------------------------------------------------------------

    /** 读取 SHP 文件头中的 shape type（bytes 32–35, little-endian）。 */
    protected static int readShpShapeType(Path shp) throws IOException {
        byte[] h = Files.readAllBytes(shp);
        return ByteBuffer.wrap(h, 32, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** 读取 SHP 文件头中的 magic number（bytes 0–3, big-endian，应为 9994）。 */
    protected static int readShpMagic(Path shp) throws IOException {
        byte[] h = Files.readAllBytes(shp);
        return ByteBuffer.wrap(h, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    /** 读取 DBF 文件头中的记录数（bytes 4–7, little-endian）。 */
    protected static int readDbfRecordCount(Path dbf) throws IOException {
        byte[] h = Files.readAllBytes(dbf);
        return ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** 读取 SHX 文件中的记录数（= (fileLenWords - 50) / 4，其中每条索引 8 字节 = 4 words）。 */
    protected static int readShxRecordCount(Path shx) throws IOException {
        byte[] h = Files.readAllBytes(shx);
        int fileLenWords = ByteBuffer.wrap(h, 6, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        return (fileLenWords - 50) / 4;
    }

    /** 递归删除临时目录（测试清理）。 */
    protected static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    /** 将 GBK 字节流以 ISO-8859-1 透传方式转成字符串（用于 DBF 字节级断言）。 */
    protected static String dbfLatin1(Path dbf) throws IOException {
        return new String(Files.readAllBytes(dbf), StandardCharsets.ISO_8859_1);
    }
}
