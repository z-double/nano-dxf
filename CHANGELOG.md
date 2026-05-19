# Changelog

所有版本的变更记录遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

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
