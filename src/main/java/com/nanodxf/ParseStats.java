package com.nanodxf;

public record ParseStats(long parseMs, int entityCount, int errorCount, int warningCount) {}
