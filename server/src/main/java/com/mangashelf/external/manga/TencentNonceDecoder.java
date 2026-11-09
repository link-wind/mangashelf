package com.mangashelf.external.manga;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯动漫章节页解码（M23）。
 *
 * <p>上游 `style=plain` 响应为两行文本：第一行是经「区间删除」混淆的 base64 JSON，
 * 第二行是拼装 nonce 的 JS 片段（如 {@code window["non"+"ce"] = "6c416" +
 * (+eval("5&5")).toString() + "16d..."}）。解码步骤：求值 nonce → 提取
 * {@code \d+[a-zA-Z]+} 记号 → 自后向前按 {@code 数字&255} 位置删除字母长度个字符 →
 * base64 解码。表达式求值器只接受数字运算（数字/括号/!/+,-,*,/,%/&amp;,^,|/Math.pow），
 * 拒绝其他任何字符——远端内容不可执行。</p>
 */
final class TencentNonceDecoder {

    private TencentNonceDecoder() {
    }

    private static final Pattern TOKEN = Pattern.compile("\\d+[a-zA-Z]+");

    /** 从 nonce 拼装 JS 行求出 nonce 字符串。 */
    static String evalNonceLine(String jsLine) {
        int eq = jsLine.indexOf('=');
        if (eq < 0) {
            throw new IllegalStateException("nonce 行缺少赋值号");
        }
        String rhs = jsLine.substring(eq + 1);
        StringBuilder nonce = new StringBuilder();
        for (String seg : splitTopLevelPlus(rhs)) {
            String s = seg.trim();
            if (s.startsWith("\"")) {
                nonce.append(unquote(s));
            } else {
                Matcher m = Pattern.compile("eval\\(\"([^\"]*)\"\\)").matcher(s);
                if (!m.find()) {
                    throw new IllegalStateException("nonce 段无法识别：" + abbreviate(s));
                }
                nonce.append(jsToString(Eval.eval(m.group(1))));
            }
        }
        return nonce.toString();
    }

    /** 混淆行 + nonce → 解码后的 JSON 文本。 */
    static String decode(String payload, String nonce) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(nonce);
        while (m.find()) {
            tokens.add(m.group());
        }
        StringBuilder sb = new StringBuilder(payload);
        for (int i = tokens.size() - 1; i >= 0; i--) {
            String token = tokens.get(i);
            int letterStart = 0;
            while (letterStart < token.length() && Character.isDigit(token.charAt(letterStart))) {
                letterStart++;
            }
            int pos = (int) (Long.parseLong(token.substring(0, letterStart)) & 0xFF);
            int end = pos + (token.length() - letterStart);
            if (end > sb.length()) {
                throw new IllegalStateException("nonce 删除区间越界：" + token);
            }
            sb.delete(pos, end);
        }
        String b64 = sb.toString();
        b64 += "=".repeat((4 - b64.length() % 4) % 4);
        return new String(Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 拆分 RHS 顶层 +（括号内与引号内的 + 不拆）。 */
    private static List<String> splitTopLevelPlus(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (!inQuote && c == '(') depth++;
            else if (!inQuote && c == ')') depth--;
            else if (!inQuote && depth == 0 && c == '+') {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static String unquote(String s) {
        String body = s.replaceAll("^\"+", "").replaceAll("\"+$", "");
        return String.join("", body.split("\"\\s*\\+\\s*\""));
    }

    /** JS Number→String：整值不带小数。 */
    private static String jsToString(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    /** 极小 JS 数值表达式求值（递归下降），字符白名单之外显式报错。 */
    private static final class Eval {
        private final String src;
        private int pos;

        private Eval(String src) {
            this.src = src;
        }

        static double eval(String expr) {
            Eval e = new Eval(expr);
            double v = e.ternary();
            e.ws();
            if (e.pos != e.src.length()) {
                throw new IllegalStateException("表达式存在未消费字符：" + e.src);
            }
            return v;
        }

        /** 三元条件：cond ? a : b（cond 为真值判断）。 */
        private double ternary() {
            double cond = or();
            ws();
            if (pos >= src.length() || src.charAt(pos) != '?') return cond;
            pos++; // '?'
            double whenTrue = ternary();
            ws();
            consume(':');
            double whenFalse = ternary();
            return cond != 0 ? whenTrue : whenFalse;
        }

        /** 比较运算（低于加减）：a < <= > >= b。 */
        private double compare() {
            double v = add();
            for (;;) {
                ws();
                char c = pos < src.length() ? src.charAt(pos) : '\0';
                char next = pos + 1 < src.length() ? src.charAt(pos + 1) : '\0';
                if (c == '<' && next == '=') {
                    pos += 2;
                    v = v <= add() ? 1 : 0;
                } else if (c == '>' && next == '=') {
                    pos += 2;
                    v = v >= add() ? 1 : 0;
                } else if (c == '<' && next != '<') {
                    pos++;
                    v = v < add() ? 1 : 0;
                } else if (c == '>' && next != '>') {
                    pos++;
                    v = v > add() ? 1 : 0;
                } else {
                    return v;
                }
            }
        }

        private double or() {
            double v = xor();
            for (;;) {
                ws();
                if (!eat('|')) return v;
                v = (long) v | (long) xor();
            }
        }

        private double xor() {
            double v = and();
            for (;;) {
                ws();
                if (!eat('^')) return v;
                v = (long) v ^ (long) and();
            }
        }

        private double and() {
            double v = compare();
            for (;;) {
                ws();
                if (!eat('&')) return v;
                v = (long) v & (long) compare();
            }
        }

        private double add() {
            double v = mul();
            for (;;) {
                ws();
                if (eat('+')) v += mul();
                else if (eat('-')) v -= mul();
                else return v;
            }
        }

        private double mul() {
            double v = unary();
            for (;;) {
                ws();
                if (eat('*')) v *= unary();
                else if (eat('/')) v /= unary();
                else if (eat('%')) v %= unary();
                else return v;
            }
        }

        private double unary() {
            ws();
            if (eat('!')) return unaryTruth() ? 0 : 1; // !x
            if (eat('~')) return -(long) unary() - 1;  // JS ~x = -ToInt32(x)-1
            if (eat('-')) return -unary();
            if (eat('+')) return unary();
            return primary();
        }

        /** 求值连续逻辑非后的真值（!!x 透传，!x 取反）。 */
        private boolean unaryTruth() {
            ws();
            if (eat('!')) return !unaryTruth();
            return primary() != 0;
        }

        private double primary() {
            ws();
            if (peek() == '(') {
                consume('(');
                double v = or();
                ws();
                consume(')');
                return v;
            }
            if (peek() == '\'' || peek() == '"') {
                return stringPrimary();
            }
            if (peek() == 'M' && matchWord("Math.")) {
                return mathCall();
            }
            if (peek() == 'p' && matchWord("parseInt")) {
                ws();
                consume('(');
                double v = or();
                ws();
                consume(')');
                return (long) v; // JS parseInt：向零截断
            }
            if (peek() == 'd' && matchWord("document.getElementsByTagName")) {
                // 上游用页面元素存在性做环境检查；对我们返回的 HTML 上下文恒真
                ws();
                consume('(');
                ws();
                if (peek() == '\'' || peek() == '"') { // 跳过标签名字符串参数
                    char quote = src.charAt(pos);
                    pos++;
                    while (pos < src.length() && src.charAt(pos) != quote) pos++;
                    pos++;
                } else {
                    or();
                }
                ws();
                consume(')');
                return 1;
            }
            int start = pos;
            if (peek() == '.') pos++; // .5 形式
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            if (pos == start) {
                throw new IllegalStateException("表达式缺少操作数：" + src);
            }
            return Double.parseDouble(src.substring(start, pos));
        }

        /** Math.<fn>(args)：闭合集合 pow/round/floor/ceil/abs/sqrt/trunc/min/max。 */
        private double mathCall() {
            int s = pos;
            while (pos < src.length() && Character.isLetter(src.charAt(pos))) {
                pos++;
            }
            String fn = src.substring(s, pos);
            ws();
            consume('(');
            List<Double> args = new ArrayList<>();
            if (peek() != ')') {
                args.add(or());
                while (eat(',')) {
                    args.add(or());
                }
            }
            consume(')');
            double a = args.isEmpty() ? 0 : args.getFirst();
            return switch (fn) {
                case "pow" -> Math.pow(a, args.size() > 1 ? args.get(1) : 0);
                case "round" -> Math.round(a);
                case "floor" -> Math.floor(a);
                case "ceil" -> Math.ceil(a);
                case "abs" -> Math.abs(a);
                case "sqrt" -> Math.sqrt(a);
                case "trunc" -> (long) a;
                case "min" -> args.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                case "max" -> args.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                default -> throw new IllegalStateException("不支持的 Math." + fn + "：" + src);
            };
        }

        /** 字符串字面量，仅支持 '.charCodeAt()'（可带单个整数下标）。 */
        private double stringPrimary() {
            char quote = src.charAt(pos);
            pos++;
            StringBuilder chars = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != quote) {
                chars.append(src.charAt(pos));
                pos++;
            }
            if (pos >= src.length()) {
                throw new IllegalStateException("字符串缺少闭合引号：" + src);
            }
            pos++; // 闭合引号
            ws();
            if (matchWord(".charCodeAt")) {
                ws();
                consume('(');
                ws();
                int index = 0;
                int start = pos;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
                if (pos > start) index = Integer.parseInt(src.substring(start, pos));
                ws();
                consume(')');
                if (index >= chars.length()) {
                    throw new IllegalStateException("charCodeAt 下标越界：" + src);
                }
                return chars.charAt(index);
            }
            if (matchWord(".substring")) {
                ws();
                consume('(');
                ws();
                int from = (int) or();
                ws();
                consume(')');
                String sub = chars.substring(Math.min(from, chars.length()));
                // 上游外层 (+eval(...)) 对字符串结果做数字强转
                return Double.parseDouble(sub);
            }
            throw new IllegalStateException("字符串仅支持 charCodeAt/substring：" + src);
        }

        private void ws() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            ws();
            if (pos >= src.length()) {
                throw new IllegalStateException("表达式意外结束：" + src);
            }
            return src.charAt(pos);
        }

        private void consume(char c) {
            ws();
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalStateException("表达式缺少 " + c + "：" + src);
            }
            pos++;
        }

        private boolean eat(char c) {
            ws();
            if (pos < src.length() && src.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private boolean matchWord(String w) {
            ws();
            if (src.regionMatches(pos, w, 0, w.length())) {
                pos += w.length();
                return true;
            }
            return false;
        }
    }
}
