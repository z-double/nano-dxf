package com.nanodxf.xdata;

import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;

import java.io.IOException;
import java.util.*;

/**
 * XDATA（扩展数据）解析器。
 *
 * <p>XDATA 跟随在实体数据之后，以 {@code 1001 应用名} 开始，
 * code 范围 1000~1071，遇到非 XDATA code 时结束（该 code 需回退给 reader）。
 *
 * <p>国内主流测绘软件的 XDATA 应用名约定：
 * <ul>
 *   <li>南方 CASS - 应用名 "CASS"，地物编码在 code 1000（国内最广泛）</li>
 *   <li>清华山维 EPS - 应用名 "EPSW"</li>
 *   <li>武汉大学 MapMatrix - 应用名 "MAPMATRIX"</li>
 *   <li>中地数码 MapGIS - 应用名 "MAPGIS"，地物编码在 code 1002</li>
 *   <li>SuperMap - 应用名 "SUPERMAP"</li>
 * </ul>
 *
 * <p>未匹配已知应用名的 XDATA 原样保留在 result map 中，不丢弃。
 */
public class XDataParser {

    /** 已知国内测绘软件的 XDATA 应用名，按优先级排列（CASS 最常见故排首位）。 */
    private static final List<String> KNOWN_APPS =
        List.of("CASS", "EPSW", "MAPMATRIX", "MAPGIS", "SUPERMAP");

    /**
     * 从 reader 当前位置解析 XDATA 块。
     * 调用前提：reader 的下一对为 {@code 1001 appName}。
     * 结束时，终止 XDATA 的那个非 XDATA code 对会被回退到 reader。
     *
     * @return {@code Map<应用名, XDataEntry列表>}，保持读取顺序
     */
    public Map<String, List<XDataEntry>> parse(DXFReader reader) throws IOException {
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
                // 遇到非 XDATA code，回退供后续代码处理
                reader.pushBack(pair);
                break;
            }
        }
        return result;
    }

    /**
     * 从 XDATA map 中提取地物编码（featureCode）。
     *
     * <p>按 {@link #KNOWN_APPS} 顺序查找：第一个匹配到的 code 1000 值即为地物编码。
     * MapGIS 使用 code 1002，会在后续版本中扩展支持。
     *
     * @return 地物编码（如 "41000" 表示普通房屋），未找到返回空
     */
    public Optional<String> extractFeatureCode(Map<String, List<XDataEntry>> xdata) {
        for (String app : KNOWN_APPS) {
            if (xdata.containsKey(app)) {
                return xdata.get(app).stream()
                    .filter(e -> e.code() == 1000)
                    .map(XDataEntry::value)
                    .findFirst();
            }
        }
        return Optional.empty();
    }
}
