# Changelog

所有版本的变更记录遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

---

## [1.1.0] - 2026-05-20

### 新增

**DXF 写出功能**
- `DXFWriter`：将 `CADEntity` 列表序列化为 DXF ASCII 文件
  - R12 路径：最兼容格式，无子类标记、无 owner handle，适合 AutoCAD R12+ / QGIS / LibreCAD
  - R2007 路径：完整格式，含 HEADER、CLASSES、TABLES、BLOCKS、ENTITIES、OBJECTS 全段，通过浩辰CAD（GstarCAD）验证
  - 支持几何类型：`Point` → POINT，`LineString`（2点）→ LINE，`LineString`（多点）→ LWPOLYLINE，`LinearRing` → LWPOLYLINE（闭合），`Polygon` → 外环 + 各洞 LWPOLYLINE，`GeometryCollection` → 展开
  - 支持实体属性：`colorAci`（ACI 颜色）、`colorRgb`（True Color，R2004+）、`text`（文字内容）、`height`（文字高度）、`rotation`（旋转角度）
- `DXFWriteConfig`（Builder 模式）：版本（`R12 / R2007`）、编码（自动或手动）、坐标小数位数（0~15）
- `CADEntity.Types`：DXF 实体类型字符串常量（`LINE / ARC / CIRCLE / ELLIPSE / POINT / LWPOLYLINE / POLYLINE / SPLINE / TEXT / MTEXT / HATCH / INSERT / DIMENSION` 等），消除调用方魔法字符串
- `AciColor`：ACI 颜色命名常量（标准色 1-9、`BYLAYER` / `BYBLOCK`、常用扩展别名），替代测绘代码中的裸整数
- `EntityProperty`：`CADEntity.getProperties()` 属性键常量（`COLOR_ACI / TEXT / HEIGHT / ELEVATION / FEATURE_CODE` 等 14 个键），消除属性访问中的魔法字符串
- `InsUnit`：DXF `$INSUNITS` 单位码常量（`METERS=6 / MILLIMETERS=4 / FEET=2` 等）+ `toMeters()` 换算工具方法
- `output.LineTypeName`：AutoCAD 标准线型名称常量（`CONTINUOUS / DASHED / CENTER / HIDDEN / PHANTOM` 等）

**浩辰CAD（R2007）兼容性保障**
- TABLES 段写出 VPORT（含 `*Active` 记录）、ByBlock/ByLayer/Continuous LTYPE、VIEW、UCS、DIMSTYLE（Standard）等完整表集合
- OBJECTS 段写出 ACAD_LAYOUT 字典链 + Layout1/Model LAYOUT 对象
- BLOCK_RECORD 写出 `340` 硬指针（→ LAYOUT 对象）
- 图层颜色取该层首个实体的 `colorAci`，图层列表与实体颜色一致
- 极大/极小值（如 LAYOUT 边界 ±1e20）使用科学计数法（`%.15E`），避免浮点格式异常

### 修复
- LAYOUT 对象 `±1e20` 边界坐标格式错误（`%.4f` 产生 26 字符长字符串）→ 改为 `1.000000000000000E+20`

---

## [1.0.0] - 2026-05-19

首个正式发布版本。完整实现了面向 GIS / 测绘场景的 DXF 解析链路。

### 新增

**核心解析链路**
- `DXFReader`：group code 流读取、UTF-8 BOM / juniversalchardet / GBK 三级编码检测、`pushBack` 回退
- `SectionDispatcher`：HEADER / TABLES / BLOCKS / ENTITIES / OBJECTS 段分发，未知段静默跳过
- `HeaderParser`：`$ACADVER`（版本）、`$INSUNITS`（单位）、`$MEASUREMENT`、`$CONTOURINTERVAL`
- `TablesParser`：LAYER（名称、ACI 颜色、可见性、线型）、STYLE（字体样式）、LTYPE（线型）
- `BlocksParser`：块定义解析；块内 INSERT 存为占位实体（不展开），待 ENTITIES 阶段完整展开
- `EntitiesParser`：实体分发、颜色富化（True Color > ACI > BYLAYER）、XDATA 富化、图纸空间过滤
- `ObjectsParser`：OBJECTS 段基础扫描，handle → 属性映射存入 `ctx.objectData`
- `PaperSpaceFilter`：code 67=1 实体过滤，确保只输出模型空间数据

**实体 Handler（15 种）**
- `LineHandler`：LINE，零长度自动跳过
- `ArcHandler`：ARC，弦高误差离散化
- `CircleHandler`：CIRCLE → 闭合 LinearRing
- `EllipseHandler`：ELLIPSE（含椭圆弧），参数方程离散化
- `PointHandler`：POINT，保留 Z 高程为 `elevation` 属性
- `TextHandler`：TEXT，读取对齐点与文字内容
- `MTextHandler`：MTEXT，调用 MTextCleaner 清洗格式码
- `LWPolylineHandler`：LWPOLYLINE，elevation（code 38）赋给所有顶点 Z，bulge 段圆弧离散化，闭合生成 LinearRing
- `PolylineHandler`：POLYLINE + VERTEX + SEQEND，支持 3D 折线与闭合标志
- `SplineHandler`：SPLINE，de Boor B 样条算法离散化（均匀参数采样）
- `HatchHandler`：HATCH，多边界路径解析，外环/洞分离，JTS Polygon（含多洞）
- `InsertHandler`：INSERT 块递归展开，仿射变换（缩放→旋转→平移），路径集合循环引用检测，ATTRIB 属性注入
- `SolidHandler`：SOLID → 4 顶点 Polygon
- `ThreeDFaceHandler`：3DFACE → 闭合 LinearRing（5 点）
- `DimensionHandler`：DIMENSION → 插入点 Point

**几何工具**
- `GeometryBuilder`：统一 `GeometryFactory`（FLOATING 精度模型，SRID=0）
- `Discretizer`：圆弧（弦高误差）、bulge 凸度、B 样条（de Boor）离散化
- `GeometryValidator`：DP 简化去重复顶点 + JTS `GeometryFixer` 自相交修复
- `AciColorTable`：ACI 1~9 标准色 + 10~249 调色板映射（HSV 推算）

**属性与输出**
- `MTextCleaner`：MTEXT 嵌套花括号格式码清洗、`\P` / `\~`、`%%d` / `%%p` / `%%c`、`\U+XXXX` Unicode
- `XDataParser`：CASS / EPSW / MAPMATRIX / MAPGIS / SUPERMAP 地物编码提取
- `FeatureCodeRegistry`：约 80 条 GB/T 20257 地物编码映射（房屋、道路、水系、植被、控制点等）
- `GeoJsonSerializer`：FeatureCollection 输出，坐标精度可配，CRS 标注，大坐标浮点噪声抑制，`escapeJson` 公开工具方法
- `CADLayer`：ACI 颜色号自动映射 RGB（BYLAYER 继承链路）

**公开 API**
- `CADParser`：主入口，支持 `parse(Path)` 与 `parse(Reader)`
- `ParseConfig`（Builder）：`crs`、`arcTolerance`、`coordinateDecimalPlaces`，`build()` 时参数校验
- `ParseResult`：`entities`、`errors`、`stats`、`metadata`
- `ParseStats`（record）：`parseMs`、`entityCount`、`errorCount`、`warningCount`
- `ParseError`：`level`（FATAL / WARN / INFO）、`entityType`、`handle`、`message`
- `EntityHandler`（接口）：`List<CADEntity> handle(EntityBuffer, DXFContext)`，支持 SPI 扩展

### 技术规格

- Java 17+，Maven 构建
- 运行时依赖：JTS 1.19.0、juniversalchardet 1.0.3
- 测试：JUnit 5.10.2 + AssertJ 3.24.2，24 个测试用例

---

## [0.1.0-SNAPSHOT] — 开发阶段记录

以下为开发过程中的阶段性里程碑，供参考。

### Phase 1（基础骨架）
- DXFReader + 编码检测 + GroupCodePair record
- HeaderParser、TablesParser、PaperSpaceFilter
- LINE / CIRCLE / ARC / POINT / TEXT / LWPOLYLINE 六种基础实体

### Phase 2（复杂实体）
- MTEXT（MTextCleaner 嵌套花括号清洗）
- POLYLINE + VERTEX + SEQEND
- INSERT + ATTRIB（EntityBuffer.children 机制）
- SPLINE（de Boor 算法）
- HATCH（外环 + 洞）
- ELLIPSE / 3DFACE / SOLID / DIMENSION

### Phase 3（属性与输出）
- XDATA 解析 + 多软件地物编码提取
- FeatureCodeRegistry（GB/T 20257）
- 颜色富化（True Color > ACI > BYLAYER）
- GeoJsonSerializer
- OBJECTS 段基础扫描
- 错误分级收集

### Phase 4（INSERT 展开 + 测试收尾）
- InsertHandler 完整实现：递归展开、GeometryTransformer 仿射变换、路径集合循环检测
- BlocksParser：块内 INSERT 占位策略
- BYLAYER 颜色继承（CADLayer.setColorNumber 自动映射 RGB）
- Phase 4 容错测试：截断文件、循环块引用、未知 SECTION、零长度线
- 性能基线测试（5000 实体 < 5s）
- 代码审查：ParseStats warningCount 修正、Discretizer deBoor 防越界加固
- 版本发布：1.0.0
