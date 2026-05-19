package com.nanodxf.model;

import com.nanodxf.ParseConfig;
import com.nanodxf.ParseError;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.layer.CADLayer;
import com.nanodxf.layer.LineTypeDef;
import com.nanodxf.layer.TextStyle;

import java.util.*;

/**
 * 解析过程中的共享上下文。
 *
 * <p>生命周期：每次 {@code CADParser.parse()} 调用创建独立实例，不跨文件共享。
 * TABLES/BLOCKS 段解析完毕后填充命名符号表，ENTITIES 段只读这些数据，
 * 同时向 {@link #entities} 追加解析结果，向 {@link #errors} 追加错误记录。
 */
public class DXFContext {

    /** 解析配置（arcTolerance、zStrategy 等），所有 handler 共享。 */
    public final ParseConfig config;

    /** 图层表，key=图层名。 */
    public final Map<String, CADLayer>    layers     = new LinkedHashMap<>();
    /** 文字样式表，key=样式名。 */
    public final Map<String, TextStyle>   textStyles = new LinkedHashMap<>();
    /** 线型定义表，key=线型名。 */
    public final Map<String, LineTypeDef> lineTypes  = new LinkedHashMap<>();
    /** 块定义表，key=块名（含 *Model_Space 等）。 */
    public final Map<String, CADBlock>    blocks     = new LinkedHashMap<>();

    /** 图纸元数据（版本、单位、CRS 等）。 */
    public DrawingMetadata metadata = new DrawingMetadata();

    /** 解析过程中收集的模型空间实体，由 EntitiesParser 追加。 */
    public final List<CADEntity> entities = new ArrayList<>();

    /**
     * 解析过程中产生的错误/警告，由 EntitiesParser 等追加。
     *
     * <p>包括：handler 返回 null（WARN）、几何无法修复（WARN）、
     * 未知实体类型（INFO）等。
     */
    public final List<ParseError> errors = new ArrayList<>();

    /**
     * 未注册/不支持的实体类型集合（遇到时追加）。
     * 后续版本升级后，此集合中出现的新类型是需要新增 handler 的信号。
     */
    public final Set<String> skippedEntityTypes = new LinkedHashSet<>();

    /**
     * OBJECTS 段解析结果：handle → 对象属性 map（由 ObjectsParser 填充）。
     * 实体可通过 owner handle（code 330）查找关联的 XRECORD 数据。
     */
    public final Map<String, Map<String, Object>> objectData = new LinkedHashMap<>();

    public DXFContext(ParseConfig config) {
        this.config = config;
        layers.put("0", new CADLayer("0")); // 默认图层 "0" 始终存在
    }

    /** 获取已有图层，若不存在则自动创建。 */
    public CADLayer getOrCreateLayer(String name) {
        return layers.computeIfAbsent(name, CADLayer::new);
    }
}
