package dev.probeloom.service;

import dev.probeloom.model.PlatformModels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 数据步骤的单表契约，同时供保存、执行及工作簿读写使用。 */
public final class TableStepSql {
    private static final String IDENTIFIER = "(?:\\[[^\\]]+\\]|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final Pattern TARGET = Pattern.compile("(?is)^(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+(" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")*)(.*)$");
    private TableStepSql() { }

    public static final class Data {
        public String table;
        public List<String> fields;
        public final List<List<String>> rows = new ArrayList<>();
        public final List<String> conditions = new ArrayList<>();
    }

    public static Data parse(PlatformModels.CaseAction action) {
        String sql = "CLEANUP".equals(action.actionType) ? action.cleanupSql : action.prepareSql;
        if (sql == null || sql.trim().isEmpty()) throw error(action, "缺少执行 SQL");
        if (action.datasetName == null || !action.datasetName.matches("[A-Za-z_][A-Za-z0-9_$.]*")) throw error(action, "必须填写合法的目标表");
        Data data = new Data();
        data.table = action.datasetName;
        for (String part : split(sql, ';')) {
            String statement = part.trim();
            if (statement.isEmpty()) continue;
            Matcher target = TARGET.matcher(statement);
            String command = "INSERT".equals(action.actionType) ? "INSERT INTO" : "UPDATE".equals(action.actionType) ? "UPDATE" : "DELETE FROM";
            if (!target.matches() || !target.group(1).replaceAll("\\s+", " ").equalsIgnoreCase(command)) throw error(action, "步骤类型与 SQL 不一致，只能执行 " + command);
            if (!data.table.equalsIgnoreCase(target.group(2).replace("[", "").replace("]", ""))) throw error(action, "一个步骤只能操作目标表“" + data.table + "”，请将不同表拆分为独立步骤");
            String tail = target.group(3).trim();
            if ("INSERT".equals(action.actionType)) {
                if (!tail.startsWith("(")) throw error(action, "插入 SQL 必须明确列出字段");
                int end = closing(tail, 0);
                List<String> fields = split(tail.substring(1, end), ',');
                fields(data, fields, action);
                tail = tail.substring(end + 1).trim();
                if (tail.matches("(?is)^VALUES\\b.*")) {
                    tail = tail.substring(6).trim();
                    while (!tail.isEmpty()) {
                        if (!tail.startsWith("(")) throw error(action, "插入数据必须使用字段和值，条件插入使用 SELECT 值 WHERE 条件");
                        end = closing(tail, 0);
                        row(data, split(tail.substring(1, end), ','), "", action);
                        tail = tail.substring(end + 1).trim();
                        if (tail.isEmpty()) break;
                        if (!tail.startsWith(",")) throw error(action, "插入记录之后存在不支持的 SQL");
                        tail = tail.substring(1).trim();
                    }
                } else if (tail.matches("(?is)^SELECT\\b.*")) {
                    tail = tail.substring(6).trim();
                    int where = keyword(tail, "WHERE");
                    if (where < 0 || keyword(tail, "FROM") >= 0 || keyword(tail, "UNION") >= 0) throw error(action, "条件插入必须使用 SELECT 字段值 WHERE 条件，不支持从其他表取数");
                    row(data, split(tail.substring(0, where), ','), tail.substring(where).trim(), action);
                } else throw error(action, "插入 SQL 必须使用 VALUES 或 SELECT 字段值 WHERE 条件");
            } else if ("UPDATE".equals(action.actionType)) {
                if (!tail.matches("(?is)^SET\\b.*") || keyword(tail, "FROM") >= 0 || keyword(tail, "JOIN") >= 0) throw error(action, "更新步骤必须只更新当前表");
                tail = tail.substring(3).trim();
                int where = keyword(tail, "WHERE");
                if (where < 0 || !tail.substring(where).matches("(?is)^WHERE\\s+.+")) throw error(action, "更新步骤必须填写 WHERE 条件");
                List<String> names = new ArrayList<>();
                List<String> values = new ArrayList<>();
                for (String assignment : split(tail.substring(0, where), ',')) {
                    int equal = assignment.indexOf('=');
                    if (equal < 0) throw error(action, "更新字段必须填写赋值表达式");
                    names.add(assignment.substring(0, equal).trim());
                    values.add(assignment.substring(equal + 1).trim());
                }
                fields(data, names, action);
                row(data, values, tail.substring(where).trim(), action);
            } else {
                if (!tail.matches("(?is)^WHERE\\s+.+")) throw error(action, "清理步骤必须填写当前表的 WHERE 条件，禁止全表删除");
                data.fields = Collections.emptyList();
                row(data, Collections.emptyList(), tail, action);
            }
        }
        if (data.rows.isEmpty()) throw error(action, "没有可执行的数据记录");
        return data;
    }

    private static void fields(Data data, List<String> fields, PlatformModels.CaseAction action) {
        for (String field : fields) if (!field.matches("[A-Za-z_][A-Za-z0-9_$]*")) throw error(action, "字段名不合法：" + field);
        if (data.fields != null && !data.fields.equals(fields)) throw error(action, "同一步骤各记录的字段必须一致，请拆分步骤");
        data.fields = fields;
    }

    private static void row(Data data, List<String> values, String condition, PlatformModels.CaseAction action) {
        if (values.size() != data.fields.size() || values.contains("")) throw error(action, "字段和值必须逐列对应且不能为空");
        data.rows.add(values);
        data.conditions.add(condition);
    }

    static List<String> split(String text, char delimiter) {
        List<String> values = new ArrayList<>();
        int depth = 0, start = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '\'') i++;
                else quoted = !quoted;
            } else if (!quoted) {
                if (c == '(') depth++;
                if (c == ')' && --depth < 0) throw new IllegalArgumentException("SQL 括号不匹配");
                if (c == delimiter && depth == 0) { values.add(text.substring(start, i).trim()); start = i + 1; }
            }
        }
        if (quoted || depth != 0) throw new IllegalArgumentException("SQL 引号或括号不匹配");
        values.add(text.substring(start).trim());
        return values;
    }

    private static int closing(String text, int start) {
        int depth = 0;
        boolean quoted = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '\'') i++;
                else quoted = !quoted;
            } else if (!quoted) {
                if (c == '(') depth++;
                if (c == ')' && --depth == 0) return i;
            }
        }
        throw new IllegalArgumentException("SQL 括号不匹配");
    }

    static int keyword(String text, String word) {
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '\'') i++;
                else quoted = !quoted;
            } else if (!quoted) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (depth == 0 && upper.startsWith(word, i)
                        && (i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1)))
                        && (i + word.length() == text.length() || !Character.isJavaIdentifierPart(text.charAt(i + word.length())))) return i;
            }
        }
        return -1;
    }

    private static IllegalArgumentException error(PlatformModels.CaseAction action, String message) {
        return new IllegalArgumentException(message + "：" + action.name);
    }
}
