# NanoDXF

**Lightweight DXF file parser and writer for GIS / surveying use cases.**

Pure Java, no third-party CAD dependencies. Auto-detects GBK / UTF-8 encoding. Outputs JTS geometry objects, GeoJSON, and Shapefile.

---

## Features

- **All major entity types**: LINE / ARC / CIRCLE / ELLIPSE / POINT / TEXT / MTEXT / LWPOLYLINE / POLYLINE / SPLINE / HATCH / INSERT / 3DFACE / SOLID / DIMENSION / LEADER
- **Recursive INSERT expansion**: affine transform (scale → rotate → translate), path-set cycle detection
- **Chinese surveying software support**: CASS / EPS / MapMatrix / MapGIS / SuperMap XDATA feature code extraction, ~80 built-in GB/T 20257 mappings
- **BYLAYER color inheritance**: ACI 256-color table → layer color → entity color full chain
- **Auto encoding detection**: UTF-8 BOM → juniversalchardet → version inference → GBK fallback
- **Fault-tolerant parsing**: truncated files, corrupted entities, unknown entity types never abort parsing; errors collected with severity levels (FATAL / WARN / INFO)
- **Multiple output formats**: GeoJSON (configurable precision), Shapefile (SHP/SHX/DBF/PRJ, pure Java, no extra dependencies)
- **DXF write**: 15 entity types + block definitions, R12 / R2007 dual-path, verified with GstarCAD / AutoCAD
- **Streaming parse API**: `parseStream(Path)` two-phase lazy stream, low memory for large files

---

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.github.z-double</groupId>
    <artifactId>nano-dxf</artifactId>
    <version>1.3.1</version>
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

// Review errors and stats
result.getErrors().forEach(System.err::println);
System.out.println(result.getStats());
```

### With Configuration

```java
ParseConfig config = ParseConfig.builder()
    .crs("EPSG:4545")           // CGCS2000 3-Degree GK CM 117E
    .arcTolerance(0.001)        // arc discretization chord-height tolerance (meters)
    .coordinateDecimalPlaces(4) // GeoJSON coordinate decimal places
    .build();

ParseResult result = new CADParser(config).parse(Paths.get("drawing.dxf"));
```

### Streaming Parse (Low Memory for Large Files)

```java
// Two-phase: eagerly load BLOCKS/TABLES → lazily stream ENTITIES
// Must be used in try-with-resources (stream holds a file handle)
try (Stream<CADEntity> stream = new CADParser().parseStream(Paths.get("large.dxf"))) {
    stream.filter(e -> CADEntity.Types.LWPOLYLINE.equals(e.getType()))
          .limit(10_000)
          .forEach(myProcessor::accept);
}
```

### Export GeoJSON

```java
GeoJsonSerializer ser = new GeoJsonSerializer(result.getMetadata());
String geojson = ser.serialize(result.getEntities(), result.getMetadata());
Files.writeString(Paths.get("output.geojson"), geojson);
```

### Export Shapefile

```java
import com.nanodxf.output.ShapefileWriter;
import com.nanodxf.output.ShapefileWriteConfig;

ShapefileWriteConfig cfg = ShapefileWriteConfig.builder()
    .crs("EPSG:4490")           // PRJ: built-in WKT for EPSG:4326/4490; comment fallback for others
    .encoding("GBK")            // DBF attribute file encoding
    .coordinateDecimalPlaces(4) // ELEVATION field decimal places (affects DBF field width)
    .build();

// Output: output.shp + output.shx + output.dbf + output.prj
new ShapefileWriter(cfg).write(result.getEntities(), Paths.get("output.shp"));
```

---

## API Overview

### CADParser

| Method | Description |
|---|---|
| `parse(Path path)` | Parse DXF file (auto encoding detection) |
| `parse(Reader reader)` | Parse from Reader (useful in unit tests) |
| `parseStream(Path path)` | Lazy-streaming parse; returns `Stream<CADEntity>` (v1.3.0) |

### ParseConfig (Builder pattern)

| Parameter | Default | Description |
|---|---|---|
| `crs` | null | CRS identifier (e.g. `EPSG:4545`) |
| `arcTolerance` | 0.01 | Arc/spline chord-height tolerance (coordinate units) |
| `coordinateDecimalPlaces` | 4 | GeoJSON coordinate decimal places (0~15) |

### ParseResult

| Method | Return type | Description |
|---|---|---|
| `getEntities()` | `List<CADEntity>` | Model-space entities (INSERT blocks expanded) |
| `getErrors()` | `List<ParseError>` | Leveled error list |
| `getStats()` | `ParseStats` | Parse time, entity count, error counts |
| `getMetadata()` | `DrawingMetadata` | Version, units, CRS, etc. |

### CADEntity

```java
entity.getType()         // "LINE" / "ARC" / "INSERT" etc.
entity.getLayer()        // layer name
entity.getHandle()       // DXF handle (unique ID)
entity.geometry()        // JTS Geometry (Point/LineString/Polygon etc.)
entity.getProperties()   // Map<String, Object> — color, text, feature code, etc.
```

Common property keys:

| Key | Type | Description |
|---|---|---|
| `colorRgb` | `int[3]` | RGB color (True Color > ACI > BYLAYER inheritance) |
| `colorAci` | `Integer` | ACI color number (only present when explicitly set) |
| `text` | `String` | Cleaned text content for TEXT / MTEXT |
| `elevation` | `Double` | Elevation (Z) for LWPOLYLINE / POINT |
| `featureCode` | `String` | Feature code from CASS / EPS XDATA |
| `featureType` | `String` | Feature name from GB/T 20257 (e.g. "普通房屋") |
| `featureCategory` | `String` | Feature category (e.g. "建筑") |
| `blockName` | `String` | Block name referenced by INSERT |
| `xdata` | `Map` | Raw XDATA (all app names) |
| `dimensionValue` | `Double` | DIMENSION measured value (code 42, v1.3.0) |
| `dimPoint1` / `dimPoint2` | `double[2]` | DIMENSION definition points (v1.3.0) |
| `controlPoints` | `List<double[]>` | SPLINE control points (v1.3.0) |

---

## DXF Output (Write)

### Quick Write

```java
import com.nanodxf.output.DXFWriter;
import com.nanodxf.output.DXFWriteConfig;
import com.nanodxf.model.DXFVersion;

List<CADEntity> entities = List.of(
    CADEntity.builder(CADEntity.Types.LINE)
        .layer("Road")
        .geometry(GF.createLineString(new Coordinate[]{
            new Coordinate(0, 0), new Coordinate(100, 0)}))
        .property(EntityProperty.COLOR_ACI, AciColor.WHITE)
        .build()
);

// R2007 + GBK (recommended for GstarCAD / AutoCAD)
DXFWriteConfig config = DXFWriteConfig.builder()
    .version(DXFVersion.R2007)
    .encoding("GBK")               // required when layer names contain Chinese characters
    .coordinateDecimalPlaces(4)
    .build();

new DXFWriter(config).write(entities, Paths.get("output.dxf"));
```

### Write ARC / CIRCLE / HATCH / INSERT / ELLIPSE / SOLID / 3DFACE / SPLINE

```java
// ARC: center Point + radius/startAngle/endAngle properties
entities.add(CADEntity.builder(CADEntity.Types.ARC)
    .layer("Arc")
    .geometry(GF.createPoint(new Coordinate(50, 50)))
    .property(EntityProperty.RADIUS,      25.0)
    .property(EntityProperty.START_ANGLE, 0.0)
    .property(EntityProperty.END_ANGLE,   270.0)
    .build());

// CIRCLE: center Point + radius property
entities.add(CADEntity.builder(CADEntity.Types.CIRCLE)
    .layer("Circle")
    .geometry(GF.createPoint(new Coordinate(150, 50)))
    .property(EntityProperty.RADIUS, 30.0)
    .build());

// HATCH SOLID: Polygon geometry (holes supported)
entities.add(CADEntity.builder(CADEntity.Types.HATCH)
    .layer("Fill")
    .geometry(polygon)
    .property(EntityProperty.COLOR_ACI, AciColor.GREEN)
    .build());

// Block definition + INSERT
CADBlock symbol = new CADBlock("ARROW");
symbol.setInsertionPoint(0, 0, 0);
symbol.addEntity(CADEntity.builder(CADEntity.Types.LINE)
    .layer("0").geometry(lineGeom).build());
entities.add(CADEntity.builder(CADEntity.Types.INSERT)
    .layer("Symbol")
    .geometry(GF.createPoint(new Coordinate(100, 100)))
    .property(EntityProperty.BLOCK_NAME, "ARROW")
    .property(EntityProperty.SCALE_X, 2.0)
    .build());
new DXFWriter(config).write(List.of(symbol), entities, Paths.get("output.dxf"));

// ELLIPSE: center Point + major axis vector + axis ratio (v1.3.0)
entities.add(CADEntity.builder(CADEntity.Types.ELLIPSE)
    .layer("Ellipse")
    .geometry(GF.createPoint(new Coordinate(200, 100)))
    .property(EntityProperty.MAJOR_AXIS_X, 50.0)  // major axis endpoint X (relative to center)
    .property(EntityProperty.MAJOR_AXIS_Y,  0.0)
    .property(EntityProperty.AXIS_RATIO,    0.5)  // minor/major ratio
    .property(EntityProperty.START_ANGLE,   0.0)  // start parameter (radians)
    .property(EntityProperty.END_ANGLE,     2 * Math.PI)
    .build());

// SOLID: 4-vertex Polygon (v1.3.0)
entities.add(CADEntity.builder(CADEntity.Types.SOLID)
    .layer("Fill")
    .geometry(solidPolygon)   // first 4 vertices of exterior ring used
    .build());

// SPLINE: control points required for DXF SPLINE output; otherwise falls back to LWPOLYLINE (v1.3.0)
List<double[]> ctrlPts = List.of(
    new double[]{0,0,0}, new double[]{10,20,0},
    new double[]{30,15,0}, new double[]{40,0,0});
entities.add(CADEntity.builder(CADEntity.Types.SPLINE)
    .layer("Spline")
    .geometry(GF.createLineString(/* discretized coords */))
    .property(EntityProperty.CONTROL_POINTS, ctrlPts)  // >= 4 points required
    .build());
```

### DXFWriteConfig Parameters

| Parameter | Default | Description |
|---|---|---|
| `version` | `R2007` | Output version (`R12` or `R2007`) |
| `encoding` | auto | R2007+ defaults to UTF-8; others default to GBK. Specify explicitly when layer names contain non-ASCII characters. |
| `coordinateDecimalPlaces` | `4` | Coordinate decimal places (0~15) |

### Supported Entity Types (Write)

| Type (`CADEntity.Types.*`) | Input geometry | Output entity |
|---|---|---|
| `LINE` | `LineString` (2 points) | LINE |
| `LWPOLYLINE` | `LineString` (multi-point) / `LinearRing` | LWPOLYLINE |
| `LWPOLYLINE` | `Polygon` | exterior ring + each hole as LWPOLYLINE |
| `POINT` | `Point` | POINT |
| `TEXT` | `Point` | TEXT (requires `text` property) |
| `MTEXT` | `Point` | MTEXT (requires `text` property) |
| `ARC` | `Point` (center) | ARC (requires `radius`/`startAngle`/`endAngle`) |
| `CIRCLE` | `Point` (center) | CIRCLE (requires `radius`) |
| `HATCH` | `Polygon`/`MultiPolygon` | HATCH (SOLID fill, holes supported) |
| `INSERT` | `Point` (insertion) | INSERT (requires `blockName`) |
| `ELLIPSE` | `Point` (center) | ELLIPSE (R2007) / 72-segment POLYLINE approx (R12, v1.3.1) |
| `SOLID` | `Polygon` / `LinearRing` (3~4 vertices) | SOLID (v1.3.0) |
| `FACE3D` | `LinearRing` / `Polygon` (3~4 vertices) | 3DFACE (v1.3.0) |
| `SPLINE` | `LineString` + `controlPoints` property | SPLINE (R2007) / POLYLINE (R12, v1.3.1) |
| any | `GeometryCollection` | recursively expanded |

### Supported Write Properties

| Key | Type | Description |
|---|---|---|
| `colorAci` | `Integer` | ACI color number (also sets layer color) |
| `colorRgb` | `int[3]` | True Color (R2004+ only, code 420) |
| `text` | `String` | TEXT / MTEXT content |
| `height` | `Double` | Text height (default 2.5) |
| `rotation` | `Double` | Text rotation angle (degrees, default 0) |
| `style` | `String` | TEXT style name (default "Standard") |
| `radius` | `Double` | ARC / CIRCLE radius |
| `startAngle` | `Double` | ARC start angle (degrees) / ELLIPSE start parameter (radians) |
| `endAngle` | `Double` | ARC end angle (degrees) / ELLIPSE end parameter (radians) |
| `hatchPattern` | `String` | HATCH pattern name (default `"SOLID"`) |
| `blockName` | `String` | INSERT block name reference |
| `scaleX`/`scaleY`/`scaleZ` | `Double` | INSERT scale factors (default 1.0) |
| `lineType` | `String` | Layer linetype name (default `"Continuous"`) |
| `lineWeight` | `Integer` | Layer lineweight code (default -3=ByLayer) |
| `xdata` | `Map` | XDATA write-through (preserves feature codes) |
| `majorAxisX`/`majorAxisY` | `Double` | ELLIPSE major axis endpoint vector (v1.3.0) |
| `axisRatio` | `Double` | ELLIPSE minor/major axis ratio (v1.3.0) |
| `controlPoints` | `List<double[]>` | SPLINE control points (v1.3.0) |

### Format Version Comparison

| | R12 | R2007 |
|---|---|---|
| Subclass markers (`100 AcDbXxx`) | ✗ | ✅ |
| Owner handle (`330`) | ✗ | ✅ |
| BLOCKS / OBJECTS section | ✗ | ✅ |
| True Color (code 420) | ✗ | ✅ (R2004+) |
| GstarCAD compatible | ✅ | ✅ |
| AutoCAD 2020+ compatible | ✅ | ✅ |
| QGIS / LibreCAD compatible | ✅ | ✅ |

---

## Shapefile Output

```java
ShapefileWriteConfig cfg = ShapefileWriteConfig.builder()
    .crs("EPSG:4490")           // PRJ WKT: built-in for EPSG:4326/4490; comment fallback for others
    .encoding("GBK")            // DBF charset (GBK for Chinese GIS tools)
    .coordinateDecimalPlaces(4) // ELEVATION field decimal places (also controls DBF field width)
    .build();

new ShapefileWriter(cfg).write(entities, Paths.get("output.shp"));
// Outputs: output.shp (geometry) + output.shx (index) + output.dbf (attributes) + output.prj (CRS)
```

**Geometry type mapping** (dominant type wins for mixed collections):

| JTS Geometry | Shapefile Type |
|---|---|
| `Point` | POINT (1) |
| `LineString` / `MultiLineString` / `LinearRing` | POLYLINE (3) |
| `Polygon` / `MultiPolygon` | POLYGON (5) |
| No geometry (empty list or all-null) | NULL (0) |

**.prj CRS support** — priority order:

| `crs` value | PRJ content |
|---|---|
| Starts with `GEOGCS[` / `PROJCS[` / `COMPD_CS[` | Written directly (user-supplied WKT) |
| `EPSG:4326` (WGS 84) | Built-in full WKT |
| `EPSG:4490` (CGCS2000) | Built-in full WKT |
| Any other code | Comment line `# CRS: ...` (human-readable, not parsed by GIS tools) |

**DBF attribute fields**: `LAYER`(C64) · `ETYPE`(C16) · `TEXT`(C254) · `FEAT_CODE`(C32) · `FEAT_TYPE`(C64) · `COLOR`(N4) · `ELEVATION`(N{w}.{dp}), where `w` and `dp` are controlled by `coordinateDecimalPlaces`

---

## Entity Type Constants

`CADEntity.Types` provides string constants for all DXF entity types, eliminating magic strings:

```java
// Parsing side
if (CADEntity.Types.LINE.equals(entity.getType())) { ... }

// Writing side
CADEntity.builder(CADEntity.Types.LWPOLYLINE).layer("Road").geometry(ring).build();
```

Constants: `LINE` / `ARC` / `CIRCLE` / `ELLIPSE` / `POINT` / `LWPOLYLINE` / `POLYLINE` / `VERTEX` / `SPLINE` / `TEXT` / `MTEXT` / `ATTRIB` / `HATCH` / `SOLID` / `FACE3D` (value `"3DFACE"`) / `INSERT` / `BLOCK` / `ENDBLK` / `SEQEND` / `DIMENSION` / `LEADER` / `VIEWPORT`

### Other Constant Classes

| Class | Purpose | Typical constants |
|---|---|---|
| `AciColor` | ACI color codes | `RED=1` / `WHITE=7` / `BYLAYER=256` / `ORANGE=30` |
| `EntityProperty` | `getProperties()` key strings | `COLOR_ACI` / `TEXT` / `ELEVATION` / `FEATURE_CODE` |
| `InsUnit` | `$INSUNITS` unit codes | `METERS=6` / `MILLIMETERS=4` / `FEET=2` |
| `output.LineTypeName` | Standard linetype names | `CONTINUOUS` / `DASHED` / `CENTER` / `HIDDEN` |

```java
// EntityProperty eliminates magic string keys
String text = (String) entity.getProperties().get(EntityProperty.TEXT);
Double elev = (Double) entity.getProperties().get(EntityProperty.ELEVATION);

// InsUnit conversion
double meters = InsUnit.toMeters(coord, result.getMetadata().getInsUnits());
```

---

## Supported DXF Versions

### Parsing (Read)

| Version string | AutoCAD version | Status |
|---|---|---|
| AC1009 | R12 | ✅ |
| AC1015 | R2000 | ✅ |
| AC1018 | R2004 | ✅ |
| AC1021 | R2007 | ✅ |
| AC1024+ | R2010~R2018 | ✅ |

### Writing (Generate)

| Version | Format | Recommended for |
|---|---|---|
| R12 (AC1009) | Minimal, no subclass markers | Cross-software compatibility, smallest file size |
| R2007 (AC1021) | Full format, GstarCAD verified | Chinese domestic CAD software (GstarCAD, ZWCAD, etc.) |

---

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `org.locationtech.jts:jts-core` | 1.19.0 | JTS geometry library |
| `com.googlecode.juniversalchardet:juniversalchardet` | 1.0.3 | Encoding auto-detection |

No other runtime dependencies. Requires Java 17+.

---

## Build

```bash
mvn clean package
mvn test
```

---

## License

Apache License 2.0
