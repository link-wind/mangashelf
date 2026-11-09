package com.mangashelf.external.manga;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** nonce 求值与区间删除解码（样本为 2026-11-09 实测抓包）。 */
class TencentNonceDecoderTest {

    private static final String PAYLOAD =
            "edyJjb2c1pYyI6eyJpZCI6NTIzMTk0LCJ0aXRsZSI6IueBteWlkSJ9fQ==";

    @Test
    void nonceLiteralOnly() {
        assertEquals("28b9a419037905b39d1536d79eb7bb77",
                TencentNonceDecoder.evalNonceLine(
                        "window[\"non\"+\"ce\"] = \"28b9a419037905b39d1536d79eb7bb77\""));
    }

    @Test
    void nonceWithEvalSegments() {
        // 样本 cid=2：算术、Math.pow、逻辑非混合
        assertEquals("22", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"2\")).toString() + \"2\""));
        assertEquals("3351", TencentNonceDecoder.evalNonceLine(
                "window[\"non\"+\"ce\"] = \"3\" + (+eval(\"Math.pow(1,3)+1+1\")).toString() + \"51\""));
        assertEquals("0", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"!1\")).toString()"));
        assertEquals("4", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"!!1+!1+!!2+!!2+1\")).toString()"));
        assertEquals("5", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"5&5\")).toString()"));
        assertEquals("1", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"1 * 0 + 1\")).toString()"));
        // 实测样式：字符码运算（航海王第 1 话）
        assertEquals("4", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"'1'.charCodeAt() - 45\")).toString()"));
        assertEquals("52", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"'4'.charCodeAt(0)\")).toString()"));
        // 实测样式：Math.round + 按位非 + 前导点小数（JS：round(.5)=1，~~1.8=截断=1）
        assertEquals("2", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"Math.round(.5) + ~~1.8\")).toString()"));
        // 实测样式：parseInt（截断取整）与 DOM 存在性检查（浏览器内恒真）
        assertEquals("2", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"parseInt(7/3)\")).toString()"));
        assertEquals("1", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"!!document.getElementsByTagName('html')\")).toString()"));
        // 实测样式：三元 + 比较
        assertEquals("2", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"0<=3?2:1\")).toString()"));
        // 实测样式：substring 结果被外层 + 强转数字
        assertEquals("3", TencentNonceDecoder.evalNonceLine(
                "window[\"n\"+\"once\"] = (+eval(\"'123'.substring(2)\")).toString()"));
        // 大数字 token：JS 255&parseInt 取低 8 位，int 会溢出
        assertEquals(255 & 7913057851L, Long.parseLong("7913057851") & 0xFF);
    }

    @Test
    void rejectsNonNumericExpression() {
        assertThrows(IllegalStateException.class,
                () -> TencentNonceDecoder.evalNonceLine("window[\"nonce\"] = eval(\"System.exit(1)\")"));
    }

    @Test
    void decodeRemovesRangesThenBase64() {
        // nonce "6c" → 位置 6 删 1 字符；"1d" → 位置 1 删 1 字符（自后向前）
        String json = TencentNonceDecoder.decode(PAYLOAD, "6c1d");
        assertEquals("{\"comic\":{\"id\":523194,\"title\":\"灵契\"}}", json);
    }

    @Test
    void outOfRangeTokenFails() {
        assertThrows(IllegalStateException.class, () -> TencentNonceDecoder.decode(PAYLOAD, "999z"));
    }
}
