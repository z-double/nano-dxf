# DXF 解析器 + 写出器设计文档 v1.1

> 本文档描述 SmartCAD-Parser 中 DXF 解析器的自研实现方案。
> DWG 文件经 ODA File Converter 转换为 DXF 后，走同一套解析链路。

---

## 1. DXF 格式基础

### 1.1 Group Code 机制

DXF ASCII 格式的基本单元是 **group code + value** 的逐行对：

```
0          ← group code（整数，定义下一行的含义）
LINE       ← value
8          ← 图层名
道路中心线
10         ← 起点 X
100.000
20         ← 起点 Y
200.000
11         ← 终点 X
150.000
21         ← 终点 Y
200.000
```

常用 group code 语义：

| Group Code | 含义 |
|---|---|
| 0 | 实体类型 / 节标识 |
| 1 | 主文本内容 |
| 2 | 名称（块名、样式名等）|
| 5 | 实体 handle（唯一标识）|
| 8 | 图层名 |
| 10/20/30 | 起点或插入点 X/Y/Z |
| 11/21/31 | 终点或第二点 X/Y/Z |
| 38 | Elevation（LWPOLYLINE 整体 Z 高度）|
| 40 | 半径、高度、比例等 |
| 50 | 角度 |
| 62 | ACI 颜色号（256=BYLAYER，0=BYBLOCK）|
| 67 | 空间标志（0 或缺失=模型空间，1=图纸空间）|
| 100 | 子类标记 |
| 210/220/230 | 拉伸方向向量（OCS extrusion vector）|
| 330 | 所属对象 handle |
| 420 | True Color（24 位 RGB，R2004+，优先于 code 62）|
| 1001 | XDATA 应用名开始 |

### 1.2 文件结构

```
0 SECTION  →  2 HEADER    图纸变量（单位、版本、范围等）
0 ENDSEC

0 SECTION  →  2 CLASSES   自定义类定义（R13+）
0 ENDSEC

0 SECTION  →  2 TABLES    命名符号表（图层、线型、字体样式等）
0 ENDSEC

0 SECTION  →  2 BLOCKS    块定义（含 *Model_Space、*Paper_Space）
0 ENDSEC

0 SECTION  →  2 ENTITIES  模型空间实体（R12 遗留节）
0 ENDSEC

0 SECTION  →  2 OBJECTS   非图形对象（R13+，DICTIONARY/XRECORD）
0 ENDSEC

0 EOF
```

### 1.3 版本识别

| 版本字符串 | 对应版本 | 关键差异 |
|---|---|---|
| AC1009 | R12 | 无 CLASSES/OBJECTS，原始字节编码 |
| AC1015 | R2000 | OBJECTS 段，handle 体系完善 |
| AC1018 | R2004 | True Color（code 420）|
| AC1021 | R2007 | 官方 Unicode，MULTILEADER |
| AC1024 | R2010 | 透明度属性 |
| AC1027 | R2013 | 小幅变化 |
| AC1032 | R2018 | 当前最新 |

---

## 2. 解析器架构

```
DXFFile（文件路径）
      │
      ▼
┌─────────────────────────────────┐
│         DXFReader               │  逐行读取，输出 GroupCode 流
│  - 编码检测（juniversalchardet）│
│  - 版本识别                     │
└─────────────────────────────────┘
      │ Stream<GroupCodePair>
      ▼
┌─────────────────────────────────┐
│       SectionDispatcher         │  按 SECTION 名分发
└─────────────────────────────────┘
      │
   ┌──┴──────────────────────────────────┐
   │                                     │
   ▼                                     ▼
HeaderParser                        TablesParser
（版本、单位换算、范围）              （图层、字体、线型）
      │                                  │
      ▼                             BlocksParser
  UnitConverter                    （块定义 + 模型/图纸空间分离）
                                         │
                                    EntitiesParser（R12）
                                         │
                                         ▼
                                  PaperSpaceFilter   ← 过滤图纸空间实体
                                         │
                                  EntityDispatcher
                                         │
                    ┌──────────┬─────────┼─────────┬──────────┐
                    ▼          ▼         ▼         ▼          ▼
               LineHandler  ArcHandler  TextHandler  InsertHandler  ...
               （接收 EntityBuffer，纯函数，不操作 Reader）
                    │
                    ▼
             GeometryBuilder（→ JTS Geometry）
                    │
                    ▼
            GeometryValidator      ← 有效性检查 + 修复
                    │
                    ▼
          FeatureCompletenessChecker ← 要素完整性诊断（GIS 层）
                    │
                    ▼
            GeoJsonSerializer      ← 精度控制 + CRS 标注
                    │
                    ▼
                CADEntity
```

---

## 3. 各层职责

### 3.1 DXFReader

```java
public class DXFReader implements Closeable {
    public static DXFReader open(Path path) {
        // 编码检测见第 5 章
    }
    public GroupCodePair next()  { ... }
    public boolean hasNext()     { ... }
    public void pushBack(GroupCodePair p) { ... }  // 回退一对，用于预读
}

public record GroupCodePair(int code, String value) {
    public double asDouble() { ... }
    public int    asInt()    { ... }
}
```

### 3.2 HeaderParser + UnitConverter

HEADER 段除版本外，还需提取以下关键变量：

```java
// $INSUNITS：图纸单位，必须处理
// $EXTMIN / $EXTMAX：图纸范围（仅作参考，实际范围从几何重新计算）
// $MEASUREMENT：0=英制，1=公制
// $CONTOURINTERVAL：等高距（测绘地形图关键参数，如 1.0 表示 1 米等高距）

public class UnitConverter {
    // $INSUNITS → 换算到米的系数
    private static final Map<Integer, Double> TO_METER = Map.of(
        0,  1.0,      // 无单位，原样输出
        1,  0.0254,   // 英寸
        2,  0.3048,   // 英尺
        4,  0.001,    // 毫米（国内测绘常见）
        5,  0.01,     // 厘米
        6,  1.0,      // 米
        7,  1000.0,   // 千米
        13, 1852.0    // 海里
    );

    public double toMeter(double value, int insunits) {
        return value * TO_METER.getOrDefault(insunits, 1.0);
    }
}
```

**注意**：换算后的单位写入 `DrawingMetadata.units`，调用方据此判断坐标量级。若 `$INSUNITS=0`（未指定），记录 warning，原样输出。

### 3.3 TablesParser → DXFContext

`DXFContext` 是解析过程中的共享只读上下文。**每次 `CADParser.parse()` 调用创建独立实例，不跨文件共享，线程安全由调用方保证（并发解析多文件时，每个文件对应一个独立的解析线程和 context）。**

```java
public class DXFContext {
    // 解析完 TABLES/BLOCKS 段后填充，EntitiesParser 阶段只读
    final Map<String, CADLayer>    layers;
    final Map<String, TextStyle>   textStyles;
    final Map<String, LineTypeDef> lineTypes;
    final Map<String, CADBlock>    blocks;
    final DrawingMetadata          metadata;
    final int                      insunits;
    final double                   contourInterval; // 等高距，0 表示未定义
}
```

### 3.4 PaperSpaceFilter

图纸空间存放图框、标题栏、视口，不是测量数据，必须过滤：

```java
public class PaperSpaceFilter {
    public static boolean isModelSpace(GroupCodeContext entity) {
        // 方式一：实体 group code 67 = 1 → 图纸空间，跳过
        // 方式二（R2000+）：实体所在块名为 *Paper_Space* → 跳过
        // 方式三：VIEWPORT 实体类型 → 跳过
        return entity.getSpaceFlag() != 1
            && !entity.getOwnerBlockName().startsWith("*Paper_Space");
    }
}
```

### 3.5 EntityBuffer + EntityHandler

**EntityHandler 不直接操作 Reader**，由 Dispatcher 先将实体的全部 group code 收集到 `EntityBuffer`，再传入 handler。handler 是纯函数，输入确定输出确定，便于独立单元测试。

```java
// Dispatcher 负责收集，handler 负责解析
public class EntityBuffer {
    private final List<GroupCodePair> pairs = new ArrayList<>();
    public void add(GroupCodePair p) { pairs.add(p); }
    public List<GroupCodePair> all()  { return Collections.unmodifiableList(pairs); }
    public Optional<String> first(int code) { ... }
    public List<String>     all(int code)   { ... }
    public double getDouble(int code, double defaultVal) { ... }
}

public interface EntityHandler {
    // 纯函数：buffer 已包含该实体全部数据，不再依赖 reader 状态
    CADEntity handle(EntityBuffer buffer, DXFContext ctx);
}
```

### 3.6 EntityDispatcher + SPI 扩展

内置处理器通过硬编码注册，外部扩展通过 `ServiceLoader` 注入，支持第三方自定义实体（如国内测绘软件扩展的私有实体类型）：

```java
public class EntityDispatcher {
    public EntityDispatcher() {
        // 内置处理器
        register("LINE",          new LineHandler());
        register("ARC",           new ArcHandler());
        register("CIRCLE",        new CircleHandler());
        register("ELLIPSE",       new EllipseHandler());
        register("LWPOLYLINE",    new LWPolylineHandler());
        register("POLYLINE",      new PolylineHandler());
        register("SPLINE",        new SplineHandler());
        register("POINT",         new PointHandler());
        register("TEXT",          new TextHandler());
        register("MTEXT",         new MTextHandler());
        register("INSERT",        new InsertHandler());
        register("ATTRIB",        new AttribHandler());
        register("HATCH",         new HatchHandler());
        register("DIMENSION",     new DimensionHandler());
        register("LEADER",        new LeaderHandler());
        register("MULTILEADER",   new MultiLeaderHandler());
        register("3DFACE",        new ThreeDFaceHandler());
        register("SOLID",         new SolidHandler());
        register("VIEWPORT",      EntityHandler.SKIP);

        // SPI 扩展：外部 jar 中声明 META-INF/services/EntityHandlerProvider
        ServiceLoader.load(EntityHandlerProvider.class)
            .forEach(p -> p.handlers().forEach(this::register));
    }
}
```

---

## 4. Z 坐标策略

DXF 是三维格式，Z 值在测绘中至关重要（高程点、等高线），需要明确处理策略。

### 4.1 各实体 Z 值来源

| 实体 | Z 值位置 | 说明 |
|---|---|---|
| POINT | group code 30 | 直接是高程 |
| LINE | code 30 / 31 | 起终点各自的 Z |
| ARC / CIRCLE | code 30 | 圆心 Z |
| LWPOLYLINE | **code 38（elevation）** | 整体统一 Z，所有顶点共用，等高线就在这里 |
| POLYLINE | VERTEX 的 code 30 | 每顶点独立 Z（3D 折线）|
| INSERT | code 30 | 插入点 Z |
| TEXT / MTEXT | code 30 | 文字基点 Z |

**LWPOLYLINE 的 elevation 是最容易丢失的**：它是 2D 实体，XY 在顶点里，Z 统一存在 code 38。等高线解析时必须把 elevation 赋给所有顶点的 Z。

```java
// LWPolylineHandler 中
double elevation = 0.0;  // code 38，默认为 0
// 解析顶点时
for (Coordinate coord : coords) {
    coord.setZ(elevation);  // 统一赋高程
}
```

### 4.2 2D / 3D 输出策略

```java
// ParseConfig 中
public enum ZStrategy {
    KEEP_3D,        // 保留 Z，输出 3D geometry（测绘推荐）
    FLATTEN_2D,     // 丢弃 Z，输出 2D geometry
    Z_AS_ATTRIBUTE  // 2D geometry + Z 存入 properties["elevation"]
}
```

默认 `KEEP_3D`。当 Z 全为 0（二维图纸）时，`DrawingMetadata.is3D = false`，调用方可据此决定。

---

## 5. 关键实体的解析细节

### 5.1 LWPOLYLINE

```
0  LWPOLYLINE
8  道路
38 125.300    ← elevation（整体 Z，等高线关键值）
90 4          ← 顶点数量
70 1          ← 标志位（bit 0 = 闭合）
10 0.0  20 0.0    ← 顶点 1 XY
10 100.0 20 0.0   ← 顶点 2 XY
42 0.0        ← 顶点处的凸度（bulge，非 0 表示弧线段）
```

**凸度（bulge）处理**：bulge ≠ 0 的线段是圆弧，需插值离散化后才能表示为折线。

```java
// bulge = tan(夹角/4)，正值逆时针，负值顺时针
if (Math.abs(bulge) > 1e-10) {
    List<Coordinate> arcPts = Discretizer.bulge(p1, p2, bulge, tolerance);
    coords.addAll(arcPts);
} else {
    coords.add(p2);
}
```

闭合处理：闭合 LWPOLYLINE 构建 Polygon 时，首尾点必须完全相同。

```java
boolean closed = (flags & 1) == 1;
if (closed && !coords.get(0).equals2D(coords.get(coords.size() - 1))) {
    coords.add(new Coordinate(coords.get(0)));  // 强制闭合
}
```

### 5.2 POLYLINE + VERTEX + SEQEND

POLYLINE 的 flag（code 70）决定类型：

| bit | 含义 |
|---|---|
| 1 | 闭合 |
| 8 | 3D 折线（VERTEX 有独立 Z）|
| 16 | 多面体网格（3D mesh）|
| 64 | 3D 多边形网格 |

```java
while (true) {
    GroupCodePair pair = reader.next();
    if (pair.code() == 0) {
        if ("SEQEND".equals(pair.value())) break;
        if ("VERTEX".equals(pair.value())) {
            coords.add(parseVertex(reader, is3D));
        }
    }
}
```

### 5.3 HATCH（含内边界洞）

HATCH 可以有多条边界路径，外边界（外环）和内边界（洞）需分别处理：

```
91  2          ← 2 条边界路径
92  1          ← 第1条：外边界（pathType bit 0 = 1）
93  4  ...     ← 4条线段
92  16         ← 第2条：内边界（pathType bit 4 = 1 表示外部 outermost 以外）
93  3  ...
```

```java
LinearRing shell = null;
List<LinearRing> holes = new ArrayList<>();

for (BoundaryPath path : paths) {
    LinearRing ring = buildRing(path, tolerance);
    ring = normalizeWindingOrder(ring);  // 统一绕行方向（见第 6 章）

    if (path.isOuter()) {
        shell = ring;       // 外环
    } else {
        holes.add(ring);    // 内环（洞）
    }
}

Polygon polygon = geometryFactory.createPolygon(
    shell, holes.toArray(new LinearRing[0])
);
```

### 5.4 INSERT 块展开

```
0 INSERT
2  高程点       ← 块名
10/20/30        ← 插入点
41/42/43        ← X/Y/Z 比例
50              ← 旋转角（度）
66  1           ← 有后续 ATTRIB
0 ATTRIB
1  1234.56      ← 属性值
2  ELV          ← 属性标签（tag）
0 SEQEND
```

坐标变换（完整 2D 仿射）：

```java
// 注意 DXF 角度是度，需转弧度
double rad = Math.toRadians(rotation);
double cos = Math.cos(rad), sin = Math.sin(rad);

double x = scaleX * (ex * cos - ey * sin) + insertX;
double y = scaleY * (ex * sin + ey * cos) + insertY;
double z = scaleZ * ez + insertZ;
```

**防循环展开**：

深度限制不能防真正的循环引用（A 块含 B 的 INSERT，B 块含 A 的 INSERT，深度只有 2，永远不会触发深度限制）。必须用路径集合检测：

```java
private void expandBlock(String blockName, Deque<String> expansionPath) {
    // 检测真正的循环引用：路径中已存在此块名
    if (expansionPath.contains(blockName)) {
        warnings.add("检测到块循环引用，跳过：" + String.join(" → ", expansionPath) + " → " + blockName);
        return;
    }
    // 同时防深度过大（文件损坏等异常情况）
    if (expansionPath.size() > 16) {
        warnings.add("块嵌套超过 16 层，跳过：" + blockName);
        return;
    }
    expansionPath.push(blockName);
    try {
        // ... 展开逻辑
    } finally {
        expansionPath.pop();
    }
}
```

### 5.5 MTEXT 格式码清洗

| 控制码 | 含义 | 处理 |
|---|---|---|
| `\P` | 段落换行 | → `\n` |
| `\~` | 不换行空格 | → ` ` |
| `{\fArial|b0;文字}` | 字体块 | 提取内文字 |
| `{\H2.5;文字}` | 字高块 | 提取内文字 |
| `{\C1;文字}` | 颜色块 | 提取内文字 |
| `%%d` | ° | 替换 |
| `%%p` | ± | 替换 |
| `%%c` | ⌀ | 替换 |
| `\U+XXXX` | Unicode | 解码 |

```java
public class MTextCleaner {
    // 嵌套花括号需要递归处理，正则只能处理单层
    public static String clean(String raw) {
        String s = resolveUnicode(raw);
        s = stripFormatBlocks(s);        // 递归剥离 {...;...} 块
        s = s.replace("\\P", "\n").replace("\\~", " ");
        s = replaceSpecialSymbols(s);
        return s.strip();
    }

    private static String stripFormatBlocks(String s) {
        // 用栈处理嵌套花括号，提取 ; 之后的内容
        ...
    }
}
```

### 5.6 SPLINE

```java
// de Boor 算法离散化
// group code 71=degree, 72=knot数, 74=控制点数
// 40=节点向量（重复），10/20/30=控制点（重复）

public static List<Coordinate> discretizeSpline(
        int degree, double[] knots, double[][] ctrlPts, double tolerance) {
    // 按弦高误差自适应采样，而非均匀采样
    // 均匀采样在曲率大的地方不够密，在平直处浪费点
    return adaptiveSample(degree, knots, ctrlPts, tolerance);
}
```

### 5.7 XDATA（CASS 地物编码）

国内最常用的测绘软件**南方 CASS** 把地物编码存在 XDATA 里，这是获取地物类型的关键数据：

```
1001  CASS       ← 应用名（CASS 的标识）
1000  41000      ← 地物编码（字符串）：如 41000=普通房屋
```

其他测绘软件也有类似约定（EPS、MapMatrix 等），应用名不同。

```java
public class XDataParser {
    public Map<String, List<XDataEntry>> parse(DXFReader reader) {
        Map<String, List<XDataEntry>> result = new LinkedHashMap<>();
        String appName = null;
        while (reader.hasNext()) {
            GroupCodePair p = reader.next();
            if (p.code() == 1001) { appName = p.value(); result.put(appName, new ArrayList<>()); }
            else if (p.code() >= 1000 && p.code() <= 1071 && appName != null) {
                result.get(appName).add(new XDataEntry(p.code(), p.value()));
            } else { reader.pushBack(p); break; }  // XDATA 结束
        }
        return result;
    }
}
```

XDATA 解析结果放入 `entity.properties["xdata"]`，并提取 CASS 地物编码放入 `entity.properties["featureCode"]`（如存在）。

---

## 6. 几何有效性与修复

CAD 数据质量不稳定，直接入库 PostGIS 经常报错，必须有修复管线。

### 6.1 问题类型与修复策略

| 问题 | 检测方法 | 修复方式 |
|---|---|---|
| 重复顶点 | 相邻点距离 < 阈值 | 合并相邻重复点 |
| 近似未闭合 | 首尾点距离 < 阈值但不完全相同 | 强制令尾点 = 首点 |
| 自相交 | JTS `isValid()` | `GeometryFixer.fix()` |
| 零长度线段 | 起终点相同 | 转为 Point 或跳过 |
| 顶点数 < 2 | 顶点计数 | 降级：2点变线，1点变点 |

```java
public class GeometryValidator {
    private static final double SNAP_TOLERANCE = 1e-6;  // 可配置

    public static Geometry validate(Geometry geom) {
        if (geom == null) return null;

        // 1. 清理重复点
        geom = DouglasPeuckerSimplifier.simplify(geom, 0);

        // 2. 有效性检查
        if (!geom.isValid()) {
            geom = GeometryFixer.fix(geom);  // JTS 1.19+ 提供
            if (!geom.isValid()) {
                // 修复失败，记 warning，返回 null
                return null;
            }
        }
        return geom;
    }
}
```

### 6.2 Winding Order（绕行方向）

GeoJSON RFC 7946 标准：外环逆时针（CCW），内环顺时针（CW）。
CAD 不保证绕行方向，必须统一修正。

```java
public static LinearRing normalizeWindingOrder(LinearRing ring, boolean isOuter) {
    boolean isCCW = Orientation.isCCW(ring.getCoordinateSequence());
    if (isOuter && !isCCW) return ring.reverse();   // 外环要 CCW
    if (!isOuter && isCCW) return ring.reverse();   // 内环要 CW
    return ring;
}
```

### 6.3 大坐标精度控制

CGCS2000 投影坐标 X 值约 3,000,000~6,000,000，Y 值约 200,000~700,000。JSON 序列化时默认精度可能引入浮点噪声（如 `3456789.1234560001`）。

```java
public class GeoJsonSerializer {
    // 坐标保留小数位数，与测绘精度匹配
    // 毫米级精度：3 位；厘米级：2 位；地形图可用 4 位
    private final int coordinateDecimalPlaces;

    public String serializeCoordinate(double value) {
        return String.format("%." + coordinateDecimalPlaces + "f", value);
    }
}
```

默认 `coordinateDecimalPlaces = 4`（0.1mm 精度），通过 `ParseConfig` 配置。

### 6.4 Bounding Box 重新计算

`$EXTMIN`/`$EXTMAX` 在实际 CAD 文件中经常未更新，不可信。从所有实体几何计算实际范围：

```java
Envelope envelope = new Envelope();
for (CADEntity entity : entities) {
    if (entity.geometry() != null) {
        envelope.expandToInclude(entity.geometry().getEnvelopeInternal());
    }
}
metadata.setActualExtents(envelope);  // 覆盖 header 里的值
```

---

## 7. 颜色解析

颜色解析需要处理三种情况：

```java
public int[] resolveColor(int aciCode, Integer trueColor, String layerName, DXFContext ctx) {
    // 优先级：True Color > ACI > BYLAYER > BYBLOCK
    if (trueColor != null) {
        // code 420：24位 RGB，直接解码
        int r = (trueColor >> 16) & 0xFF;
        int g = (trueColor >> 8)  & 0xFF;
        int b =  trueColor        & 0xFF;
        return new int[]{r, g, b};
    }
    if (aciCode == 256) {
        // BYLAYER：继承图层颜色
        return ctx.layers.get(layerName).colorRgb();
    }
    if (aciCode == 0) {
        // BYBLOCK：继承所在块颜色，展开时由 InsertHandler 传入
        return null;  // 延迟到展开时解析
    }
    return AciColorTable.toRgb(aciCode);  // ACI 256 色表
}
```

---

## 8. 编码处理策略

```
读取文件前 512 字节
        │
        ├── 有 BOM (EF BB BF) → UTF-8
        │
        ├── 读 $ACADVER
        │       ├── >= AC1021 (R2007) → 理论 UTF-8，但仍用 chardet 验证
        │       └── < AC1021          → juniversalchardet 检测
        │                                 ├── 检测到 GBK/GB2312 → GBK
        │                                 └── 检测失败 → 尝试 UTF-8，失败则 GBK
        │
        └── 解析失败字符：替换为 ? + 记 warning，不中断解析
```

依赖：`com.googlecode.juniversalchardet:juniversalchardet:1.0.3`

---

## 9. 几何构建（JTS）

```java
public class GeometryBuilder {
    private static final GeometryFactory GF = new GeometryFactory(
        new PrecisionModel(PrecisionModel.FLOATING), 0
    );

    public static Geometry buildArc(double[] center, double radius,
                                    double startDeg, double endDeg,
                                    double tolerance) {
        List<Coordinate> pts = Discretizer.arc(center, radius, startDeg, endDeg, tolerance);
        return GF.createLineString(pts.toArray(new Coordinate[0]));
    }
}

public class Discretizer {
    // 弧线：基于弦高误差（sagitta）计算最少点数
    // sagitta = r * (1 - cos(θ/2)) ≤ tolerance
    public static List<Coordinate> arc(double[] center, double r,
                                       double startDeg, double endDeg,
                                       double tolerance) {
        double spanRad = Math.toRadians(Math.abs(endDeg - startDeg));
        int n = (int) Math.ceil(spanRad / (2 * Math.acos(1.0 - tolerance / r)));
        n = Math.max(n, 8);
        ...
    }

    // LWPOLYLINE bulge 段转弧线
    public static List<Coordinate> bulge(Coordinate p1, Coordinate p2,
                                         double bulge, double tolerance) {
        // 从 bulge 值还原圆弧参数，再调用 arc()
        double angle = 4 * Math.atan(bulge);
        double chord = p1.distance(p2);
        double r = chord / (2 * Math.sin(angle / 2));
        ...
    }

    // SPLINE 自适应采样（按曲率密度调整，非均匀）
    public static List<Coordinate> spline(int degree, double[] knots,
                                          double[][] ctrlPts, double tolerance) {
        return adaptiveSample(degree, knots, ctrlPts, tolerance);
    }
}
```

---

## 10. 国内测绘软件适配

### 10.1 主流软件的 XDATA 应用名约定

| 软件 | XDATA 应用名 | 地物编码字段 | 备注 |
|---|---|---|---|
| 南方 CASS | `CASS` | code 1000 | 国内最广泛，块名也是编码 |
| 清华山维 EPS | `EPSW` | code 1000 | 二三维一体化测图 |
| 武汉大学 MapMatrix | `MAPMATRIX` | code 1000 | 摄影测量主流 |
| 中地数码 MapGIS | `MAPGIS` | code 1002 | GIS 平台导出 DXF |
| SuperMap | `SUPERMAP` | code 1000 | 同上 |

解析策略：逐一尝试已知应用名，找到即提取；未匹配的 XDATA 原样保留在 `xdata` 字段，不丢弃。

```java
public class XDataParser {
    private static final List<String> KNOWN_APPS =
        List.of("CASS", "EPSW", "MAPMATRIX", "MAPGIS", "SUPERMAP");

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
```

### 10.2 地物编码映射表（基于 GB/T 20257）

完整映射表以独立 JSON 文件形式内置（`resources/feature_codes.json`），而非硬编码在 Java 里，便于后期扩充：

```json
{
  "41000": {"name": "普通房屋",   "category": "建筑"},
  "41010": {"name": "简单房屋",   "category": "建筑"},
  "41080": {"name": "在建房屋",   "category": "建筑"},
  "24101": {"name": "水准点",     "category": "控制点"},
  "24201": {"name": "导线点",     "category": "控制点"},
  "31010": {"name": "硬化路面",   "category": "道路"},
  "31020": {"name": "未硬化路面", "category": "道路"},
  "51101": {"name": "一般等高线", "category": "地貌"},
  "51102": {"name": "计曲线",     "category": "地貌"}
}
```

GB/T 20257 完整编码约 400 条，分批录入，优先覆盖房屋、道路、水系、植被、控制点五大类。

命中时写入 `entity.properties["featureType"]` 和 `featureCategory"`，`featureTypeSource = "registry"`。

---

## 11. 版本兼容策略

```java
if (context.getVersion().before(DXFVersion.R2000)) {
    // R12：实体直接在 ENTITIES 段
    parseEntitiesSection(reader, context, modelSpaceEntities);
} else {
    // R2000+：模型空间在 *Model_Space 块里
    modelSpaceEntities = context.blocks.get("*Model_Space").entities();
}
```

**R12 额外注意**：
- 没有 OBJECTS 段，直接跳过
- 不支持 True Color（code 420）
- ATTRIB 和 INSERT 的 handle 体系不完整，用顺序号兜底

---

## 12. 错误处理原则

### 12.1 错误分级

不是所有错误都能容忍，分三级处理：

| 级别 | 情形 | 处理方式 |
|---|---|---|
| FATAL | 文件头损坏、无法识别格式、SECTION 结构完全混乱 | 抛 `DXFParseException`，终止解析 |
| WARN | 单实体解析失败、几何修复失败、未知实体类型 | 跳过该实体 + 记入 warnings，继续 |
| INFO | 使用了默认值填补缺失字段、$INSUNITS 为 0 | 记入 info 日志，不影响结果 |

```java
public enum ParseErrorLevel { FATAL, WARN, INFO }

public class ParseError {
    ParseErrorLevel level;
    String          entityType;
    String          handle;
    String          message;
}
```

### 12.2 实体级宽容解析

```java
try {
    CADEntity entity = handler.handle(reader, ctx);
    if (entity != null) entities.add(entity);
} catch (DXFParseException e) {
    result.addError(ParseErrorLevel.WARN, type, handle, e.getMessage());
    skipToNextEntity(reader);  // 快进到下一个 code=0
}
```

### 12.3 几何修复失败

几何无法修复时，实体不丢弃，geometry 置 null，保留 properties：

```java
entity = entity.withGeometry(null);
result.addError(ParseErrorLevel.WARN, entityType, handle, "几何无效，已置空");
```

所有错误汇总到 `ParseResult.errors`，调用方可按级别过滤查看。

---

## 13. CRS 标注机制

DXF 文件本身不存储坐标参考系信息。解析器的处理策略：

**输入**：调用方通过 `ParseConfig.crs` 传入坐标系标识（EPSG 代码或自定义字符串）。

```java
ParseConfig config = ParseConfig.builder()
    .crs("EPSG:4545")   // CGCS2000 / 3-Degree GK CM 117E
    .build();
```

**输出**：CRS 信息写入 `DrawingMetadata` 和 GeoJSON 顶层属性：

```json
{
  "type": "FeatureCollection",
  "crs": {
    "source": "caller_specified",
    "value": "EPSG:4545"
  },
  "features": [...]
}
```

`source` 字段区分三种情况：
- `caller_specified`：调用方明确传入，可信
- `inferred`：从图纸元数据推断（预留，暂不实现）
- `unknown`：未知，调用方需自行处理

坐标变换（地方坐标系 → 标准坐标系）**不在本库职责范围**，由调用方在入库前处理。

---

## 14. ParseConfig 校验

`ParseConfig` 在 `build()` 时校验，不把错误推迟到运行时：

```java
public ParseConfig build() {
    if (arcTolerance <= 0)
        throw new IllegalArgumentException("arcTolerance 必须 > 0，当前值：" + arcTolerance);
    if (proximityThreshold < 0)
        throw new IllegalArgumentException("proximityThreshold 不能为负");
    if (coordinateDecimalPlaces < 0 || coordinateDecimalPlaces > 15)
        throw new IllegalArgumentException("coordinateDecimalPlaces 范围 0~15");
    return new ParseConfig(this);
}
```

---

## 15. 要素完整性诊断

GIS 入库前的基础检查，不修复，只诊断，结果写入 `QualityReport.completenessIssues`：

| 检查项 | 判断条件 | 严重程度 |
|---|---|---|
| 道路中心线断头 | LineString 端点与其他线距离 > 阈值 | WARN |
| 建筑面未闭合 | Polygon 构建失败的 LWPOLYLINE | ERROR |
| 等高线高程缺失 | elevation=0 且无注记关联 | WARN |
| 孤立注记 | orphanTexts 数量占总文字比例 > 30% | INFO |
| 高程点无高程值 | POINT 实体 Z=0 且无邻近注记 | WARN |

```java
public class FeatureCompletenessChecker {
    public List<CompletenessIssue> check(List<CADEntity> entities, QualityReport report) {
        // 不修改实体，只产生诊断报告
        ...
    }
}
```

---

## 16. 资源管理

### 16.1 临时文件清理

ODA Converter 把 DWG 转换为 DXF 时产生临时文件，必须明确清理时机：

```java
public class ODAConverter {
    public Path convert(Path dwgFile) throws IOException {
        Path tempDxf = Files.createTempFile("smartcad_", ".dxf");
        // ... 调用 ODA Converter
        return tempDxf;  // 调用方负责删除
    }
}

// CADParser.parse() 内部：
Path tempDxf = null;
try {
    if (isDwg(path)) tempDxf = ODAConverter.convert(path);
    Path dxfPath = tempDxf != null ? tempDxf : path;
    return dxfParser.parse(dxfPath, config);
} finally {
    if (tempDxf != null) Files.deleteIfExists(tempDxf);  // 无论成败都清理
}
```

### 16.2 DXFReader 资源释放

`DXFReader` 实现 `Closeable`，调用方通过 try-with-resources 使用：

```java
// DXFParser 内部，调用方不直接接触 DXFReader
try (DXFReader reader = DXFReader.open(path)) {
    return parse(reader, config, context);
}
```

---

## 17. 可观测性

不引入 Micrometer 等监控框架，通过 **SLF4J 结构化日志** 实现基础可观测性：

```java
// 解析开始
log.info("开始解析 file={} format={} size={}KB", filename, format, sizeKb);

// 版本和单位（关键参数）
log.info("DXF版本={} 单位={} 等高距={}", version, units, contourInterval);

// 解析完成（关键指标）
log.info("解析完成 file={} entities={} errors={} warnings={} elapsed={}ms",
    filename, entityCount, errorCount, warningCount, elapsed);

// 慢操作告警（>5秒）
if (elapsed > 5000) log.warn("解析耗时过长 file={} elapsed={}ms", filename, elapsed);
```

`ParseResult` 中也暴露基础耗时：

```java
public record ParseStats(
    long parseMs,       // 总解析耗时
    int  entityCount,
    int  errorCount,
    int  warningCount
) {}
```

---

## 18. DXF 写出器设计（v1.1 新增）

### 18.1 核心设计决策

**目标 CAD 软件**：浩辰 CAD（GstarCAD），要求 AC1021（R2007）格式，不支持 R2000/R2004。

**双路径策略**：

| 路径 | 版本 | 结构 | 适用场景 |
|---|---|---|---|
| R12 | AC1009 | HEADER + TABLES(LAYER) + ENTITIES | 最广泛兼容，文件最小 |
| R2007 | AC1021 | 完整六段结构 | 浩辰CAD、中望CAD、AutoCAD 2010+ |

### 18.2 R2007 必需的完整结构

通过比对参考文件（能正常打开的 城建.dxf，AC1021）逐步确认了以下浩辰CAD的强制要求：

#### HEADER 段必需变量

```
$ACADVER    = AC1021
$ACADMAINTVER = 50        ← R2007+ 必须，缺少会报版本不兼容
$DWGCODEPAGE = ANSI_936  ← GBK 编码时写 ANSI_936，不能写 "UTF-8"
$INSUNITS   = 6           ← 坐标单位（6=米）
$EXTMIN/$EXTMAX           ← 必须是真实包围盒，不能 min==max==0
$HANDSEED   = 1000        ← 下一个可用句柄，不能缺失
```

#### TABLES 段必需的完整表集（顺序严格）

```
VPORT   ← 必须包含 *Active 记录（浩辰CAD 会查找此记录）
LTYPE   ← 必须包含 ByBlock + ByLayer + Continuous 三条记录
LAYER   ← 图层定义
STYLE   ← 必须包含 Standard 文字样式
VIEW    ← 空表，但必须存在
UCS     ← 空表，但必须存在
APPID   ← 必须包含 ACAD 应用注册
DIMSTYLE← 必须包含 Standard，句柄用 code 105（非 code 5！）
BLOCK_RECORD ← 必须最后，含 *Model_Space 和 *Paper_Space
```

#### BLOCK_RECORD 的 340 硬指针

R2007 中 BLOCK_RECORD 必须通过 code 340 硬指针指向对应 LAYOUT 对象：

```
BLOCK_RECORD  *Model_Space  → 340 <Model LAYOUT 句柄>
BLOCK_RECORD  *Paper_Space  → 340 <Layout1 LAYOUT 句柄>
```

#### OBJECTS 段 LAYOUT 链

```
root DICTIONARY (handle C)
  └─ ACAD_LAYOUT → LAYOUT dict (handle 1A)
        ├─ Layout1 → LAYOUT object (handle 1E)  ← 图纸空间，330 指向 *Paper_Space BR
        └─ Model   → LAYOUT object (handle 22)  ← 模型空间，330 指向 *Model_Space BR
                                                    331 指向 *Active VPORT 记录
```

每个 LAYOUT 对象包含两个子类：
- `AcDbPlotSettings`：打印设置（浩辰CAD 会校验此子类存在）
- `AcDbLayout`：布局几何信息

### 18.3 句柄分配策略

固定句柄（与参考文件对齐，避免冲突）：

```
1  = BLOCK_RECORD TABLE
2  = LTYPE TABLE
3  = LAYER TABLE
4  = STYLE TABLE
5  = Continuous LTYPE 记录
6  = Standard STYLE 记录
C  = root DICTIONARY
1A = ACAD_LAYOUT 子字典
1B = *Paper_Space BLOCK_RECORD
1C = *Paper_Space BLOCK
1D = *Paper_Space ENDBLK
1E = Layout1 LAYOUT 对象
1F = *Model_Space BLOCK_RECORD
20 = *Model_Space BLOCK
21 = *Model_Space ENDBLK
22 = Model LAYOUT 对象
E0 = VPORT TABLE
E1 = APPID TABLE
E2 = ACAD 应用注册记录
E3 = *Active VPORT 记录
E4 = ByBlock LTYPE 记录
E5 = ByLayer LTYPE 记录
E6 = VIEW TABLE
E7 = UCS TABLE
E8 = DIMSTYLE TABLE
E9 = Standard DIMSTYLE 记录
```

动态分配：
- 图层记录：从 `0x10` 开始递增
- 实体记录：从 `0x100` 开始递增
- `$HANDSEED = 0x1000`（安全上限，不与上述句柄冲突）

### 18.4 数值格式注意事项

LAYOUT 对象中使用 `±1e20` 作为边界无穷大值。若用 `%.4f` 格式化会产生 26 字符长字符串（`100000000000000000000.0000`），部分解析器无法处理。正确格式：

```java
// 错误：%.4f → "100000000000000000000.0000"
// 正确：%.15E → "1.000000000000000E+20"
if (Math.abs(v) >= 1e15) {
    return String.format("%.15E", v);
}
```

### 18.5 编码选择

| 版本 | 推荐编码 | `$DWGCODEPAGE` | 说明 |
|---|---|---|---|
| R12 | GBK | 无（R12 不写此字段）| 最简兼容 |
| R2007（纯 ASCII 内容）| UTF-8 | ANSI_1252 | DXF 官方规范 |
| R2007（中文图层名）| GBK | ANSI_936 | 浩辰CAD 按 `$DWGCODEPAGE` 解码 |

> **注意**：浩辰CAD 对 R2007 文件优先按 `$DWGCODEPAGE` 确定编码，而非硬编码 UTF-8。
> 含中文内容时，必须显式 `.encoding("GBK")` + 自动写出 `ANSI_936`。

### 18.6 公开 API 常量类（v1.1 新增）

为消除调用方魔法字符串，新增以下常量类：

| 类 | 包 | 解决的问题 |
|---|---|---|
| `CADEntity.Types` | `entity` | 实体类型字符串 22 个，如 `LINE / LWPOLYLINE / TEXT` |
| `AciColor` | 顶层 | ACI 颜色码，标准色 1-9 + 特殊值 BYLAYER/BYBLOCK + 扩展别名 |
| `EntityProperty` | 顶层 | `getProperties()` 属性键 14 个，如 `COLOR_ACI / TEXT / ELEVATION` |
| `InsUnit` | 顶层 | `$INSUNITS` 单位码 + `toMeters()` 换算工具 |
| `output.LineTypeName` | `output` | 标准线型名，如 `CONTINUOUS / CENTER / DASHED` |

---

## 18. API 稳定性声明

以下接口为 **稳定 API**，v1.x 版本内保持向后兼容：
- `CADParser.parse()` 方法签名
- `ParseResult`、`CADEntity`、`CADLayer`、`QualityReport` 的公开 getter
- `ParseConfig`、`AIConfig` 的 Builder 接口
- `EntityHandler` 接口

以下为 **实验性 API**（`@Experimental` 标注），可能在 minor 版本中变更：
- `EntityHandlerProvider` SPI 接口
- `FeatureCompletenessChecker`
- `XDataParser` 的具体解析结果结构

调用方不应该依赖 `DXFContext`、`EntityBuffer`、各 Handler 实现类——这些是内部 API，不在稳定承诺范围内。

---

## 19. 实现阶段

**总工期：12 周（1 人），或 8 周（2 人并行）。**

下表为单人串行节奏，括号内标注主要风险点。

### Phase 1 - 核心骨架（3 周）
- DXFReader：group code 流 + 编码检测
- HeaderParser：版本、`$INSUNITS` 单位换算
- TablesParser：图层、字体样式
- PaperSpaceFilter
- 基础实体：LINE、POINT、CIRCLE、ARC、LWPOLYLINE（含 elevation + bulge）、TEXT
- GeometryValidator：重复点、winding order、有效性修复
- **里程碑**：能解析 CASS 简单地形图，输出可入库的 GeoJSON

### Phase 2 - 复杂实体（4 周）
- MTEXT 嵌套花括号格式码清洗（**高风险，嵌套层次复杂**）
- POLYLINE + VERTEX + SEQEND（含 3D 标志）
- INSERT + ATTRIB + 递归展开 + 循环引用检测（**高风险**）
- SPLINE 自适应离散化（**高风险，de Boor 算法**）
- HATCH 外边界 + 内边界洞（**中风险，四种线段类型各需实现**）
- DIMENSION、ELLIPSE、3DFACE、MULTILEADER
- **里程碑**：通过对比测试（见第 14 章），主要实体类型与 ezdxf 输出一致

### Phase 3 - 属性与适配（3 周）
- OBJECTS 段解析（DICTIONARY → XRECORD）
- XDATA 解析 + 多软件地物编码提取
- 地物编码映射表完整录入（GB/T 20257，约 400 条）
- R12 兼容
- 坐标精度控制序列化
- 错误分级完善
- **里程碑**：CASS、EPS、MapMatrix 样本文件全部通过回归测试

### Phase 4 - 测试与收尾（2 周）
- 补充边界情况测试（损坏文件、截断文件、混合编码）
- 性能基线测量（10MB 文件 < 5 秒为基准）
- 文档与示例
- **里程碑**：v1.0 发布

---

## 20. 测试策略

自研解析器最大的风险是"写完跑通了，但输出是错的"。测试必须有黄金标准对比，不能只做单元测试。

### 14.1 测试分层

| 层次 | 内容 | 工具 |
|---|---|---|
| 单元测试 | 每个 handler 解析正确的 group code 序列 | JUnit 5 + 手写 DXF 片段 |
| 集成测试 | 完整文件解析，对比 ezdxf 输出 | Python ezdxf 生成黄金数据 |
| 回归测试 | 每次改动后跑全量，防止已有实体被破坏 | CI 自动触发 |
| 容错测试 | 损坏文件、截断文件、乱码文件 | 手工构造异常 fixture |

### 14.2 黄金标准对比（核心）

```
真实 DXF 文件
    ├── Python ezdxf 解析 → JSON（黄金数据）
    └── 自研 Java 解析器 → JSON
                ↓
        字段级对比（坐标精度允许 1e-6 误差）
```

对比脚本放在 `test/fixtures/compare.py`，CI 流程中自动运行。发现差异时输出具体 handle 和字段，便于定位。

### 14.3 样本文件库

`test/fixtures/` 目录需收集：

```
fixtures/
├── basic/          基础实体（LINE、ARC、TEXT 等）
├── cass/           南方 CASS 输出的真实地形图
├── eps/            清华山维 EPS 样本
├── versions/       R12、R2000、R2007、R2013、R2018 各一份
├── edge_cases/     截断文件、乱码、循环块引用、自相交几何
└── large/          > 10MB 的文件（性能基线用）
```

**来源**：找甲方或开源数据集，不要用自己生成的假数据——假数据测不出真实问题。

### 14.4 性能基线

不做优化目标，但要有基线意识：

| 文件大小 | 期望解析时间 |
|---|---|
| 1 MB | < 1 秒 |
| 10 MB | < 10 秒 |
| 50 MB | 可接受（记录实测值）|

超出基线时记录，不阻塞 v1.0，但纳入后续优化 backlog。

---

## 21. OBJECTS 段解析

OBJECTS 段（R2000+）通过 `DICTIONARY → XRECORD` 结构存储非图形对象数据，和 XDATA 是并列的两套附加属性机制，不能只处理其中一个。

```
0 DICTIONARY
5  A1B2       ← dictionary 的 handle
3  ACAD_XDICTIONARY   ← 键名
350 C3D4      ← 指向的对象 handle
...

0 XRECORD
5  C3D4
100 AcDbXrecord
...（数据字段）
```

解析策略：

1. 解析 OBJECTS 段，建立 `handle → ObjectData` 的映射表
2. 实体解析时，通过 `owner handle`（code 330）和 `ACAD_XDICTIONARY` 指针找到关联的 XRECORD
3. 将 XRECORD 内容合并到对应实体的 `properties["objectData"]`

R12 无 OBJECTS 段，直接跳过。

---

## 22. 维护策略

### 16.1 格式版本追踪

AutoCAD 每年发布新版本，DXF 格式随之更新。建立以下机制：

- 在 `DXFVersion.java` 中维护版本枚举，新版本发布时同步添加
- 每个新版本发布后，用该版本 AutoCAD 导出 DXF 样本，加入 `fixtures/versions/` 并跑回归测试
- 回归失败说明格式有变化，需针对性修复

### 16.2 不兼容实体的处理

遇到未知实体类型不是报错，而是记入 `QualityReport.skippedEntityTypes`。版本升级后，`skippedEntityTypes` 中出现新类型，即是需要新增 handler 的信号。

### 16.3 测绘软件适配扩展

`XDataParser` 中 `KNOWN_APPS` 列表和 `feature_codes.json` 均以配置文件形式维护，不需要改代码即可扩充新软件支持。

---

## 23. 依赖

```groovy
// 几何计算与空间索引
implementation 'org.locationtech.jts:jts-core:1.19.0'

// 编码检测
implementation 'com.googlecode.juniversalchardet:juniversalchardet:1.0.3'

// 测试
testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
testImplementation 'org.assertj:assertj-core:3.24.2'

// 测试用黄金数据生成（Python ezdxf，不进 Java 依赖）
// pip install ezdxf  →  test/fixtures/generate_golden.py
```

不引入任何 CAD 第三方库，完全自研。