package com.nanodxf;

import com.nanodxf.model.ZStrategy;

public class ParseConfig {
    private final double arcTolerance;
    private final double proximityThreshold;
    private final int coordinateDecimalPlaces;
    private final ZStrategy zStrategy;
    private final String crs;

    private ParseConfig(Builder builder) {
        this.arcTolerance = builder.arcTolerance;
        this.proximityThreshold = builder.proximityThreshold;
        this.coordinateDecimalPlaces = builder.coordinateDecimalPlaces;
        this.zStrategy = builder.zStrategy;
        this.crs = builder.crs;
    }

    public double getArcTolerance() { return arcTolerance; }
    public double getProximityThreshold() { return proximityThreshold; }
    public int getCoordinateDecimalPlaces() { return coordinateDecimalPlaces; }
    public ZStrategy getZStrategy() { return zStrategy; }
    public String getCrs() { return crs; }

    public static Builder builder() { return new Builder(); }
    public static ParseConfig defaults() { return builder().build(); }

    public static class Builder {
        private double arcTolerance = 0.001;
        private double proximityThreshold = 1e-6;
        private int coordinateDecimalPlaces = 4;
        private ZStrategy zStrategy = ZStrategy.KEEP_3D;
        private String crs = null;

        public Builder arcTolerance(double v) {
            if (v <= 0) throw new IllegalArgumentException("arcTolerance 必须 > 0，当前值：" + v);
            this.arcTolerance = v; return this;
        }

        public Builder proximityThreshold(double v) {
            if (v < 0) throw new IllegalArgumentException("proximityThreshold 不能为负");
            this.proximityThreshold = v; return this;
        }

        public Builder coordinateDecimalPlaces(int v) {
            if (v < 0 || v > 15)
                throw new IllegalArgumentException("coordinateDecimalPlaces 范围 0~15");
            this.coordinateDecimalPlaces = v; return this;
        }

        public Builder zStrategy(ZStrategy s) { this.zStrategy = s; return this; }

        public Builder crs(String crs) { this.crs = crs; return this; }

        public ParseConfig build() { return new ParseConfig(this); }
    }
}
