package dev.probeloom.service;

import dev.probeloom.model.PlatformModels;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SnapshotRules {
    private SnapshotRules() { }

    public static List<PlatformModels.SnapshotItem> snapshotItems(PlatformModels.CaseAction action) {
        if (action.snapshotRows != null && !action.snapshotRows.isEmpty()) return action.snapshotRows;
        if (action.datasourceKey == null && action.datasetName == null && action.snapshotName == null && action.clearAfterBackup == null) {
            return Collections.emptyList();
        }
        PlatformModels.SnapshotItem item = new PlatformModels.SnapshotItem();
        item.datasourceKey = action.datasourceKey;
        item.datasetName = action.datasetName;
        item.snapshotName = action.snapshotName;
        item.clearAfterBackup = action.clearAfterBackup;
        item.backupCondition = action.backupCondition;
        return Collections.singletonList(item);
    }

    public static void validate(List<PlatformModels.CaseAction> actions) {
        Map<String, PlatformModels.SnapshotItem> backups = new LinkedHashMap<String, PlatformModels.SnapshotItem>();
        for (PlatformModels.CaseAction action : actions) {
            if (!"BACKUP".equals(action.actionType) && !"RESTORE".equals(action.actionType)) continue;
            if (Boolean.TRUE.equals(action.runOnCaseEnd)) throw new IllegalArgumentException("备份/恢复由自动快照收尾处理，不能配置 runOnCaseEnd：" + action.name);
            if (Boolean.TRUE.equals(action.coreAssertion)) throw new IllegalArgumentException("备份/恢复步骤不能标记核心核对");
            if ("BACKUP".equals(action.actionType)) {
                List<PlatformModels.SnapshotItem> items = snapshotItems(action);
                if (items.isEmpty()) throw new IllegalArgumentException("备份步骤至少需要一行备份表：" + action.name);
                for (PlatformModels.SnapshotItem item : items) {
                    validateItem(item, action.name);
                    if (item.clearAfterBackup == null) throw new IllegalArgumentException("备份步骤必须指定备份后是否清空原表：" + action.name);
                    if (backups.putIfAbsent(item.snapshotName, item) != null) {
                        throw new IllegalArgumentException("本案例快照标识重复：" + item.snapshotName);
                    }
                }
            } else {
                validateItem(action.datasourceKey, action.datasetName, action.snapshotName, action.name);
                PlatformModels.SnapshotItem backup = backups.get(action.snapshotName);
                if (backup == null || !backup.datasourceKey.equals(action.datasourceKey) || !backup.datasetName.equals(action.datasetName)) {
                    throw new IllegalArgumentException("恢复步骤必须包含同数据源、同表的前置备份步骤：" + action.snapshotName);
                }
            }
        }
    }

    private static void validateItem(PlatformModels.SnapshotItem item, String actionName) {
        validateItem(item.datasourceKey, item.datasetName, item.snapshotName, actionName);
        validateCondition(item.backupCondition);
    }

    public static void validateCondition(String condition) {
        if (condition == null || condition.trim().isEmpty()) return;
        String expression = condition.replaceAll("'(?:''|[^'])*'", "''");
        if (expression.contains(";") || expression.contains("--") || expression.contains("/*")
                || expression.contains("*/") || expression.contains("#")
                || expression.replace("''", "").contains("'")
                || expression.matches("(?is).*\\b(SELECT|FROM|WHERE|JOIN|UNION|INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|INTO|ORDER|GROUP|HAVING|LIMIT|OFFSET|RETURNING)\\b.*")) {
            throw new IllegalArgumentException("备份条件只填写当前表的条件表达式，不含 WHERE、完整 SQL、子查询、分号或注释");
        }
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char token = expression.charAt(i);
            if (token == '(') depth++;
            if (token == ')' && --depth < 0) throw new IllegalArgumentException("备份条件括号不匹配");
        }
        if (depth != 0) throw new IllegalArgumentException("备份条件括号不匹配");
    }

    private static void validateItem(String datasourceKey, String datasetName, String snapshotName, String actionName) {
        if (datasourceKey == null || datasourceKey.trim().isEmpty()
                || datasetName == null || !datasetName.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?")
                || snapshotName == null || !snapshotName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("备份/恢复步骤必须填写数据源、合法表名和快照标识：" + actionName);
        }
    }
}
