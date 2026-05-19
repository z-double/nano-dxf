package com.nanodxf.xdata;

import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.entity.EntityBuffer;

import java.io.IOException;
import java.util.*;

/**
 * XDATA（扩展数据）解析器。
 *
 * <p>XDATA 以 {@code 1001 appName} 开始，code 范围 1000~1071。
 * 支持两种解析来源：
 * <ol>
 *   <li>{@link #parseFromBuffer} — 从 {@link EntityBuffer} 解析（Phase 2/3 主用方式，
 *       XDATA 已包含在 buffer 中，无需操作 reader）</li>
 *   <li>{@link #parseFromReader} — 从 DXFReader 顺序解析（用于直接读取流）</li>
 * </ol>
 *
 * <p>国内主流测绘软件 XDATA 应用名约定：
 * <ul>
 *   <li>南方 CASS - "CASS"，地物编码在 code 1000（国内最广泛）</li>
 *   <li>清华山维 EPS - "EPSW"，code 1000</li>
 *   <li>武汉大学 MapMatrix - "MAPMATRIX"，code 1000</li>
 *   <li>中地数码 MapGIS - "MAPGIS"，code 1002</li>
 *   <li>SuperMap - "SUPERMAP"，code 1000</li>
 * </ul>
 *
 * <p>未匹配已知应用名的 XDATA 原样保留在结果 map 中，不丢弃。
 */
public class XDataParser {

    /** 已知国内测绘软件的 XDATA 应用名，按优先级排列。 */
    private static final List<String> KNOWN_APPS =
        List.of("CASS", "EPSW", "MAPMATRIX", "MAPGIS", "SUPERMAP");

    // -------------------------------------------------------------------------
    // 从 EntityBuffer 解析（主要入口）
    // -------------------------------------------------------------------------

    /**
     * 从 EntityBuffer 的 group code 列表中提取 XDATA。
     *
     * <p>XDATA 已包含在 buffer 内（code 1001~1071），不需要操作 DXFReader。
     * 此方法遍历所有 pair，过滤出 1001~1071 范围的 code，按应用名分组。
     *
     * @return {@code Map<应用名, XDataEntry列表>}，保持读取顺序；无 XDATA 时返回空 map
     */
    public Map<String, List<XDataEntry>> parseFromBuffer(EntityBuffer buffer) {
        Map<String, List<XDataEntry>> result = new LinkedHashMap<>();
        String appName = null;

        for (GroupCodePair pair : buffer.all()) {
            if (pair.code() == 1001) {
                appName = pair.value();
                result.put(appName, new ArrayList<>());
            } else if (pair.code() >= 1000 && pair.code() <= 1071 && appName != null) {
                result.get(appName).add(new XDataEntry(pair.code(), pair.value()));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 从 DXFReader 顺序解析（流式读取）
    // -------------------------------------------------------------------------

    /**
     * 从 DXFReader 当前位置解析 XDATA 块（流式读取）。
     *
     * <p>调用前提：reader 的下一对为 {@code 1001 appName}。
     * 结束时，终止 XDATA 的非 XDATA code 对会被 pushBack 到 reader。
     */
    public Map<String, List<XDataEntry>> parseFromReader(DXFReader reader) throws IOException {
        Map<String, List<XDataEntry>> result = new LinkedHashMap<>();
        String appName = null;

        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;

            if (pair.code() == 1001) {
                appName = pair.value();
                result.put(appName, new ArrayList<>());
            } else if (pair.code() >= 1000 && pair.code() <= 1071 && appName != null) {
                result.get(appName).add(new XDataEntry(pair.code(), pair.value()));
            } else {
                reader.pushBack(pair); // 非 XDATA code，归还给 reader
                break;
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 地物编码提取
    // -------------------------------------------------------------------------

    /**
     * 从 XDATA map 中提取地物编码（featureCode）。
     *
     * <p>按 {@link #KNOWN_APPS} 顺序查找，第一个匹配到的 code 1000 值即为地物编码。
     * MapGIS 使用 code 1002，后续版本扩展支持。
     *
     * @return 地物编码（如 "41000" 表示普通房屋）；未找到返回 empty
     */
    public Optional<String> extractFeatureCode(Map<String, List<XDataEntry>> xdata) {
        for (String app : KNOWN_APPS) {
            if (xdata.containsKey(app)) {
                // CASS/EPS/MAPMATRIX: code 1000
                Optional<String> code = xdata.get(app).stream()
                    .filter(e -> e.code() == 1000)
                    .map(XDataEntry::value)
                    .findFirst();
                if (code.isPresent()) return code;

                // MapGIS: code 1002
                if ("MAPGIS".equals(app)) {
                    code = xdata.get(app).stream()
                        .filter(e -> e.code() == 1002)
                        .map(XDataEntry::value)
                        .findFirst();
                    if (code.isPresent()) return code;
                }
            }
        }
        return Optional.empty();
    }
}
