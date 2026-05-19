package com.nanodxf.model;

import com.nanodxf.layer.CADLayer;
import com.nanodxf.layer.LineTypeDef;
import com.nanodxf.layer.TextStyle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析过程中的共享只读上下文。
 * 每次 CADParser.parse() 调用创建独立实例，不跨文件共享。
 * TABLES/BLOCKS 段解析完成后填充，ENTITIES 阶段只读。
 */
public class DXFContext {
    public final Map<String, CADLayer>    layers     = new LinkedHashMap<>();
    public final Map<String, TextStyle>   textStyles = new LinkedHashMap<>();
    public final Map<String, LineTypeDef> lineTypes  = new LinkedHashMap<>();
    public final Map<String, CADBlock>    blocks     = new LinkedHashMap<>();
    public DrawingMetadata metadata = new DrawingMetadata();

    public DXFContext() {
        layers.put("0", new CADLayer("0"));
    }

    public CADLayer getOrCreateLayer(String name) {
        return layers.computeIfAbsent(name, CADLayer::new);
    }
}
