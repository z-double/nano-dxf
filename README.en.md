# NanoDXF

**Lightweight DXF file parser, writer, and GIS analysis toolkit for surveying use cases.**

Pure Java, no third-party CAD dependencies. Auto-detects GBK / UTF-8 encoding. Outputs JTS geometry objects, GeoJSON, Shapefile, GeoPackage, SVG, CSV, ESRI ASCII Grid, and more.

---

## Features

- **All major entity types**: LINE / ARC / CIRCLE / ELLIPSE / POINT / TEXT / MTEXT / LWPOLYLINE / POLYLINE / SPLINE / HATCH / INSERT / 3DFACE / SOLID / DIMENSION / LEADER / MULTILEADER / ATTDEF / MLINE / WIPEOUT / IMAGE / TOLERANCE
- **OCS/WCS coordinate transform**: Arbitrary Axis Algorithm, correctly restores 3D/rotated entity coordinates (v1.6.0)
- **Recursive INSERT expansion**: affine transform (scale → rotate → translate), path-set cycle detection
- **Chinese surveying software support**: CASS / EPS / MapMatrix / MapGIS / SuperMap XDATA feature code extraction, ~80 built-in GB/T 20257 mappings
- **BYLAYER color inheritance**: ACI 256-color table → layer color → entity color full chain
- **Auto encoding detection**: UTF-8 BOM → juniversalchardet → version inference → GBK fallback
- **Fault-tolerant parsing**: truncated files, corrupted entities, unknown entity types never abort parsing; errors collected with severity levels (FATAL / WARN / INFO)
- **Parse filtering**: `includeLayers` / `excludeLayers` / `includeTypes`, skip irrelevant layers on large files (v1.6.0)
- **Multiple output formats**: GeoJSON, Shapefile (2D/3D), GeoPackage, SVG (v1.6.0, zero-dependency vector preview), point-cloud CSV (v1.7.0), ESRI ASCII Grid DEM (v1.7.0)
- **DXF write**: 16 entity types + block definitions, R12 / R2007 dual-path, verified with GstarCAD / AutoCAD
- **Streaming parse API**: `parseStream(Path)` two-phase lazy stream, low memory for large files
- **Spatial index**: `EntityIndex` (JTS STRtree), `query(Envelope)` / `byLayer` / `byType` (v1.5.0)
- **Surveying API**: `ContourHelper` (contour grouping/validation), `ElevationAnnotation` (elevation annotation matching) (v1.6.0)
- **Topology check API**: `TopologyChecker` with 5 rules (duplicate / self-intersection / zero-length / dangling endpoint / contour crossing) (v1.6.0)
- **Topology repair API**: `TopologyFixer` — auto dedup, zero-length removal, endpoint snapping (v1.7.0)
- **Polygon reconstruction**: `PolygonBuilder` — LinearRing → Polygon, JTS Polygonizer hole detection (v1.7.0)
- **Geometry simplification**: `GeomSimplifier` — vertex reduction (topology-preserving / Douglas-Peucker) (v1.7.0)
- **Layer statistics**: `LayerStats` — per-layer count / total length / total area, with unit conversion (v1.7.0)
- **DEM construction + ASCII Grid output**: `DemBuilder` Delaunay TIN interpolation, `AscGridWriter` ESRI .asc format (v1.7.0)
- **Slope / aspect analysis**: `SlopeAnalyzer` Horn's method 3×3 finite-difference neighborhood (v1.7.0)
- **Sheet edge matching**: `SheetEdgeMatcher` — endpoint gap check along vertical/horizontal/arbitrary join edges (v1.7.0)

---

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.github.z-double</groupId>
    <artifactId>nano-dxf</artifactId>
    <version>1.7.0</version>
</dependency>
```

### Minimal Usage

```java
import com.nanodxf.CADParser;
import com.nanodxf.ParseResult;
import com.nanodxf.entity.CADEntity;

// Parse file (auto-detects GBK / UTF-8 encoding)
ParseResult result = new CADParser().parse(Paths.get("drawing.dxf"));

// Iterate entities
for (CADEntity entity : result.getEntities()) {
    System.out.println(entity.getType() + " layer=" + entity.getLayer());
    System.out.println("  geometry=" + entity.geometry());
}

result.getErrors().forEach(System.err::println);
System.out.println(result.getStats());
```

### With Configuration

```java
ParseConfig config = ParseConfig.builder()
    .crs("EPSG:4545")           // CGCS2000 3-Degree GK CM 117E
    .arcTolerance(0.001)        // arc discretization chord-height tolerance
    .includeLayers("等高线", "建筑")  // layer whitelist (v1.6.0)
    .build();

ParseResult result = new CADParser(config).parse(Paths.get("drawing.dxf"));
```

### Streaming Parse (Low Memory)

```java
try (Stream<CADEntity> stream = new CADParser().parseStream(Paths.get("large.dxf"))) {
    stream.filter(e -> CADEntity.Types.LWPOLYLINE.equals(e.getType()))
          .limit(10_000)
          .forEach(myProcessor::accept);
}
```

---

## Surveying & Analysis API (v1.6.0 / v1.7.0)

### Contour Grouping and Validation

```java
import com.nanodxf.survey.ContourHelper;
import com.nanodxf.survey.ContourSet;

ContourSet cs = ContourHelper.extract(result.getEntities(), "等高线", "计曲线");
System.out.println(cs.summary());

// Validate: all elevations must be multiples of contour interval
List<Double> bad = cs.validate(5.0);  // 5m contour interval
if (!bad.isEmpty()) System.err.println("Anomalous elevations: " + bad);
```

### Topology Check

```java
import com.nanodxf.topology.*;

TopologyReport report = TopologyChecker.check(result.getEntities(),
    TopologyCheckConfig.builder()
        .rules(TopologyRule.DUPLICATE_ENTITY, TopologyRule.DANGLING_ENDPOINT,
               TopologyRule.CONTOUR_CROSSING)
        .contourLayers("等高线", "计曲线")
        .lineConnectLayers("道路", "水系")
        .snapTolerance(0.001)
        .build());

System.out.println(report.summary());
```

### Topology Repair

```java
import com.nanodxf.topology.*;

TopologyFixResult fixed = TopologyFixer.fix(result.getEntities(),
    TopologyFixConfig.builder()
        .rules(TopologyRule.DUPLICATE_ENTITY, TopologyRule.ZERO_LENGTH,
               TopologyRule.DANGLING_ENDPOINT)
        .snapTolerance(0.01)
        .build());

System.out.println(fixed.summary());
List<CADEntity> cleanEntities = fixed.getEntities();
```

### Polygon Reconstruction (LinearRing → Polygon)

```java
import com.nanodxf.geometry.PolygonBuilder;

List<CADEntity> buildings = result.getEntities().stream()
    .filter(e -> "建筑".equals(e.getLayer())).toList();

List<CADEntity> polygons = PolygonBuilder.build(buildings);  // hole detection
double totalArea = polygons.stream()
    .mapToDouble(e -> e.geometry().getArea()).sum();
```

### Geometry Simplification

```java
import com.nanodxf.geometry.GeomSimplifier;
import com.nanodxf.geometry.SimplifyMode;

// Contour simplification before SVG/Shapefile export
List<CADEntity> slim = GeomSimplifier.simplify(result.getEntities(), 0.1);

// Or simplify ContourSet directly
ContourSet simplified = GeomSimplifier.simplifyContours(cs, 0.5);
```

### Layer Statistics

```java
import com.nanodxf.stat.LayerStats;

// Raw coordinate units
Map<String, LayerStatRow> stats = LayerStats.compute(result.getEntities());
System.out.println(LayerStats.summary(stats));

// With unit conversion (→ meters / m²)
Map<String, LayerStatRow> stats = LayerStats.compute(
    result.getEntities(), result.getMetadata());
```

### Point Cloud CSV Export

```java
import com.nanodxf.output.*;

CsvWriteConfig cfg = CsvWriteConfig.builder()
    .fields(CsvField.X, CsvField.Y, CsvField.Z,
            CsvField.LAYER, CsvField.FEATURE_CODE)
    .delimiter(',')
    .build();
CsvWriter.write(result.getEntities(), Path.of("output.csv"), cfg);
```

### DEM Construction and Slope Analysis

```java
import com.nanodxf.dem.*;
import com.nanodxf.survey.*;

ContourSet cs = ContourHelper.extract(result.getEntities(), "等高线");

// Build DEM (1m cell size)
DemGrid dem = DemBuilder.build(cs, 1.0);

// Write ESRI ASCII Grid
AscGridWriter.write(dem, Path.of("dem.asc"));

// Slope / aspect (Horn's method)
SlopeGrid sg = SlopeAnalyzer.analyze(dem);
System.out.printf("Mean slope: %.2f°%n", sg.meanSlope());
```

### Sheet Edge Matching

```java
import com.nanodxf.sheet.*;

// Vertical join edge at x = 50000, band ±2m, gap tolerance 0.5m
SheetEdgeReport report = SheetEdgeMatcher.matchVertical(
    entitiesA, entitiesB, 50000.0, 2.0, 0.5);

System.out.println(report.summary());
if (!report.isClean()) {
    for (EdgeGap gap : report.getGaps()) {
        System.out.printf("Gap: A=%s B=%s dist=%.4f%n",
            gap.getEntityA().getHandle(),
            gap.getEntityB().getHandle(),
            gap.getDistance());
    }
}
```

---

## Output Formats

### GeoJSON

```java
GeoJsonSerializer ser = new GeoJsonSerializer(result.getMetadata());
String geojson = ser.serialize(result.getEntities(), result.getMetadata());
Files.writeString(Paths.get("output.geojson"), geojson);
```

### Shapefile

```java
ShapefileWriteConfig cfg = ShapefileWriteConfig.builder()
    .crs("EPSG:4545")
    .encoding("GBK")
    .build();
new ShapefileWriter(cfg).write(result.getEntities(), Paths.get("output.shp"));
```

### SVG (v1.6.0)

```java
SvgWriteConfig cfg = SvgWriteConfig.builder()
    .width(1200).background("#ffffff").build();
String svg = new SvgWriter(cfg).serialize(result.getEntities());
Files.writeString(Paths.get("output.svg"), svg);
```

### GeoPackage

```java
GeoPackageWriteConfig cfg = GeoPackageWriteConfig.builder()
    .layerName("drawing").crs("EPSG:4545").build();
new GeoPackageWriter(cfg).write(result.getEntities(), Paths.get("output.gpkg"));
```

---

## API Reference

### ParseResult

| Method | Return | Description |
|---|---|---|
| `getEntities()` | `List<CADEntity>` | Model-space entities (INSERT blocks expanded) |
| `getErrors()` | `List<ParseError>` | Leveled error list |
| `getStats()` | `ParseStats` | Parse time, entity count, error counts |
| `getMetadata()` | `DrawingMetadata` | Version, units, CRS metadata |
| `index()` | `EntityIndex` | Lazy-built spatial index (STRtree) |

### CADEntity

```java
entity.getType()         // "LINE" / "ARC" / "LWPOLYLINE" etc.
entity.getLayer()        // layer name
entity.getHandle()       // DXF unique ID
entity.geometry()        // JTS Geometry
entity.getProperties()   // Map<String, Object>
```

Common property keys (`EntityProperty.*`):

| Key | Type | Description |
|---|---|---|
| `colorRgb` | `int[3]` | RGB color (True Color > ACI > BYLAYER chain) |
| `colorAci` | `Integer` | ACI color number |
| `text` | `String` | Cleaned TEXT / MTEXT content |
| `elevation` | `Double` | Elevation (Z) from LWPOLYLINE / POINT code 38 |
| `featureCode` | `String` | CASS / EPS XDATA feature code |
| `featureType` | `String` | GB/T 20257 feature name |

---

## Supported DXF Versions

| Version string | AutoCAD | Status |
|---|---|---|
| AC1009 | R12 | ✅ |
| AC1015 | R2000 | ✅ |
| AC1018 | R2004 | ✅ |
| AC1021 | R2007 | ✅ |
| AC1024+ | R2010~R2018 | ✅ |

---

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `org.locationtech.jts:jts-core` | 1.19.0 | JTS geometry (includes `DelaunayTriangulationBuilder`) |
| `com.googlecode.juniversalchardet:juniversalchardet` | 1.0.3 | Encoding auto-detection |

No other runtime dependencies. Requires **Java 17+**.

---

## Build

```bash
mvn clean package
mvn test
```

---

## License

Apache License 2.0
