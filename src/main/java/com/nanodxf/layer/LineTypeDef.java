package com.nanodxf.layer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DXF 线型定义，对应 TABLES 段中的 LTYPE 表项（{@code code 0 = LTYPE}）。
 *
 * <p>线型决定实体边线的虚实模式（实线、虚线、点划线等）。
 * 解析名称、描述与图案数据（{@code code 40} 图案总长、{@code code 49} 段划长度序列）。
 *
 * <p>图案元素语义：正值表示实线段长度，负值表示空白段长度，0 表示点。
 * 例如虚线 {@code DASHED} 的图案为 {@code [12.7, -6.35]}。
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

    /** 图案总长度（code 40），即一个完整周期的长度。 */
    private double patternLength;

    /** 段划长度序列（code 49，重复）。正=实线段，负=空白段，0=点。 */
    private final List<Double> patternElements = new ArrayList<>();

    public LineTypeDef(String name) {
        this.name = name;
    }

    public String getName()                           { return name; }

    public String getDescription()                    { return description; }
    public void setDescription(String description)    { this.description = description; }

    public double getPatternLength()                  { return patternLength; }
    public void setPatternLength(double patternLength) { this.patternLength = patternLength; }

    /** 返回只读的段划长度序列（正=实线，负=空白，0=点）。 */
    public List<Double> getPatternElements() {
        return Collections.unmodifiableList(patternElements);
    }

    public void addPatternElement(double element)     { patternElements.add(element); }
}
