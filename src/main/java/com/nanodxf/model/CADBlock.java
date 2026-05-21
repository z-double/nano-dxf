package com.nanodxf.model;

import com.nanodxf.entity.CADEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DXF 块定义（BLOCK ... ENDBLK）。
 *
 * <p>块是可复用的实体集合，通过 INSERT 实体引用并展开。
 * 特殊块名：
 * <ul>
 *   <li>{@code *Model_Space} — R2000+ 模型空间实体（{@link com.nanodxf.section.BlocksParser} 路由）</li>
 *   <li>{@code *Paper_Space} — 图纸空间，由 {@link com.nanodxf.filter.PaperSpaceFilter} 过滤</li>
 * </ul>
 *
 * <p>块内的 INSERT（嵌套块引用）在 {@link com.nanodxf.section.BlocksParser} 阶段
 * 存为占位 {@link CADEntity}（不展开），由
 * {@link com.nanodxf.entity.handler.InsertHandler} 在 ENTITIES 阶段递归展开。
 *
 * <p><b>线程安全</b>：本类非线程安全。{@link #addEntity(CADEntity)} 和
 * {@link #setInsertionPoint(double, double, double)} 应在单线程中完成，
 * 再传入 {@link com.nanodxf.output.DXFWriter} 写出。
 */
public class CADBlock {

    /** 块名，与 BLOCKS 段中 {@code code 2} 对应，大小写敏感。 */
    private final String name;

    /** 块基点 X（{@code code 10}），用于偏移块内实体坐标。 */
    private double insertX;

    /** 块基点 Y（{@code code 20}）。 */
    private double insertY;

    /** 块基点 Z（{@code code 30}）。 */
    private double insertZ;

    /** 块内实体列表（含 INSERT 占位实体）。 */
    private final List<CADEntity> entities = new ArrayList<>();

    public CADBlock(String name) {
        this.name = name;
    }

    public String getName()     { return name; }
    public double getInsertX()  { return insertX; }
    public double getInsertY()  { return insertY; }
    public double getInsertZ()  { return insertZ; }

    /** 设置块基点坐标（对应 BLOCK header 中的 code 10/20/30）。 */
    public void setInsertionPoint(double x, double y, double z) {
        this.insertX = x; this.insertY = y; this.insertZ = z;
    }

    /** 向块内追加一个实体（包括 INSERT 占位实体）。 */
    public void addEntity(CADEntity entity) { entities.add(entity); }

    /** 返回块内实体的不可变视图。 */
    public List<CADEntity> entities() { return Collections.unmodifiableList(entities); }
}
