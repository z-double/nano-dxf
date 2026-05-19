package com.nanodxf.core;

public record GroupCodePair(int code, String value) {

    public double asDouble() {
        return Double.parseDouble(value.trim());
    }

    public int asInt() {
        return Integer.parseInt(value.trim());
    }

    @Override
    public String toString() {
        return code + "\n" + value;
    }
}
