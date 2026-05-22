# NOTICE

NanoDXF
Copyright 2024-2026 z-double
Licensed under the Apache License, Version 2.0

---

## Third-Party Components

### JTS Topology Suite
- **License**: Eclipse Distribution License 1.0 (BSD-style)
- **Source**: https://github.com/locationtech/jts
- **Used for**: JTS geometry objects (Point, LineString, Polygon, etc.)

### juniversalchardet
- **License**: Mozilla Public License 1.1 (MPL 1.1)
- **Source**: https://github.com/albfan/juniversalchardet
- **Used for**: Auto-detection of DXF file encoding (GBK / UTF-8)
- **MPL 1.1 notice**: The source code of this component is available at the
  URL above. If you modify any files governed by MPL 1.1, those modifications
  must remain available under MPL 1.1.

---

## Data Sources

### EPSG Geodetic Parameter Dataset
- **Registry**: https://epsg.org
- **Maintainer**: IOGP (International Association of Oil and Gas Producers)
- **Used for**: Built-in CRS WKT strings in `ShapefileWriter`
  (EPSG:4326, EPSG:4490, EPSG:4534–4554, EPSG:32644–32654)
- **License**: Permitted for use in software applications per IOGP terms.
  See https://epsg.org/terms-of-use.html

### GB/T 20257 Feature Codes
- **Standard**: GB/T 20257.1—2017《国家基本比例尺地图图式》
- **Issuer**: Standardization Administration of China (SAC)
- **Used for**: Feature code registry (`FeatureCodeRegistry`) mapping
  surveying codes to feature names and categories
