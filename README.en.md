# NanoDXF

**Lightweight DXF file parser for GIS / surveying use cases.**

Pure Java, no third-party CAD dependencies. Auto-detects GBK / UTF-8 encoding. Outputs JTS geometry objects and GeoJSON.

---

## Features

- **All major entity types**: LINE / ARC / CIRCLE / ELLIPSE / POINT / TEXT / MTEXT / LWPOLYLINE / POLYLINE / SPLINE / HATCH / INSERT / 3DFACE / SOLID / DIMENSION
- **Recursive INSERT expansion**: affine transform (scale → rotate → translate), path-set cycle detection
- **Chinese surveying software support**: CASS / EPS / MapMatrix / MapGIS / SuperMap XDATA feature code extraction, ~80 built-in GB/T 20257 mappings
- **BYLAYER color inheritance**: ACI 256-color table → layer color → entity color full chain
- **Auto encoding detection**: UTF-8 BOM → juniversalchardet → version inference → GBK fallback
- **Fault-tolerant parsing**: truncated files, corrupted entities, unknown entity types never abort parsing; errors collected with severity levels (FATAL / WARN / INFO)
- **GeoJSON output**: configurable coordinate precision, CRS annotation, large-coordinate float noise suppression

---

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.github.z-double</groupId>
    <artifactId>nano-dxf</artifactId>
    <version>1.2.1</version>
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

### Export GeoJSON

```java
GeoJsonSerializer ser = new GeoJsonSerializer(result.getMetadata());
String geojson = ser.serialize(result.getEntities(), result.getMetadata());
Files.writeString(Paths.get("output.geojson"), geojson);
```

---

## API Overview

### CADParser

| Method | Description |
|---|---|
| `parse(Path path)` | Parse DXF file (auto encoding detection) |
| `parse(Reader reader)` | Parse from Reader (useful in unit tests) |

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
        .property("colorAci", 7)
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

### DXFWriteConfig Parameters

| Parameter | Default | Description |
|---|---|---|
| `version` | `R2007` | Output version (`R12` or `R2007`) |
| `encoding` | auto | R2007+ defaults to UTF-8; others default to GBK. Specify explicitly when layer names contain non-ASCII characters. |
| `coordinateDecimalPlaces` | `4` | Coordinate decimal places (0~15) |

### Entity Type Constants

`CADEntity.Types` provides string constants for all DXF entity types, eliminating magic strings:

```java
// Parsing side
if (CADEntity.Types.LINE.equals(entity.getType())) { ... }

// Writing side
CADEntity.builder(CADEntity.Types.LWPOLYLINE).layer("Road").geometry(ring).build();
```

Constants: `LINE` / `ARC` / `CIRCLE` / `ELLIPSE` / `POINT` / `LWPOLYLINE` / `POLYLINE` / `VERTEX` / `SPLINE` / `TEXT` / `MTEXT` / `ATTRIB` / `HATCH` / `SOLID` / `FACE3D` (value `"3DFACE"`) / `INSERT` / `BLOCK` / `ENDBLK` / `SEQEND` / `DIMENSION` / `LEADER` / `VIEWPORT`

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
