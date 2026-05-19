package com.nanodxf.entity;

import com.nanodxf.core.GroupCodePair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 单个 DXF 实体的 group code 缓冲区。
 *
 * <p>EntityDispatcher 从 DXFReader 读取一个实体的所有 group code，
 * 存入此缓冲区后传给 EntityHandler，使 handler 无需直接操作 reader，
 * 便于纯函数化测试（给定固定输入，输出必然确定）。
 *
 * <p>同一 code 可能出现多次（如 LWPOLYLINE 的顶点 code 10/20），
 * 使用 {@link #all(int)} 获取全部值，{@link #first(int)} 只取第一个。
 */
public class EntityBuffer {
    private final List<GroupCodePair> pairs = new ArrayList<>();

    /** 追加一个 group code 对。 */
    public void add(GroupCodePair pair) {
        pairs.add(pair);
    }

    /** 返回所有 group code 对（只读视图）。 */
    public List<GroupCodePair> all() {
        return Collections.unmodifiableList(pairs);
    }

    /** 返回第一个指定 code 的值；若不存在返回空。 */
    public Optional<String> first(int code) {
        return pairs.stream()
                .filter(p -> p.code() == code)
                .map(GroupCodePair::value)
                .findFirst();
    }

    /** 返回所有指定 code 的值列表；如 LWPOLYLINE 的多个顶点。 */
    public List<String> all(int code) {
        return pairs.stream()
                .filter(p -> p.code() == code)
                .map(GroupCodePair::value)
                .collect(Collectors.toList());
    }

    /**
     * 读取指定 code 的浮点值，解析失败或不存在时返回 {@code defaultVal}。
     * 典型用法：{@code getDouble(40, 0.0)} 读取半径。
     */
    public double getDouble(int code, double defaultVal) {
        return first(code).map(v -> {
            try { return Double.parseDouble(v.trim()); }
            catch (NumberFormatException e) { return defaultVal; }
        }).orElse(defaultVal);
    }

    /**
     * 读取指定 code 的整数值，解析失败或不存在时返回 {@code defaultVal}。
     * 典型用法：{@code getInt(70, 0)} 读取标志位。
     */
    public int getInt(int code, int defaultVal) {
        return first(code).map(v -> {
            try { return Integer.parseInt(v.trim()); }
            catch (NumberFormatException e) { return defaultVal; }
        }).orElse(defaultVal);
    }

    /** 读取指定 code 的字符串值，不存在时返回 {@code defaultVal}。 */
    public String getString(int code, String defaultVal) {
        return first(code).orElse(defaultVal);
    }
}
