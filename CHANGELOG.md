# Changelog

所有版本的变更记录遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

---

## [1.3.0] - 2026-05-21

### 新增

**写出实体类型扩充（方向 A）**
- `ELLIPSE` 写出：type=`ELLIPSE` + geometry=`Point`（圆心）+ properties `{majorAxisX, majorAxisY, axisRatio, startAngle, endAngle}` → DXF ELLIPSE。R12 / R2007 双路径。
- `SOLID` 写出：type=`SOLID` + geometry=`Polygon`（4 顶点）或 `LinearRing` → DXF SOLID，自动处理蝴蝶结顶点顺序。
- `3DFACE` 写出：type=`3DFACE` + geometry=`LinearRing`（3~4 顶点）→ DXF 3DFACE，edge 可见性标志默认全可见。
- `SPLINE` 写出：type=`SPLINE` + geometry=`LineString` + `controlPoints` 属性 → DXF SPLINE（三次样条，自动生成 clamped uniform 节点向量）。无控制点时降级为 LWPOLYLINE。

**解析增强（方向 C）**
- `LEADER` 解析：新增 `LeaderHandler`，将引线顶点序列解析为 JTS `LineString`，替代原来的 `SKIP`。
- `DIMENSION` 增强：新增提取 code 42（实测值 `dimensionValue`）、code 13/23/33（`dimPoint1`）、code 14/24/34（`dimPoint2`）、code 50（`dimRotation`）。
- `SPLINE` 增强：解析时额外将控制点列表（`List<double[]>`）存入 properties `controlPoints`，支持写出时往返还原为 DXF SPLINE。

**流式解析 API（方向 D）**
- `CADParser.parseStream(Path)` 新方法：两阶段策略（快速预解析 BLOCKS/TABLES → 惰性流出 ENTITIES），返回 `Stream<CADEntity>`，大文件内存友好。流持有文件句柄，必须在 try-with-resources 中使用。

**Shapefile 输出（方向 E）**
- `ShapefileWriter`：纯 Java 实现（无额外依赖），输出 `.shp`（几何）+ `.shx`（索引）+ `.dbf`（属性）+ `.prj`（坐标系说明）。
- 几何类型映射：`Point` → Shape Type 1，`LineString/MultiLineString` → Shape Type 3，`Polygon/MultiPolygon` → Shape Type 5；Shapefile 外环 CCW / 内环 CW 自动纠正。
- DBF 属性字段：`LAYER`(C64)、`ETYPE`(C16)、`TEXT`(C254)、`FEAT_CODE`(C32)、`FEAT_TYPE`(C64)、`COLOR`(N4)、`ELEVATION`(N10.4)。
- `ShapefileWriteConfig`（Builder 模式）：`crs`、`encoding`、`coordinateDecimalPlaces`。

**新增 EntityProperty 常量（v1.3.0）**
- `MAJOR_AXIS_X` / `MAJOR_AXIS_Y`：ELLIPSE 长轴端点向量分量
- `AXIS_RATIO`：ELLIPSE 短轴与长轴之比
- `DIMENSION_VALUE`：DIMENSION 实测值（code 42）
- `DIMENSION_TYPE`：DIMENSION 类型标志（code 70）
- `DIM_POINT1` / `DIM_POINT2`：DIMENSION 第一/第二定义点
- `DIM_ROTATION`：DIMENSION 旋转角度（code 50，度）
- `CONTROL_POINTS`：SPLINE 控制点列表

### 修复

- **`parseStream()` 单位换算缺失**：Phase 1（HEADER 解析）完成后预计算换算系数，用 `map(scaleEntity)` 逐实体换算，与 `parse()` 行为一致
- **SPLINE 控制点不足时写出无效节点向量**：三次样条要求控制点 ≥ `degree+1 = 4`，不足时改降级为 LWPOLYLINE（原阈值 ≥ 2 过宽）
- **`DimensionHandler` 属性键字面量**：`"dimRotation"` 改为 `EntityProperty.DIM_ROTATION` 常量，消除拼写风险

### 测试（新增 13 个）

**功能测试（10 个）**
- `dxfWriter_solid_shouldProduceSolidEntity`
- `dxfWriter_3dface_shouldProduceFaceEntity`
- `dxfWriter_ellipse_shouldProduceEllipseEntity`
- `dxfWriter_spline_withControlPoints_shouldProduceSplineEntity`
- `dxfWriter_spline_withoutControlPoints_fallsBackToLwPolyline`
- `dimensionHandler_shouldExtractMeasurementValue`
- `leaderHandler_shouldProduceLineStringGeometry`
- `splineHandler_shouldStoreControlPoints`
- `parseStream_shouldReturnSameEntitiesAsParseResult`
- `parseStream_shouldSupportFilter`

**Bug 修复验证（3 个）**
- `parseStream_shouldApplyUnitConversion`
- `dxfWriter_spline_withInsufficientControlPoints_fallsBackToLwPolyline`
- `generateComplexShapefile`（Shapefile 样例集成测试）

---

## [1.2.1] - 2026-05-21

### 修复

- **HATCH 路径计数 bug**：预过滤无效环（顶点数 < 2）后再写 code 91，防止路径总数与实际写出数量不匹配导致 DXF 结构损坏
- **HATCH solid fill flag**：code 70 改为随 `hatchPattern` 动态决定（`SOLID` = 1，其他图案 = 0）
- **HATCH 内环路径类型**：路径类型从 16（Outermost）改为 0（内边界），消除 LibreCAD/FreeCAD 误判风险
- **ARC 零长度弧校验**：`startAngle == endAngle` 时跳过写出，防止静默产生无意义弧
- **INSERT scale 写出一致性**：R2007 路径与 R12 保持一致，缩放因子为默认值 1.0 时不写出冗余 group code
- **`computeExtent` 包围盒**：纳入块内实体坐标，修复含块定义时 `$EXTMIN/$EXTMAX` 偏小的问题
- **`write()` null 参数防御**：公开 API 入口增加 `Objects.requireNonNull`，传入 null 时给出明确错误而非 NPE
- **`EntityProperty.ROTATION` 注释**：更正旋转方向描述（逆时针为正，与 DXF code 50 标准一致）

### 优化

- **`fmt()` 格式串缓存**：预计算 `fmtPattern` 字段，大文件写出时减少字符串分配

### 文档

- `CADEntity.Types` 表格：补全 ARC / CIRCLE / HATCH / INSERT 的写出列说明
- `CADBlock`：增加非线程安全说明
- `DXFVersion`：增加写出路径限制说明（R2000/R2004 走 R2007 结构）
- `CADEntity`：新增 `getGeometry()` JavaBean 风格别名方法

### 构建

- `pom.xml`：移除含占位符的 `distributionManagement` 块
- `pom.xml`：`doclint` 从 `none` 改为 `html,reference`，保留有效 Javadoc 检查

### 测试（新增 5 个）

- `dxfWriter_hatch_withHole_roundTrip`：验证 HATCH 含洞写出后洞数量正确
- `dxfWriter_arc_angleRoundTrip`：验证 ARC 起终角度写出正确
- `dxfWriter_mtext_roundTrip`：MTEXT 写出后解析回读内容验证
- `dxfWriter_insertWithBlock_r2007_roundTrip`：INSERT 展开后实体存在验证
- `dxfWriter_write_nullArguments_shouldThrow`：null 参数防御验证

---

## [1.2.0] - 2026-05-20

### 新增

**写出实体类型扩充**
- `ARC` 写出：type=`ARC` + geometry=`Point`（圆心）+ properties `{radius, startAngle, endAngle}` → DXF ARC。R2007 子类标记 `AcDbCircle` + `AcDbArc`；geometry 为 LineString 时回退为 LWPOLYLINE
- `CIRCLE` 写出：type=`CIRCLE` + geometry=`Point`（圆心）+ property `radius` → DXF CIRCLE；geometry 为 LinearRing 时回退为 LWPOLYLINE
- `HATCH` 写出：type=`HATCH` + geometry=`Polygon`/`MultiPolygon` → DXF HATCH（SOLID 填充），支持外环 + 洞，种子点取多边形内点。R12 降级为外环 LWPOLYLINE（无填充）
- `INSERT` 写出：type=`INSERT` + geometry=`Point`（插入点）+ properties `{blockName, scaleX, scaleY, scaleZ, rotation}` → DXF INSERT

**块定义写出 API**
- `DXFWriter.write(List<CADBlock> blocks, List<CADEntity> entities, Path path)` 新重载：块定义写入 BLOCKS 段，BLOCK_RECORD 表自动追加对应记录
- R12：块定义写在 ENTITIES 段内（`BLOCK...ENDBLK` 包围）
- R2007：块定义写在 BLOCKS 段，块内实体 owner handle 指向对应 BLOCK_RECORD
- 句柄分配：用户块 BLOCK_RECORD 从 `0x200` 起，BLOCK/ENDBLK 从 `0x210` 起，块内实体从 `0x300` 起

**图层属性扩展**
- LAYER 表新增线型（code 6）和线宽（code 370）写出
- 从实体 `lineType` property 读取图层线型，从 `lineWeight` property 读取线宽码（-3=ByLayer 默认）
- LTYPE 表自动收录图层引用的非标准线型（写出引用，不补充图案定义）

**XDATA 写出**
- 实体含 `xdata` property（`Map<String, List<XDataEntry>>`）时，在实体所有 group code 之后写出 XDATA
- 支持 CASS / EPS / MapGIS 等地物编码的 DXF 文件往返完整性

**新增 EntityProperty 常量（v1.2.0）**
- `RADIUS` / `START_ANGLE` / `END_ANGLE`：ARC / CIRCLE 几何参数
- `LINETYPE` / `LINEWEIGHT`：图层线型与线宽
- `HATCH_PATTERN`：HATCH 填充图案名
- `SCALE_X` / `SCALE_Y` / `SCALE_Z`：INSERT 缩放因子

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
