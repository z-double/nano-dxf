package com.nanodxf.output;

/**
 * DXF 标准线型名称常量。
 *
 * <p>DXF 线型（LTYPE）通过名称字符串引用，在 TABLES 段的 LTYPE 表中定义，
 * 实体通过 code 6 引用线型名。本类收录 AutoCAD 内置的标准线型名称。
 *
 * <p>注意事项：
 * <ul>
 *   <li>线型名称<b>大小写不敏感</b>（DXF 规范），但建议使用本类常量保持一致</li>
 *   <li>使用非 {@link #CONTINUOUS} 的线型时，目标文件的 LTYPE 表必须包含对应的线型定义；
 *       当前版本 {@link DXFWriter} 仅写出 Continuous / ByBlock / ByLayer，
 *       其他线型需外部工具（CAD 软件）从线型库（*.lin）加载</li>
 *   <li>特殊值 {@link #BYLAYER} 和 {@link #BYBLOCK} 继承图层或块的线型设置</li>
 * </ul>
 *
 * <h3>写出示例</h3>
 * <pre>{@code
 * // 图层默认线型（Continuous 实线）
 * CADEntity.builder(CADEntity.Types.LINE)
 *     .layer("道路")
 *     .property("lineType", LineTypeName.CONTINUOUS)
 *     .build();
 *
 * // 中心线（线型文件需在 CAD 软件中加载）
 * CADEntity.builder(CADEntity.Types.LINE)
 *     .layer("轴线")
 *     .property("lineType", LineTypeName.CENTER)
 *     .build();
 * }</pre>
 */
public final class LineTypeName {

    private LineTypeName() {}

    // =========================================================================
    // 特殊继承值（任何 DXF 文件均有效）
    // =========================================================================

    /**
     * 继承图层线型（DXF 默认行为）。
     * 实体不显式指定线型时，CAD 软件使用此值并继承所在图层的线型。
     */
    public static final String BYLAYER = "ByLayer";

    /**
     * 继承块的线型。
     * 仅在块（BLOCK）内部的实体上使用，展开时继承 INSERT 实体的线型。
     */
    public static final String BYBLOCK = "ByBlock";

    // =========================================================================
    // 实线（任何 DXF 文件均有效，本库写出 LTYPE 表包含此定义）
    // =========================================================================

    /**
     * 连续实线（默认）。
     * DXF 标准线型，任何软件均支持，{@link DXFWriter} 写出时自动包含此定义。
     */
    public static final String CONTINUOUS = "Continuous";

    // =========================================================================
    // AutoCAD 标准线型（需在 CAD 软件中从 acad.lin / acadiso.lin 加载）
    // =========================================================================

    /**
     * 虚线（长划线）。
     * 对应 acad.lin 中的 DASHED 线型，常用于隐藏边、拆除线。
     */
    public static final String DASHED = "DASHED";

    /**
     * 虚线（短划，DASHED 的一半比例）。
     */
    public static final String DASHED2 = "DASHED2";

    /**
     * 虚线（长划，DASHED 的两倍比例）。
     */
    public static final String DASHEDX2 = "DASHEDX2";

    /**
     * 点线。常用于地下管线、隐蔽设施。
     */
    public static final String DOT = "DOT";

    /**
     * 点线（DOT 的一半比例）。
     */
    public static final String DOT2 = "DOT2";

    /**
     * 点线（DOT 的两倍比例）。
     */
    public static final String DOTX2 = "DOTX2";

    /**
     * 中心线（长划-短划-长划）。常用于对称轴、中轴线。
     * 测绘中用于道路中心线、建筑轴线。
     */
    public static final String CENTER = "CENTER";

    /**
     * 中心线（CENTER 的一半比例）。
     */
    public static final String CENTER2 = "CENTER2";

    /**
     * 中心线（CENTER 的两倍比例）。
     */
    public static final String CENTERX2 = "CENTERX2";

    /**
     * 隐藏线（短划线，比 DASHED 更密）。
     * 用于三维立体图的不可见边，测绘中用于地下构筑物轮廓。
     */
    public static final String HIDDEN = "HIDDEN";

    /**
     * 隐藏线（HIDDEN 的一半比例）。
     */
    public static final String HIDDEN2 = "HIDDEN2";

    /**
     * 隐藏线（HIDDEN 的两倍比例）。
     */
    public static final String HIDDENX2 = "HIDDENX2";

    /**
     * 幻影线（长划-短划-短划-长划）。
     * 常用于设备轮廓、规划范围线。
     */
    public static final String PHANTOM = "PHANTOM";

    /**
     * 划点线（长划-点）。常用于地块界线、行政边界。
     */
    public static final String DASHDOT = "DASHDOT";

    /**
     * 划点线（DASHDOT 的一半比例）。
     */
    public static final String DASHDOT2 = "DASHDOT2";

    /**
     * 分割线（长划-点-点）。常用于用地分界、专题区域边界。
     */
    public static final String DIVIDE = "DIVIDE";

    /**
     * 边界线（长划-短划-短划-短划）。常用于地块范围线。
     */
    public static final String BORDER = "BORDER";
}
