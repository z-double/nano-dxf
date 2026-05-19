package com.nanodxf.model;

/**
 * DXF 文件版本枚举，对应 {@code $ACADVER} 变量。
 *
 * <p>版本影响解析行为：
 * <ul>
 *   <li>{@link #R12}（AC1009）— 无 CLASSES / OBJECTS 段，不支持 True Color</li>
 *   <li>{@link #R2000}（AC1015）— 引入 OBJECTS 段和完整 handle 体系</li>
 *   <li>{@link #R2004}（AC1018）— 引入 True Color（code 420）</li>
 *   <li>{@link #R2007}（AC1021）— 官方规范使用 UTF-8，编码检测策略有所不同</li>
 * </ul>
 *
 * <p>通过 {@link #fromString(String)} 从 {@code $ACADVER} 字符串解析，
 * 未知版本返回 {@link #UNKNOWN}。
 */
public enum DXFVersion {
    R12("AC1009"),
    R2000("AC1015"),
    R2004("AC1018"),
    R2007("AC1021"),
    R2010("AC1024"),
    R2013("AC1027"),
    R2018("AC1032"),
    /** 文件中未找到 {@code $ACADVER} 或版本字符串未知。 */
    UNKNOWN("UNKNOWN");

    private final String versionString;

    DXFVersion(String versionString) {
        this.versionString = versionString;
    }

    /** 返回对应的 {@code $ACADVER} 字符串，如 {@code "AC1015"}。 */
    public String getVersionString() { return versionString; }

    /** 判断此版本是否早于 {@code other}（按 ordinal 比较）。 */
    public boolean before(DXFVersion other) {
        return this.ordinal() < other.ordinal();
    }

    /**
     * 从 {@code $ACADVER} 字符串解析版本枚举。
     *
     * @param s 版本字符串，如 {@code "AC1015"}
     * @return 对应的版本枚举，未匹配时返回 {@link #UNKNOWN}
     */
    public static DXFVersion fromString(String s) {
        for (DXFVersion v : values()) {
            if (v.versionString.equals(s)) return v;
        }
        return UNKNOWN;
    }
}
