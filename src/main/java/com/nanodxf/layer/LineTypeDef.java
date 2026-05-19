package com.nanodxf.layer;

/**
 * DXF 线型定义，对应 TABLES 段中的 LTYPE 表项（{@code code 0 = LTYPE}）。
 *
 * <p>线型决定实体边线的虚实模式（实线、虚线、点划线等）。
 * 当前实现仅读取名称和描述，线型图案数据（code 49 段划长度序列）留待后续支持。
 *
 * <p>由 {@link com.nanodxf.section.TablesParser} 解析后存入
 * {@link com.nanodxf.model.DXFContext#lineTypes}。
 */
public class LineTypeDef {

    /** 线型名（code 2），如 {@code "Continuous"}、{@code "DASHED"}。 */
    private final String name;

    /**
     * 线型描述（code 3），如 {@code "Dashed __ __ __"}，可为 null。
     * 仅供显示，不影响解析逻辑。
     */
    private String description;

    public LineTypeDef(String name) {
        this.name = name;
    }

    public String getName()                           { return name; }

    public String getDescription()                    { return description; }
    public void setDescription(String description)    { this.description = description; }
}
