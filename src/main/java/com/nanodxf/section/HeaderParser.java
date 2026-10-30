package com.nanodxf.section;

import com.nanodxf.ParseError;
import com.nanodxf.ParseErrorLevel;
import com.nanodxf.core.DXFReader;
import com.nanodxf.core.GroupCodePair;
import com.nanodxf.model.DXFContext;
import com.nanodxf.model.DXFVersion;

import java.io.IOException;

/**
 * HEADER section 解析器。
 *
 * <p>从 HEADER 段中提取以下关键图纸变量：
 * <ul>
 *   <li>{@code $ACADVER}         - DXF 版本字符串（AC1009/AC1015/...）</li>
 *   <li>{@code $INSUNITS}        - 图纸单位（4=毫米、6=米，用于 UnitConverter）</li>
 *   <li>{@code $MEASUREMENT}     - 0=英制，1=公制</li>
 *   <li>{@code $CONTOURINTERVAL} - 等高距（测绘地形图关键参数，如 1.0 表示 1 米）</li>
 * </ul>
 *
 * <p>其他未列出的变量（如 $EXTMIN/$EXTMAX）不在此处读取，
 * 图纸范围从实际几何重新计算更可靠（$EXTMIN/MAX 经常未更新）。
 */
public class HeaderParser {

    public void parse(DXFReader reader, DXFContext ctx) throws IOException {
        String currentVar = null;
        while (reader.hasNext()) {
            GroupCodePair pair = reader.next();
            if (pair == null) break;
            if (pair.code() == 0 && "ENDSEC".equals(pair.value())) break;

            if (pair.code() == 9) {
                // code 9 标识一个新的图纸变量名（如 $ACADVER）
                currentVar = pair.value();
                continue;
            }
            if (currentVar == null) continue;

            switch (currentVar) {
                case "$ACADVER"         -> ctx.metadata.setVersion(DXFVersion.fromString(pair.value()));
                case "$INSUNITS"        -> ctx.metadata.setInsunits(asInt(ctx, currentVar, pair));
                case "$MEASUREMENT"     -> ctx.metadata.setMeasurement(asInt(ctx, currentVar, pair) == 1);
                case "$CONTOURINTERVAL" -> ctx.metadata.setContourInterval(asDouble(ctx, currentVar, pair));
            }
        }
    }

    /** 解析整数变量；格式错误时记录 WARN 并返回 0，不中断整段解析。 */
    private static int asInt(DXFContext ctx, String var, GroupCodePair pair) {
        try {
            return pair.asInt();
        } catch (NumberFormatException e) {
            warn(ctx, var, pair);
            return 0;
        }
    }

    /** 解析浮点变量；格式错误时记录 WARN 并返回 0，不中断整段解析。 */
    private static double asDouble(DXFContext ctx, String var, GroupCodePair pair) {
        try {
            return pair.asDouble();
        } catch (NumberFormatException e) {
            warn(ctx, var, pair);
            return 0;
        }
    }

    private static void warn(DXFContext ctx, String var, GroupCodePair pair) {
        ctx.errors.add(new ParseError(ParseErrorLevel.WARN, "HEADER", "",
                "变量 " + var + " 的数值格式非法，已使用默认值：" + pair.value()));
    }
}
