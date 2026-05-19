package com.nanodxf.model;

import com.nanodxf.entity.CADEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CADBlock {
    private final String name;
    private double insertX, insertY, insertZ;
    private final List<CADEntity> entities = new ArrayList<>();

    public CADBlock(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public double getInsertX() { return insertX; }
    public double getInsertY() { return insertY; }
    public double getInsertZ() { return insertZ; }

    public void setInsertionPoint(double x, double y, double z) {
        this.insertX = x; this.insertY = y; this.insertZ = z;
    }

    public void addEntity(CADEntity entity) { entities.add(entity); }
    public List<CADEntity> entities() { return Collections.unmodifiableList(entities); }
}
