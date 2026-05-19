package com.nanodxf.model;

public class DrawingMetadata {
    private DXFVersion version = DXFVersion.UNKNOWN;
    private int insunits = 0;
    private double contourInterval = 0.0;
    private boolean measurement = true;
    private boolean is3D = false;
    private String crs;
    private String crsSource = "unknown";

    public DXFVersion getVersion() { return version; }
    public void setVersion(DXFVersion version) { this.version = version; }

    public int getInsunits() { return insunits; }
    public void setInsunits(int insunits) { this.insunits = insunits; }

    public double getContourInterval() { return contourInterval; }
    public void setContourInterval(double contourInterval) { this.contourInterval = contourInterval; }

    public boolean isMeasurement() { return measurement; }
    public void setMeasurement(boolean measurement) { this.measurement = measurement; }

    public boolean is3D() { return is3D; }
    public void set3D(boolean is3D) { this.is3D = is3D; }

    public String getCrs() { return crs; }
    public void setCrs(String crs) { this.crs = crs; }

    public String getCrsSource() { return crsSource; }
    public void setCrsSource(String crsSource) { this.crsSource = crsSource; }
}
