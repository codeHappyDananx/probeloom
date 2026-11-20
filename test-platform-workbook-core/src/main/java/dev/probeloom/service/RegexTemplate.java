package dev.probeloom.service;

import java.util.regex.Pattern;

final class RegexTemplate {
    static final String START = "#REGEX#";
    static final String END = "#ENDREGEX#";

    private RegexTemplate() { }

    static boolean contains(String value) {
        return value != null && (value.contains(START) || value.contains(END));
    }

    static Pattern compile(String template) {
        if (template == null || template.isEmpty()) {
            throw new IllegalArgumentException("正则模板不能为空");
        }
        StringBuilder expression = new StringBuilder();
        int offset = 0;
        boolean found = false;
        while (offset < template.length()) {
            int start = template.indexOf(START, offset);
            int strayEnd = template.indexOf(END, offset);
            if (strayEnd >= 0 && (start < 0 || strayEnd < start)) {
                throw new IllegalArgumentException("存在未配对的 " + END);
            }
            if (start < 0) {
                expression.append(Pattern.quote(template.substring(offset)));
                break;
            }
            expression.append(Pattern.quote(template.substring(offset, start)));
            int regexStart = start + START.length();
            int end = template.indexOf(END, regexStart);
            if (end < 0) throw new IllegalArgumentException("缺少结束标记 " + END);
            if (end == regexStart) throw new IllegalArgumentException("正则表达式不能为空");
            String regex = template.substring(regexStart, end);
            if (regex.contains(START)) throw new IllegalArgumentException("正则标记不允许嵌套");
            rejectDoubleBackslash(regex);
            Pattern.compile(regex);
            expression.append("(?:").append(regex).append(')');
            found = true;
            offset = end + END.length();
        }
        if (!found) throw new IllegalArgumentException("缺少正则标记 " + START);
        return Pattern.compile(expression.toString());
    }

    static boolean matches(String template, String actual) {
        return actual != null && compile(template).matcher(actual).matches();
    }

    static String singleExpression(String template) {
        compile(template);
        if (!template.startsWith(START) || !template.endsWith(END)
                || template.indexOf(START, START.length()) >= 0) {
            throw new IllegalArgumentException("变量提取只允许一个完整正则片段");
        }
        return template.substring(START.length(), template.length() - END.length());
    }

    // 正则统一按字面书写，连续两个反斜杠直接拒绝。
    private static void rejectDoubleBackslash(String regex) {
        if (!regex.contains("\\\\")) return;
        throw new IllegalArgumentException("正则片段不允许出现连续两个反斜杠 \\\\：平台统一按字面解析正则，"
                + "匹配小数点写 \\. 即可（#JSON# 单元格中也只写一个反斜杠，JSON 转义由平台自动处理）；"
                + "确需匹配字面反斜杠时请写 \\\\x5c。出错正则：" + regex);
    }
}
