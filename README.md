# NanoDXF

**轻量级 DXF 文件解析库，面向 GIS / 测绘场景。**

纯 Java 实现，无第三方 CAD 依赖，支持 GBK / UTF-8 自动编码检测，输出 JTS 几何对象与 GeoJSON。

---

## 特性

- **全主流实体类型**：LINE / ARC / CIRCLE / ELLIPSE / POINT / TEXT / MTEXT / LWPOLYLINE / POLYLINE / SPLINE / HATCH / INSERT / 3DFACE / SOLID / DIMENSION
- **INSERT 块递归展开**：仿射变换（缩放 → 旋转 → 平移）、路径集合循环引用检测
- **国内测绘软件适配**：CASS / EPS / MapMatrix / MapGIS / SuperMap XDATA 地物编码提取，内置约 80 条 GB/T 20257 映射
- **BYLAYER 颜色继承**：ACI 256 色表 → 图层颜色 → 实体颜色完整链路
- **编码自适应**：UTF-8 BOM → juniversalchardet 检测 → 版本号推断 → GBK 兜底
- **容错解析**：截断文件、损坏实体、未知实体类型均不中断，错误分级收集（FATAL / WARN / INFO）
- **GeoJSON 输出**：坐标精度可配、CRS 标注、大坐标浮点噪声抑制

---

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>com.nanodxf</groupId>
    <artifactId>nano-dxf</artifactId>
    <version>1.0.0</version>
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

## 支持的 DXF 版本

| 版本字符串 | AutoCAD 版本 | 支持状态 |
|---|---|---|
| AC1009 | R12 | ✅ |
| AC1015 | R2000 | ✅ |
| AC1018 | R2004 | ✅ |
| AC1021 | R2007 | ✅ |
| AC1024+ | R2010~R2018 | ✅ |

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
