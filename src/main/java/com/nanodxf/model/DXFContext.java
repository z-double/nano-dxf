package com.nanodxf.model;

import com.nanodxf.ParseConfig;
import com.nanodxf.entity.CADEntity;
import com.nanodxf.layer.CADLayer;
import com.nanodxf.layer.LineTypeDef;
import com.nanodxf.layer.TextStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析过程中的共享上下文。
 *
 * <p>生命周期：每次 {@code CADParser.parse()} 调用创建独立实例，不跨文件共享。
 * TABLES/BLOCKS 段解析完毕后填充 layers/textStyles/lineTypes/blocks，
 * ENTITIES 段只读这些数据，同时向 {@link #entities} 追加解析结果。
 */
public class DXFContext {

    /** 解析配置（arcTolerance、zStrategy 等），所有 handler 共享。 */
    public final ParseConfig config;

    /** 图层表，key=图层名（大小写敏感，DXF 规范区分大小写，但实践中常见不区分）。 */
    public final Map<String, CADLayer>    layers     = new LinkedHashMap<>();
    /** 文字样式表，key=样式名。 */
    public final Map<String, TextStyle>   textStyles = new LinkedHashMap<>();
    /** 线型定义表，key=线型名。 */
    public final Map<String, LineTypeDef> lineTypes  = new LinkedHashMap<>();
    /** 块定义表，key=块名（含 *Model_Space 等特殊块）。 */
    public final Map<String, CADBlock>    blocks     = new LinkedHashMap<>();

    /** 图纸元数据（版本、单位、CRS 等）。 */
    public DrawingMetadata metadata = new DrawingMetadata();

    /** 解析过程中收集的所有模型空间实体，由各 section parser 追加。 */
    public final List<CADEntity> entities = new ArrayList<>();

    public DXFContext(ParseConfig config) {
        this.config = config;
        // 默认图层 "0" 始终存在
        layers.put("0", new CADLayer("0"));
    }

    /** 获取已有图层，若不存在则自动创建（懒创建，避免 null 检查）。 */
    public CADLayer getOrCreateLayer(String name) {
        return layers.computeIfAbsent(name, CADLayer::new);
    }
}
