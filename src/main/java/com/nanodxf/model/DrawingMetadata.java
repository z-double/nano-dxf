package com.nanodxf.model;

/**
 * 图纸级元数据，从 DXF HEADER 段和调用方配置中提取。
 *
 * <p>由 {@link com.nanodxf.section.HeaderParser} 填充版本、单位等字段；
 * CRS 信息由 {@link com.nanodxf.CADParser} 从 {@link com.nanodxf.ParseConfig} 注入。
 *
 * <p>注意：{@code $EXTMIN} / {@code $EXTMAX} 在实际 DXF 文件中经常未更新，不可信，
 * 实际范围应从所有实体几何重新计算。
 */
public class DrawingMetadata {

    /** DXF 版本，如 {@link DXFVersion#R2000}，默认 {@link DXFVersion#UNKNOWN}。 */
    private DXFVersion version = DXFVersion.UNKNOWN;

    /**
     * 图纸单位代码（{@code $INSUNITS}）。常用值：
     * 0=无单位，1=英寸，2=英尺，4=毫米，5=厘米，6=米，7=千米。
     */
    private int insunits = 0;

    /**
     * 等高距（{@code $CONTOURINTERVAL}），单位与 {@code insunits} 一致。
     * 0 表示未定义。测绘地形图关键参数，如 1.0 表示 1 米等高距。
     */
    private double contourInterval = 0.0;

    /** 度量制标志（{@code $MEASUREMENT}）：true=公制，false=英制。 */
    private boolean measurement = true;

    /**
     * 是否含有效 Z 坐标（高程）。
     * 若所有实体 Z 均为 0，则置 false，表示这是一张平面图。
     */
    private boolean is3D = false;

    /**
     * 坐标参考系标识，如 {@code "EPSG:4545"}。
     * 由调用方通过 {@link com.nanodxf.ParseConfig#getCrs()} 注入；DXF 文件本身不存储 CRS。
     */
    private String crs;

    /**
     * CRS 来源标注，写入 GeoJSON 顶层 {@code crs.source} 字段。
     * <ul>
     *   <li>{@code "caller_specified"} — 调用方明确传入，可信</li>
     *   <li>{@code "unknown"}         — 未指定（默认值）</li>
     * </ul>
     */
    private String crsSource = "unknown";

    public DXFVersion getVersion()                      { return version; }
    public void setVersion(DXFVersion version)          { this.version = version; }

    public int getInsunits()                            { return insunits; }
    public void setInsunits(int insunits)               { this.insunits = insunits; }

    public double getContourInterval()                  { return contourInterval; }
    public void setContourInterval(double v)            { this.contourInterval = v; }

    public boolean isMeasurement()                      { return measurement; }
    public void setMeasurement(boolean measurement)     { this.measurement = measurement; }

    public boolean is3D()                               { return is3D; }
    public void set3D(boolean is3D)                     { this.is3D = is3D; }

    public String getCrs()                              { return crs; }
    public void setCrs(String crs)                      { this.crs = crs; }

    public String getCrsSource()                        { return crsSource; }
    public void setCrsSource(String crsSource)          { this.crsSource = crsSource; }
}
