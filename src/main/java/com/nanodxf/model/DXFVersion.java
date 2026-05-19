package com.nanodxf.model;

public enum DXFVersion {
    R12("AC1009"),
    R2000("AC1015"),
    R2004("AC1018"),
    R2007("AC1021"),
    R2010("AC1024"),
    R2013("AC1027"),
    R2018("AC1032"),
    UNKNOWN("UNKNOWN");

    private final String versionString;

    DXFVersion(String versionString) {
        this.versionString = versionString;
    }

    public String getVersionString() { return versionString; }

    public boolean before(DXFVersion other) {
        return this.ordinal() < other.ordinal();
    }

    public static DXFVersion fromString(String s) {
        for (DXFVersion v : values()) {
            if (v.versionString.equals(s)) return v;
        }
        return UNKNOWN;
    }
}
