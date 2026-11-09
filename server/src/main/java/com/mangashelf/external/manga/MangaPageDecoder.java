package com.mangashelf.external.manga;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 页图解码钩子（M23，闭合集合）：规则无法表达的加密/混淆响应由具名 Java 解码器处理。
 * 解码器只做「取数 + 解码」，不可执行远端代码。
 */
public interface MangaPageDecoder {

    String name();

    /**
     * @param pagesRule 规则里 pages 节点（url 模板 / list 路径等）
     * @param vars      章节复合 ID 按模板拆出的变量（comicId、cid…）
     * @return 页图 URL 列表；付费/不可读等显式抛 IllegalStateException
     */
    List<String> decode(JsonNode pagesRule, Map<String, String> vars) throws Exception;
}
