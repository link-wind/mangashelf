package com.mangashelf.external;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 轻量繁简转换器（零外部依赖）：从 classpath:/t2s-chars.txt 加载 3,048 条 ICU 常用繁简单字映射，
 * 供漫画标题跨源去重（title_key）与题材标签归一化（TagNormalizer）共用。
 */
public final class TraditionalToSimplified {

    private static final int BMP_START = 0x4E00;
    private static final int BMP_END = 0x9FFF;
    private static final char[] TABLE = loadTable();

    private TraditionalToSimplified() {}

    private static char[] loadTable() {
        char[] table = new char[BMP_END - BMP_START + 1];
        try (InputStream in = TraditionalToSimplified.class.getResourceAsStream("/t2s-chars.txt")) {
            if (in == null) return table;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() >= 2) {
                        char src = line.charAt(0);
                        char dst = line.charAt(1);
                        if (src >= BMP_START && src <= BMP_END) {
                            table[src - BMP_START] = dst;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 降级为原样返回
        }
        return table;
    }

    /** 将文本中的繁体汉字转为简体汉字，其余字符保持不变。 */
    public static String toSimplified(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder sb = null;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= BMP_START && c <= BMP_END) {
                char mapped = TABLE[c - BMP_START];
                if (mapped != 0) {
                    if (sb == null) {
                        sb = new StringBuilder(input.length());
                        sb.append(input, 0, i);
                    }
                    sb.append(mapped);
                    continue;
                }
            }
            if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? input : sb.toString();
    }
}
