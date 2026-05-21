package com.nanodxf;

/**
 * {@link com.nanodxf.entity.CADEntity#getProperties()} 中使用的属性键常量。
 *
 * <p>所有键均为小写驼峰字符串，与解析器写入、写出器读取的键严格一致。
 * 使用此类的常量可避免拼写错误导致的静默失败（如 {@code "colorACi"} 返回 null）。
 *
 * <h3>读取示例</h3>
 * <pre>{@code
 * // 错误写法（拼写错误，静默失败）
 * entity.getProperties().get("colorACi");       // 返回 null
 *
 * // 正确写法
 * Integer aci = (Integer) entity.getProperties().get(EntityProperty.COLOR_ACI);
 * int[]   rgb = (int[])   entity.getProperties().get(EntityProperty.COLOR_RGB);
 * String  txt = (String)  entity.getProperties().get(EntityProperty.TEXT);
 * Double  elev = (Double) entity.getProperties().get(EntityProperty.ELEVATION);
 * }</pre>
 *
 * <h3>写出示例</h3>
 * <pre>{@code
 * CADEntity.builder(CADEntity.Types.TEXT)
 *     .layer("注记")
 *     .geometry(point)
 *     .property(EntityProperty.TEXT,     "控制点 A1")
 *     .property(EntityProperty.HEIGHT,   3.0)
 *     .property(EntityProperty.ROTATION, 45.0)
 *     .property(EntityProperty.COLOR_ACI, AciColor.RED)
 *     .build();
 * }</pre>
 *
 * <h3>属性来源说明</h3>
 * <ul>
 *   <li>解析侧：由各 EntityHandler 和 EntitiesParser 填充</li>
 *   <li>写出侧：{@link com.nanodxf.output.DXFWriter} 读取以下属性写入 DXF 文件</li>
 * </ul>
 */
public final class EntityProperty {

    private EntityProperty() {}

    // =========================================================================
    // 颜色
    // =========================================================================

    /**
     * ACI 颜色号（Integer）。
     * True Color（{@link #COLOR_RGB}）存在时，写出器优先使用 RGB，忽略此属性。
     * 解析器在颜色继承链路（True Color → ACI → BYLAYER）处理后填充。
     *
     * @see AciColor
     */
    public static final String COLOR_ACI = "colorAci";

    /**
     * RGB 颜色（int[3]，分量范围 0-255）。
     * 来源：DXF True Color（code 420），或 BYLAYER 继承后由 ACI 色表转换。
     * 解析器总会尝试填充此属性（优先级高于 colorAci）。
     * 写出器仅在 R2004+ 且值存在时写出 code 420。
     */
    public static final String COLOR_RGB = "colorRgb";

    // =========================================================================
    // 文字
    // =========================================================================

    /**
     * 文字内容（String）。
     * TEXT / MTEXT 解析后的净文字（MTEXT 格式码已由 MTextCleaner 清洗）。
     * 写出时对应 TEXT 的 code 1 / MTEXT 的 code 1。
     */
    public static final String TEXT = "text";

    /**
     * 文字高度（Double，坐标单位）。
     * TEXT / MTEXT 的字高，对应 code 40。写出时默认值 2.5。
     */
    public static final String HEIGHT = "height";

    /**
     * 文字旋转角度（Double，度，**逆时针**为正，与 DXF code 50 标准一致）。
     * 对应 DXF code 50。写出时默认值 0.0（水平）。
     */
    public static final String ROTATION = "rotation";

    /**
     * 文字样式名（String）。
     * 对应 STYLE 表中的样式名（code 7）。写出时默认 "Standard"。
     */
    public static final String STYLE = "style";

    // =========================================================================
    // 几何附加属性
    // =========================================================================

    /**
     * Z 高程（Double，坐标单位）。
     * LWPOLYLINE（code 38）和 POINT 实体的高程值。
     * 写出 LWPOLYLINE 时若各顶点 Z 一致则写出 code 38；POINT 写出 code 30。
     */
    public static final String ELEVATION = "elevation";

    // =========================================================================
    // 地物编码（来自 XDATA 解析）
    // =========================================================================

    /**
     * 地物编码（String）。
     * 从 CASS / EPS / MapMatrix / MapGIS / SuperMap 的 XDATA 中提取。
     * 通常为 GB/T 20257 或软件自定义编码（如 CASS 的 31010）。
     */
    public static final String FEATURE_CODE = "featureCode";

    /**
     * 地物名称（String）。
     * 由 {@code FeatureCodeRegistry} 根据 {@link #FEATURE_CODE} 查表后填充。
     * 示例："普通房屋"、"等高线（首曲线）"。
     */
    public static final String FEATURE_TYPE = "featureType";

    /**
     * 地物分类（String）。
     * 同样由 {@code FeatureCodeRegistry} 填充，为 {@link #FEATURE_TYPE} 的上级分类。
     * 示例："建筑"、"道路"、"水系"。
     */
    public static final String FEATURE_CATEGORY = "featureCategory";

    /**
     * 原始 XDATA（{@code Map<String, List<Object>>}）。
     * 键为 XDATA 应用名（如 "CASS"、"EPSW"），值为 group code + value 列表。
     * 仅当 DXF 文件包含 XDATA 且无法映射到已知地物编码时才需要直接访问。
     */
    public static final String XDATA = "xdata";

    // =========================================================================
    // 几何参数（ARC / CIRCLE）
    // =========================================================================

    /**
     * 半径（Double，坐标单位）。
     * ARC / CIRCLE 写出时必须提供。对应 DXF code 40。
     *
     * <p>写出 ARC 示例：
     * <pre>{@code
     * CADEntity.builder(CADEntity.Types.ARC)
     *     .layer("构筑物")
     *     .geometry(GF.createPoint(new Coordinate(100, 200)))  // 圆心
     *     .property(EntityProperty.RADIUS,      25.0)
     *     .property(EntityProperty.START_ANGLE, 0.0)
     *     .property(EntityProperty.END_ANGLE,   180.0)
     *     .build();
     * }</pre>
     */
    public static final String RADIUS = "radius";

    /**
     * 弧线起始角（Double，度，逆时针，相对 WCS X 轴正方向）。
     * ARC 写出时必须提供。对应 DXF code 50。
     */
    public static final String START_ANGLE = "startAngle";

    /**
     * 弧线终止角（Double，度，逆时针，相对 WCS X 轴正方向）。
     * ARC 写出时必须提供。对应 DXF code 51。
     */
    public static final String END_ANGLE = "endAngle";

    // =========================================================================
    // 线型与线宽
    // =========================================================================

    /**
     * 线型名（String）。
     * 图层级别的线型，对应 LAYER 表中 code 6。
     * 默认值 {@code "Continuous"}（实线）。
     * 非 Continuous 线型需在目标 CAD 软件中已加载对应线型文件（*.lin）。
     *
     * @see com.nanodxf.output.LineTypeName
     */
    public static final String LINETYPE = "lineType";

    /**
     * DXF 线宽码（Integer）。
     * 图层级别的线宽，对应 LAYER 表中 code 370。
     * 特殊值：-3=ByLayer（默认），-2=ByBlock，-1=Default。
     * 实际线宽值（单位 1/100 mm）：0、5、9、13、15、18、20、25、30、35、40、50、53、60、70、80、90、100、106、120、140、158、200、211。
     */
    public static final String LINEWEIGHT = "lineWeight";

    // =========================================================================
    // HATCH 填充
    // =========================================================================

    /**
     * HATCH 图案名（String）。
     * 对应 DXF code 2（pattern name）。默认 {@code "SOLID"}（实心填充）。
     * v1.2.0 写出器仅支持 SOLID，其他图案名不会生成图案定义段。
     */
    public static final String HATCH_PATTERN = "hatchPattern";

    // =========================================================================
    // 块引用（INSERT）
    // =========================================================================

    /**
     * 块名（String）。
     * INSERT 实体引用的块定义名称（code 2）。
     * INSERT 展开后，子实体上也会保留此属性以追踪来源。
     */
    public static final String BLOCK_NAME = "blockName";

    /**
     * INSERT X 方向缩放因子（Double）。默认 1.0。对应 DXF code 41。
     */
    public static final String SCALE_X = "scaleX";

    /**
     * INSERT Y 方向缩放因子（Double）。默认 1.0。对应 DXF code 42。
     */
    public static final String SCALE_Y = "scaleY";

    /**
     * INSERT Z 方向缩放因子（Double）。默认 1.0。对应 DXF code 43。
     */
    public static final String SCALE_Z = "scaleZ";

    // =========================================================================
    // 块属性（ATTRIB）
    // =========================================================================

    /**
     * 属性标签（String）。
     * ATTRIB 实体的标识标签（code 2），如 "NAME"、"NUMBER"。
     * INSERT 展开后注入到包含 ATTRIB 的实体的 {@code properties} 中。
     */
    public static final String TAG = "tag";

    /**
     * 属性值（String）。
     * ATTRIB 实体的文字值（code 1），与 {@link #TAG} 配对出现。
     */
    public static final String VALUE = "value";
}
