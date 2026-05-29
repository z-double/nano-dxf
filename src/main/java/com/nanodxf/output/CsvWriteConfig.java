package com.nanodxf.output;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 点云 CSV 写出配置（不可变，使用 {@link Builder} 构建）。
 *
 * <p>默认配置：
 * <ul>
 *   <li>列顺序：X, Y, Z, LAYER, FEATURE_CODE</li>
 *   <li>分隔符：逗号</li>
 *   <li>包含表头行</li>
 *   <li>编码：UTF-8</li>
 *   <li>noDataValue：空字符串</li>
 * </ul>
 *
 * @see CsvWriter
 */
public final class CsvWriteConfig {

    private final List<CsvField> fields;
    private final char           delimiter;
    private final boolean        writeHeader;
    private final Charset        charset;
    private final String         noDataValue;

    private CsvWriteConfig(Builder b) {
        this.fields      = Collections.unmodifiableList(b.fields);
        this.delimiter   = b.delimiter;
        this.writeHeader = b.writeHeader;
        this.charset     = b.charset;
        this.noDataValue = b.noDataValue;
    }

    public List<CsvField> getFields()    { return fields; }
    public char           getDelimiter() { return delimiter; }
    public boolean        isWriteHeader(){ return writeHeader; }
    public Charset        getCharset()   { return charset; }
    public String         getNoDataValue(){ return noDataValue; }

    /** 默认配置（X, Y, Z, LAYER, FEATURE_CODE；逗号；UTF-8；含表头）。 */
    public static CsvWriteConfig defaults() {
        return new Builder().build();
    }

    public static Builder builder() { return new Builder(); }

    // -------------------------------------------------------------------------

    public static final class Builder {

        private List<CsvField> fields      = Arrays.asList(
                CsvField.X, CsvField.Y, CsvField.Z, CsvField.LAYER, CsvField.FEATURE_CODE);
        private char           delimiter   = ',';
        private boolean        writeHeader = true;
        private Charset        charset     = StandardCharsets.UTF_8;
        private String         noDataValue = "";

        public Builder fields(CsvField... f)     { this.fields = Arrays.asList(f); return this; }
        public Builder fields(List<CsvField> f)  { this.fields = List.copyOf(f);   return this; }
        public Builder delimiter(char d)         { this.delimiter   = d;    return this; }
        public Builder writeHeader(boolean w)    { this.writeHeader = w;    return this; }
        public Builder charset(Charset cs)       { this.charset     = cs;   return this; }
        public Builder noDataValue(String v)     { this.noDataValue = v;    return this; }

        public CsvWriteConfig build() { return new CsvWriteConfig(this); }
    }
}
