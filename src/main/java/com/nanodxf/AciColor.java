package com.nanodxf;

/**
 * AutoCAD 颜色索引（ACI）命名常量。
 *
 * <p>ACI（AutoCAD Color Index）是 DXF 中最常用的颜色表示方式，用 0-256 的整数索引颜色：
 * <ul>
 *   <li>{@link #BYBLOCK}（0）  — 继承所在块的颜色</li>
 *   <li>1-9                   — 9 种标准基础色（下列常量）</li>
 *   <li>10-249                — 扩展调色板（24 色相 × 10 明度级，此处提供常用别名）</li>
 *   <li>{@link #BYLAYER}（256）— 继承所在图层的颜色（DXF 默认，实体不显式设色时的行为）</li>
 * </ul>
 *
 * <h3>写出侧用法</h3>
 * <pre>{@code
 * // 通过 "colorAci" 属性设置 ACI 颜色
 * CADEntity.builder(CADEntity.Types.LINE)
 *     .layer("道路")
 *     .geometry(line)
 *     .property("colorAci", AciColor.WHITE)
 *     .build();
 *
 * // 图层颜色由首个实体的 colorAci 自动推导，无需额外配置
 * }</pre>
 *
 * <h3>解析侧用法</h3>
 * <pre>{@code
 * Object aci = entity.getProperties().get("colorAci");
 * if (AciColor.RED == (Integer) aci) { ... }
 *
 * // 完整颜色（True Color > ACI > BYLAYER 继承链路）用 colorRgb 属性
 * int[] rgb = (int[]) entity.getProperties().get("colorRgb"); // [R, G, B]
 * }</pre>
 *
 * <h3>常见测绘/GIS 场景推荐配色</h3>
 * <table border="1">
 *   <tr><th>要素类型</th><th>推荐常量</th><th>ACI 值</th><th>颜色</th></tr>
 *   <tr><td>道路 / 注记</td><td>{@link #WHITE}</td><td>7</td><td>白（黑背景）/ 黑（白背景）</td></tr>
 *   <tr><td>等高线</td><td>{@link #YELLOW}</td><td>2</td><td>黄</td></tr>
 *   <tr><td>建筑 / 植被</td><td>{@link #GREEN}</td><td>3</td><td>绿</td></tr>
 *   <tr><td>管线 / 地下设施</td><td>{@link #CYAN}</td><td>4</td><td>青</td></tr>
 *   <tr><td>水系</td><td>{@link #BLUE}</td><td>5</td><td>蓝</td></tr>
 *   <tr><td>高程点 / 控制点</td><td>{@link #RED}</td><td>1</td><td>红</td></tr>
 *   <tr><td>境界线 / 用地边界</td><td>{@link #MAGENTA}</td><td>6</td><td>洋红</td></tr>
 *   <tr><td>辅助线 / 不可见层</td><td>{@link #DARK_GRAY}</td><td>8</td><td>深灰</td></tr>
 * </table>
 *
 * @see com.nanodxf.geometry.AciColorTable ACI → RGB 颜色转换
 */
public final class AciColor {

    private AciColor() {}

    // =========================================================================
    // 特殊值
    // =========================================================================

    /**
     * BYBLOCK（0）：继承所在块（INSERT）的颜色。
     * 仅在块定义内部的实体上使用；在模型空间实体上等同于黑色。
     */
    public static final int BYBLOCK = 0;

    /**
     * BYLAYER（256）：继承所在图层的颜色（DXF 默认值）。
     * 不显式设置 {@code colorAci} 时，CAD 软件自动使用图层颜色。
     * {@link CADParser} 解析后会通过 BYLAYER 继承链路填充 {@code colorRgb} 属性。
     */
    public static final int BYLAYER = 256;

    // =========================================================================
    // 标准色 1-9（9 种固定基础色）
    // =========================================================================

    /** ACI 1 — 红色 RGB(255, 0, 0)。测绘中常用于高程点、控制点。 */
    public static final int RED = 1;

    /** ACI 2 — 黄色 RGB(255, 255, 0)。测绘中常用于等高线（基本等高线）。 */
    public static final int YELLOW = 2;

    /** ACI 3 — 绿色 RGB(0, 255, 0)。测绘中常用于建筑物、植被。 */
    public static final int GREEN = 3;

    /** ACI 4 — 青色 RGB(0, 255, 255)。测绘中常用于管线、地下设施。 */
    public static final int CYAN = 4;

    /** ACI 5 — 蓝色 RGB(0, 0, 255)。测绘中常用于水系（河流、湖泊）。 */
    public static final int BLUE = 5;

    /** ACI 6 — 洋红色 RGB(255, 0, 255)。常用于境界线、用地范围线。 */
    public static final int MAGENTA = 6;

    /**
     * ACI 7 — 白/黑双色。
     * 在黑色背景（CAD 默认）下显示为白色 RGB(255,255,255)；
     * 在白色背景（打印/出图）下显示为黑色 RGB(0,0,0)。
     * 测绘中最常用颜色，适合道路、注记、普通线划要素。
     */
    public static final int WHITE = 7;

    /** ACI 8 — 深灰色 RGB(128, 128, 128)。常用于辅助线、不可见要素层。 */
    public static final int DARK_GRAY = 8;

    /** ACI 9 — 浅灰色 RGB(192, 192, 192)。常用于底图、参考层。 */
    public static final int LIGHT_GRAY = 9;

    // =========================================================================
    // 扩展调色板常用别名（10-249，AutoCAD 标准 24 色相 × 10 明度）
    // =========================================================================

    /**
     * ACI 10 — 橙红色 RGB(255, 0, 0) → 实为第二组色相起点。
     * 注意：扩展色在不同 CAD 软件中显示可能略有差异，建议优先使用 1-9 标准色。
     */
    public static final int ORANGE_RED = 10;

    /** ACI 30 — 橙色，近似 RGB(255, 127, 0)。可用于警告线、临时边界。 */
    public static final int ORANGE = 30;

    /** ACI 40 — 黄绿色，近似 RGB(128, 255, 0)。可用于草地、绿化带。 */
    public static final int YELLOW_GREEN = 40;

    /** ACI 80 — 深绿色，近似 RGB(0, 128, 0)。可用于林地、森林。 */
    public static final int DARK_GREEN = 80;

    /** ACI 130 — 深青色，近似 RGB(0, 128, 128)。可用于水渠、灌渠。 */
    public static final int DARK_CYAN = 130;

    /** ACI 150 — 天蓝色，近似 RGB(0, 127, 255)。可用于海岸线、大水体。 */
    public static final int SKY_BLUE = 150;

    /** ACI 170 — 深蓝色，近似 RGB(0, 0, 128)。可用于湖泊填充、深水区。 */
    public static final int DARK_BLUE = 170;

    /** ACI 200 — 紫色，近似 RGB(128, 0, 128)。可用于地铁线、特殊管线。 */
    public static final int PURPLE = 200;

    /** ACI 220 — 粉红色，近似 RGB(255, 0, 127)。可用于住宅用地、粉色区划。 */
    public static final int PINK = 220;
}
