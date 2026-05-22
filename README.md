# NanoDXF

**轻量级 DXF 文件解析库，面向 GIS / 测绘场景。**

纯 Java 实现，无第三方 CAD 依赖，支持 GBK / UTF-8 自动编码检测，输出 JTS 几何对象与 GeoJSON。

---

## 特性

- **全主流实体解析**：LINE / ARC / CIRCLE / ELLIPSE / POINT / TEXT / MTEXT / LWPOLYLINE / POLYLINE / SPLINE / HATCH / INSERT / 3DFACE / SOLID / DIMENSION / LEADER / MULTILEADER
- **INSERT 块递归展开**：仿射变换（缩放 → 旋转 → 平移）、路径集合循环引用检测
- **国内测绘软件适配**：CASS / EPS / MapMatrix / MapGIS / SuperMap XDATA 地物编码提取，内置约 80 条 GB/T 20257 映射
- **BYLAYER 颜色继承**：ACI 256 色表 → 图层颜色 → 实体颜色完整链路
- **编码自适应**：UTF-8 BOM → juniversalchardet 检测 → 版本号推断 → GBK 兜底
- **容错解析**：截断文件、损坏实体、未知实体类型均不中断，错误分级收集（FATAL / WARN / INFO）
- **多格式输出**：GeoJSON（坐标精度可配）、Shapefile（SHP/SHX/DBF/PRJ，纯 Java 无额外依赖；自动 2D/3D 输出，含 PointZ/PolylineZ/PolygonZ）
- **DXF 写出**：15 种实体类型 + 块定义，R12 / R2007 双路径，浩辰 CAD / AutoCAD 验证通过
- **流式解析 API**：`parseStream(Path)` 两阶段惰性流，大文件低内存占用

---

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>io.github.z-double</groupId>
    <artifactId>nano-dxf</artifactId>
    <version>1.4.0</version>
</dependency>
```

### 最简用法

```java
import com.nanodxf.CADParser;
import com.nanodxf.ParseResult;
import com.nanodxf.entity.CADEntity;

// 解析文件（自动检测 GBK / UTF-8 编码）
ParseResult result = new CADParser().parse(Paths.get("drawing.dxf"));

// 遍历实体
for (CADEntity entity : result.getEntities()) {
    System.out.println(entity.getType() + " 图层=" + entity.getLayer());
    System.out.println("  几何=" + entity.geometry());
}

// 查看错误与统计
result.getErrors().forEach(System.err::println);
System.out.println(result.getStats());
```

### 带配置解析

```java
ParseConfig config = ParseConfig.builder()
    .crs("EPSG:4545")          // CGCS2000 / 3度带 117°E
    .arcTolerance(0.001)       // 弧线离散精度（米）
    .coordinateDecimalPlaces(4)// 坐标小数位数
    .build();

ParseResult result = new CADParser(config).parse(Paths.get("drawing.dxf"));
```

### 输出 GeoJSON

```java
GeoJsonSerializer ser = new GeoJsonSerializer(result.getMetadata());
String geojson = ser.serialize(result.getEntities(), result.getMetadata());
Files.writeString(Paths.get("output.geojson"), geojson);
```

---

## API 概览

### CADParser

| 方法 | 说明 |
|---|---|
| `parse(Path path)` | 解析 DXF 文件（自动编码检测） |
| `parse(Reader reader)` | 从 Reader 解析（单元测试常用） |

### ParseConfig（Builder 模式）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `crs` | null | 坐标参考系标识（如 `EPSG:4545`） |
| `arcTolerance` | 0.01 | 弧线/样条离散弦高误差（坐标单位） |
| `coordinateDecimalPlaces` | 4 | GeoJSON 坐标小数位数（0~15） |

### ParseResult

| 方法 | 返回值 | 说明 |
|---|---|---|
| `getEntities()` | `List<CADEntity>` | 模型空间实体（已展开 INSERT）|
| `getErrors()` | `List<ParseError>` | 分级错误列表 |
| `getStats()` | `ParseStats` | 耗时、实体数、错误数 |
| `getMetadata()` | `DrawingMetadata` | 版本、单位、CRS 等图纸元数据 |

### CADEntity

```java
entity.getType()         // "LINE" / "ARC" / "INSERT" 等
entity.getLayer()        // 图层名
entity.getHandle()       // DXF handle（唯一标识）
entity.geometry()        // JTS Geometry（Point/LineString/Polygon 等）
entity.getProperties()   // Map<String, Object>，含颜色、文字、地物编码等附加属性
```

常见属性键：

| 键 | 类型 | 说明 |
|---|---|---|
| `colorRgb` | `int[3]` | RGB 颜色（True Color > ACI > BYLAYER 继承） |
| `colorAci` | `Integer` | ACI 颜色号（仅显式指定时存在） |
| `text` | `String` | TEXT / MTEXT 清洗后的文字内容 |
| `elevation` | `Double` | LWPOLYLINE / POINT 高程（Z 值） |
| `featureCode` | `String` | 地物编码（来自 CASS / EPS 等 XDATA） |
| `featureType` | `String` | 地物名称（GB/T 20257 映射，如"普通房屋"） |
| `featureCategory` | `String` | 地物分类（如"建筑"） |
| `blockName` | `String` | INSERT 引用的块名 |
| `xdata` | `Map` | 原始 XDATA（所有应用名） |

---

## DXF 输出

### 快速写出

```java
import com.nanodxf.output.DXFWriter;
import com.nanodxf.output.DXFWriteConfig;
import com.nanodxf.model.DXFVersion;
import com.nanodxf.entity.CADEntity;

List<CADEntity> entities = List.of(
    CADEntity.builder(CADEntity.Types.LINE)
        .layer("道路")
        .geometry(GF.createLineString(new Coordinate[]{
            new Coordinate(0, 0), new Coordinate(100, 0)}))
        .property(EntityProperty.COLOR_ACI, AciColor.WHITE)
        .build()
);

// R2007 + GBK（推荐，兼容浩辰 CAD / AutoCAD）
DXFWriteConfig config = DXFWriteConfig.builder()
    .version(DXFVersion.R2007)
    .encoding("GBK")              // 含中文图层名时必须指定
    .coordinateDecimalPlaces(4)
    .build();

new DXFWriter(config).write(entities, Paths.get("output.dxf"));
```

### 流式解析（大文件内存友好）

```java
// parseStream 两阶段：预解析 BLOCKS → 惰性流出 ENTITIES
// 必须在 try-with-resources 中使用（流持有文件句柄）
try (Stream<CADEntity> stream = new CADParser().parseStream(Paths.get("large.dxf"))) {
    stream.filter(e -> CADEntity.Types.LWPOLYLINE.equals(e.getType()))
          .limit(10_000)
          .forEach(myProcessor::accept);
}
```

### Shapefile 输出

```java
import com.nanodxf.output.ShapefileWriter;
import com.nanodxf.output.ShapefileWriteConfig;

ShapefileWriteConfig cfg = ShapefileWriteConfig.builder()
    .crs("EPSG:4545")           // 写入 .prj WKT（v1.4.0：内置 EPSG:4326/4490/4534–4554/32644–32654）
    .encoding("GBK")            // DBF 属性文件编码
    .coordinateDecimalPlaces(4) // ELEVATION 字段小数位（影响 DBF 字段宽度）
    // .dimension(ShapefileWriteConfig.ShapeDimension.AUTO)  // 默认：有 Z 自动写 PointZ/PolylineZ/PolygonZ
    .build();

// 输出：output.shp + output.shx + output.dbf + output.prj
new ShapefileWriter(cfg).write(result.getEntities(), Paths.get("output.shp"));
```

### 写出 ARC / CIRCLE / HATCH / INSERT / ELLIPSE / SOLID / 3DFACE / SPLINE

```java
// ARC：圆心 Point + radius/startAngle/endAngle 属性
entities.add(CADEntity.builder(CADEntity.Types.ARC)
    .layer("弧形")
    .geometry(GF.createPoint(new Coordinate(50, 50)))
    .property(EntityProperty.RADIUS,      25.0)
    .property(EntityProperty.START_ANGLE, 0.0)
    .property(EntityProperty.END_ANGLE,   270.0)
    .build());

// CIRCLE：圆心 Point + radius 属性
entities.add(CADEntity.builder(CADEntity.Types.CIRCLE)
    .layer("圆形")
    .geometry(GF.createPoint(new Coordinate(150, 50)))
    .property(EntityProperty.RADIUS, 30.0)
    .build());

// HATCH SOLID：Polygon 几何（支持洞）
entities.add(CADEntity.builder(CADEntity.Types.HATCH)
    .layer("填充")
    .geometry(polygon)
    .property(EntityProperty.COLOR_ACI, AciColor.GREEN)
    .build());

// 块定义 + INSERT
CADBlock symbol = new CADBlock("ARROW");
symbol.setInsertionPoint(0, 0, 0);
symbol.addEntity(CADEntity.builder(CADEntity.Types.LINE)
    .layer("0").geometry(lineGeom).build());

entities.add(CADEntity.builder(CADEntity.Types.INSERT)
    .layer("符号")
    .geometry(GF.createPoint(new Coordinate(100, 100)))
    .property(EntityProperty.BLOCK_NAME, "ARROW")
    .property(EntityProperty.SCALE_X, 2.0)
    .build());

// 写出时传入块定义列表
new DXFWriter(config).write(List.of(symbol), entities, Paths.get("output.dxf"));

// ELLIPSE：圆心 Point + 长轴向量 + 轴比（v1.3.0）
entities.add(CADEntity.builder(CADEntity.Types.ELLIPSE)
    .layer("椭圆")
    .geometry(GF.createPoint(new Coordinate(200, 100)))
    .property(EntityProperty.MAJOR_AXIS_X, 50.0)
    .property(EntityProperty.MAJOR_AXIS_Y,  0.0)
    .property(EntityProperty.AXIS_RATIO,    0.5)   // 短轴/长轴
    .property(EntityProperty.START_ANGLE,   0.0)   // 弧度
    .property(EntityProperty.END_ANGLE,     2 * Math.PI)
    .build());

// SOLID：4 顶点 Polygon（v1.3.0）
entities.add(CADEntity.builder(CADEntity.Types.SOLID)
    .layer("实体")
    .geometry(solidPolygon)     // 外环前 4 顶点将写为 SOLID
    .build());

// SPLINE：带控制点的样条曲线（v1.3.0）
List<double[]> ctrlPts = List.of(
    new double[]{0, 0, 0}, new double[]{10, 20, 0},
    new double[]{30, 15, 0}, new double[]{40, 0, 0});
entities.add(CADEntity.builder(CADEntity.Types.SPLINE)
    .layer("样条")
    .geometry(GF.createLineString(/* discretized coords */))
    .property(EntityProperty.CONTROL_POINTS, ctrlPts)
    .build());
```

### DXFWriteConfig 参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `version` | `R2007` | 输出版本（`R12` 或 `R2007`） |
| `encoding` | 自动 | R2007+ 默认 UTF-8，其他默认 GBK；含中文内容建议显式指定 GBK |
| `coordinateDecimalPlaces` | `4` | 坐标小数位数（0~15） |

### 支持输出的实体类型

| 类型（`CADEntity.Types.*`） | 输入几何 | 输出实体 |
|---|---|---|
| `LINE` | `LineString`（2 点） | LINE |
| `LWPOLYLINE` | `LineString`（多点）/ `LinearRing` | LWPOLYLINE |
| `LWPOLYLINE` | `Polygon` | 外环 + 各洞，每个 LWPOLYLINE |
| `POINT` | `Point` | POINT |
| `TEXT` | `Point` | TEXT（需 `text` 属性） |
| `MTEXT` | `Point` | MTEXT（需 `text` 属性） |
| `ARC` | `Point`（圆心） | ARC（需 `radius`/`startAngle`/`endAngle` 属性）|
| `CIRCLE` | `Point`（圆心） | CIRCLE（需 `radius` 属性）|
| `HATCH` | `Polygon`/`MultiPolygon` | HATCH（SOLID 填充，支持洞）|
| `INSERT` | `Point`（插入点） | INSERT（需 `blockName` 属性）|
| `ELLIPSE` | `Point`（圆心） | ELLIPSE（R2007）/ 72 段 POLYLINE 折线近似（R12，v1.3.1）|
| `SOLID` | `Polygon` / `LinearRing`（4 顶点） | SOLID（v1.3.0）|
| `FACE3D` | `LinearRing` / `Polygon`（3~4 顶点） | 3DFACE（v1.3.0）|
| `SPLINE` | `LineString` + `controlPoints` 属性 | SPLINE（R2007）/ POLYLINE 折线（R12，v1.3.1）|
| 任意 | `GeometryCollection` | 递归展开子几何 |

### 支持的实体属性（写出）

| 属性键 | 类型 | 作用 |
|---|---|---|
| `colorAci` | `Integer` | ACI 颜色号（同时写入图层颜色） |
| `colorRgb` | `int[3]` | True Color（仅 R2004+，code 420） |
| `text` | `String` | TEXT / MTEXT 文字内容 |
| `height` | `Double` | 文字高度（默认 2.5） |
| `rotation` | `Double` | 文字旋转角度（度，默认 0） |
| `style` | `String` | TEXT 文字样式（默认 "Standard"） |
| `radius` | `Double` | ARC / CIRCLE 半径 |
| `startAngle` | `Double` | ARC 起始角（度）|
| `endAngle` | `Double` | ARC 终止角（度）|
| `hatchPattern` | `String` | HATCH 图案名（默认 `"SOLID"`）|
| `blockName` | `String` | INSERT 引用的块名 |
| `scaleX`/`scaleY`/`scaleZ` | `Double` | INSERT 缩放因子（默认 1.0）|
| `lineType` | `String` | 图层线型名（默认 `"Continuous"`）|
| `lineWeight` | `Integer` | 图层线宽码（默认 -3=ByLayer）|
| `xdata` | `Map` | XDATA 写出（地物编码保留）|
| `majorAxisX`/`majorAxisY` | `Double` | ELLIPSE 长轴端点向量（v1.3.0）|
| `axisRatio` | `Double` | ELLIPSE 短轴/长轴比（v1.3.0）|
| `controlPoints` | `List<double[]>` | SPLINE 控制点（v1.3.0）|

### 格式版本对比

| | R12 | R2007 |
|---|---|---|
| 子类标记（`100 AcDbXxx`） | ✗ | ✅ |
| owner handle（`330`） | ✗ | ✅ |
| BLOCKS / OBJECTS 段 | ✗ | ✅ |
| True Color（code 420） | ✗ | ✅（R2004+） |
| 浩辰 CAD 兼容 | ✅ | ✅ |
| AutoCAD 2020+ 兼容 | ✅ | ✅ |
| QGIS / LibreCAD 兼容 | ✅ | ✅ |

### 实体类型常量

`CADEntity.Types` 提供所有 DXF 实体类型的字符串常量，避免硬编码魔法字符串：

```java
// 解析侧
if (CADEntity.Types.LINE.equals(entity.getType())) { ... }
result.getEntities().stream()
    .filter(e -> CADEntity.Types.LWPOLYLINE.equals(e.getType()))
    .forEach(...);

// 写出侧
CADEntity.builder(CADEntity.Types.LWPOLYLINE).layer("建筑").geometry(ring).build();
```

常量列表：`LINE` / `ARC` / `CIRCLE` / `ELLIPSE` / `POINT` / `LWPOLYLINE` / `POLYLINE` / `VERTEX` / `SPLINE` / `TEXT` / `MTEXT` / `ATTRIB` / `HATCH` / `SOLID` / `FACE3D`（值 `"3DFACE"`）/ `INSERT` / `BLOCK` / `ENDBLK` / `SEQEND` / `DIMENSION` / `LEADER` / `VIEWPORT`

### 其他常量类

| 类 | 用途 | 典型常量 |
|---|---|---|
| `AciColor` | ACI 颜色码 | `RED=1` / `WHITE=7` / `BYLAYER=256` / `ORANGE=30` |
| `EntityProperty` | 实体属性键字符串 | `COLOR_ACI` / `TEXT` / `ELEVATION` / `FEATURE_CODE` |
| `InsUnit` | `$INSUNITS` 单位码 | `METERS=6` / `MILLIMETERS=4` / `FEET=2` |
| `output.LineTypeName` | 标准线型名 | `CONTINUOUS` / `DASHED` / `CENTER` / `HIDDEN` |

```java
// EntityProperty 消除属性键魔法字符串
String text = (String) entity.getProperties().get(EntityProperty.TEXT);
Double elev = (Double) entity.getProperties().get(EntityProperty.ELEVATION);

// InsUnit 单位换算
double meters = InsUnit.toMeters(coord, result.getMetadata().getInsUnits());

// 写出时组合使用
CADEntity.builder(CADEntity.Types.TEXT)
    .layer("注记")
    .property(EntityProperty.TEXT,      "高程点 H=25.3")
    .property(EntityProperty.HEIGHT,    2.5)
    .property(EntityProperty.COLOR_ACI, AciColor.WHITE)
    .build();
```

---

## 支持的 DXF 版本

### 解析（读入）

| 版本字符串 | AutoCAD 版本 | 支持状态 |
|---|---|---|
| AC1009 | R12 | ✅ |
| AC1015 | R2000 | ✅ |
| AC1018 | R2004 | ✅ |
| AC1021 | R2007 | ✅ |
| AC1024+ | R2010~R2018 | ✅ |

### 写出（生成）

| 版本 | 说明 | 推荐场景 |
|---|---|---|
| R12（AC1009） | 最简兼容格式，无子类标记 | 跨软件通用，文件最小 |
| R2007（AC1021） | 完整格式，通过浩辰CAD验证 | 国内 CAD 软件（浩辰、中望等） |

---

## 依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| `org.locationtech.jts:jts-core` | 1.19.0 | JTS 几何库 |
| `com.googlecode.juniversalchardet:juniversalchardet` | 1.0.3 | 编码自动检测 |

运行时无其他依赖。需要 Java 17+。

---

## 构建

```bash
mvn clean package
mvn test
```

---

## 许可证

Apache License 2.0
