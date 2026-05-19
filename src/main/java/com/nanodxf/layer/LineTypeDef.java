package com.nanodxf.layer;

public class LineTypeDef {
    private final String name;
    private String description;

    public LineTypeDef(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
