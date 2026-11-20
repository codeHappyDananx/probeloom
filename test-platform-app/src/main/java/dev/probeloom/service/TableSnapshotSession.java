package dev.probeloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.probeloom.model.PlatformModels;
import dev.probeloom.repository.PlatformRepository;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TableSnapshotSession {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PlatformRepository repository;
    private final long executionId;
    private final int timeoutSeconds;
    private boolean operationOutcomeUnknown;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<String, Snapshot>();

    TableSnapshotSession(PlatformRepository repository, long executionId) {
        this(repository, executionId, 30);
    }

    TableSnapshotSession(PlatformRepository repository, long executionId, int timeoutSeconds) {
        this.repository = repository;
        this.executionId = executionId;
        this.timeoutSeconds = timeoutSeconds;
    }

    void load(PlatformModels.TableBackup record, PlatformModels.TestDataSource source, DataSource datasource) {
        Snapshot snapshot = new Snapshot(source, datasource, timeoutSeconds, record);
        snapshot.ready = record.rowCount != null && Arrays.asList("READY", "RESTORE_FAILED", "RESTORED", "DROP_FAILED", "DROPPED").contains(record.status);
        snapshots.put(record.snapshotName, snapshot);
    }

    boolean operationOutcomeUnknown() { return operationOutcomeUnknown; }

    String backup(PlatformModels.CaseAction action, PlatformModels.TestDataSource source, DataSource datasource) throws Exception {
        List<PlatformModels.SnapshotItem> items = SnapshotRules.snapshotItems(action);
        if (items.size() != 1) throw new IllegalArgumentException("单表备份调用必须只包含一行备份表：" + action.name);
        return backup(items.get(0), source, datasource);
    }

    String backup(PlatformModels.SnapshotItem item, PlatformModels.TestDataSource source, DataSource datasource) throws Exception {
        if (snapshots.containsKey(item.snapshotName)) throw new IllegalArgumentException("快照标识重复：" + item.snapshotName);
        Snapshot snapshot = new Snapshot(source, datasource, timeoutSeconds);
        PlatformModels.TableBackup record = snapshot.record;
        record.datasourceKey = item.datasourceKey;
        record.snapshotName = item.snapshotName;
        record.tableName = item.datasetName;
        SnapshotRules.validateCondition(item.backupCondition);
        record.backupCondition = item.backupCondition == null || item.backupCondition.trim().isEmpty() ? null : item.backupCondition.trim();
        String prefix = item.datasetName.contains(".") ? item.datasetName.substring(0, item.datasetName.lastIndexOf('.') + 1) : "";
        record.backupTable = prefix + "tp_bak_" + executionId + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        record.status = "CREATING";
        repository.createTableBackup(executionId, record);
        snapshots.put(item.snapshotName, snapshot);
        /*
         * ========================================================================
         * 步骤1：建立持久备份
         * ========================================================================
         * 1) 操作前保存备份定位信息，异常中断后仍可找到备份表。
         * 2) 备份及可选清空使用相同条件；完整复制并登记快照后，才允许清空备份范围。
         */
        try (Connection connection = datasource.getConnection()) {
            ExecutionDatabase.configure(connection, source.databaseType, timeoutSeconds);
            try (Statement statement = ExecutionDatabase.timeout(connection.createStatement(), timeoutSeconds)) {
                snapshot.inspect(connection);
                snapshot.captureIdentity(connection);
                String target = snapshot.quote(record.tableName);
                String backup = snapshot.quote(record.backupTable);
                statement.execute(snapshot.sqlServer
                        ? "SELECT source.* INTO " + backup + " FROM " + target + " source CROSS JOIN (SELECT 1 AS marker) marker" + snapshot.where()
                        : "CREATE TABLE " + backup + " AS SELECT * FROM " + target + snapshot.where());
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + backup)) {
                    rows.next();
                    record.rowCount = rows.getLong(1);
                }
                snapshot.ready = true;
                record.status = "READY";
                repository.updateTableBackup(record);
                if (Boolean.TRUE.equals(item.clearAfterBackup)) {
                    connection.setAutoCommit(false);
                    try {
                        statement.executeUpdate("DELETE FROM " + target + snapshot.where());
                        connection.commit();
                    } catch (Exception failure) {
                        connection.rollback();
                        throw failure;
                    }
                }
                return "已备份 " + record.rowCount + " 行；备份表：" + record.backupTable
                        + (record.backupCondition == null ? "；范围：整表" : "；条件：" + record.backupCondition)
                        + (Boolean.TRUE.equals(item.clearAfterBackup) ? "；备份范围内数据已清空" : "");
            }
        } catch (Exception failure) {
            record.status = snapshot.ready ? "READY" : "BACKUP_FAILED";
            record.errorMessage = failure.getMessage();
            repository.updateTableBackup(record);
            throw new IllegalStateException("备份步骤失败；备份位置：" + record.backupTable + "；" + failure.getMessage(), failure);
        }
    }

    String restore(String name) throws Exception {
        Snapshot snapshot = snapshots.get(name);
        if (snapshot == null || !snapshot.ready) throw new IllegalArgumentException("本次执行没有成功建立快照：" + name);
        try {
            snapshot.restore();
        } catch (Exception failure) {
            snapshot.record.status = "RESTORE_FAILED";
            snapshot.record.errorMessage = failure.getMessage();
            repository.updateTableBackup(snapshot.record);
            throw failure;
        }
        return "已恢复快照 " + name + "，共 " + snapshot.record.rowCount + " 行；备份保留至案例结束";
    }

    String finish() {
        String failure = restoreAll();
        return failure == null ? dropAll() : failure;
    }

    String restoreAll() {
        List<String> errors = new ArrayList<String>();
        Map<String, List<Snapshot>> tables = new LinkedHashMap<String, List<Snapshot>>();
        for (Snapshot snapshot : snapshots.values()) {
            if (!snapshot.ready) {
                // READY 登记在清空和后续业务之前；未完成的备份不用于覆盖原表。
                continue;
            }
            String key = snapshot.source.jdbcUrl + "\n" + snapshot.source.username + "\n" + snapshot.record.tableName
                    + "\n" + (snapshot.record.backupCondition == null ? "" : snapshot.record.backupCondition);
            tables.computeIfAbsent(key, ignored -> new ArrayList<Snapshot>()).add(snapshot);
        }
        /*
         * ========================================================================
         * 步骤2：单个案例收尾
         * ========================================================================
         * 1) 同表同条件还原最早成功快照；不同范围按建立顺序倒序还原，重叠范围以最早快照为准。
         * 2) 每个范围提交后保存恢复检查点；备份统一保留至案例收尾结束。
         */
        List<List<Snapshot>> groups = new ArrayList<List<Snapshot>>(tables.values());
        java.util.Collections.reverse(groups);
        for (List<Snapshot> group : groups) {
            Snapshot baseline = group.get(0);
            try {
                if (!Arrays.asList("RESTORED", "DROP_FAILED", "DROPPED").contains(baseline.record.status)) baseline.restore();
                // 恢复提交后先保存检查点；全部范围恢复结束前不删除任何备份。
                for (Snapshot snapshot : group) {
                    if (Arrays.asList("DROP_FAILED", "DROPPED").contains(snapshot.record.status)) continue;
                    snapshot.record.status = "RESTORED";
                    snapshot.record.errorMessage = null;
                    repository.updateTableBackup(snapshot.record);
                }
            } catch (Exception failure) {
                operationOutcomeUnknown |= ExecutionDatabase.outcomeUnknown(failure);
                for (Snapshot snapshot : group) {
                    snapshot.record.status = "RESTORE_FAILED";
                    snapshot.record.errorMessage = failure.getMessage();
                    try { repository.updateTableBackup(snapshot.record); }
                    catch (Exception persistenceFailure) { errors.add("保存恢复状态失败：" + persistenceFailure.getMessage()); }
                }
                errors.add(baseline.record.tableName + " 恢复失败，备份保留：" + baseline.record.backupTable + "；" + failure.getMessage());
                if (operationOutcomeUnknown) break;
                continue;
            }
        }
        return errors.isEmpty() ? null : String.join("\n", errors);
    }

    String dropAll() {
        List<String> errors = new ArrayList<String>();
        for (Snapshot snapshot : snapshots.values()) {
            if ("DROPPED".equals(snapshot.record.status)) continue;
            if (snapshot.ready && !Arrays.asList("RESTORED", "DROP_FAILED").contains(snapshot.record.status)) continue;
            try (Connection connection = snapshot.datasource.getConnection()) {
                ExecutionDatabase.configure(connection, snapshot.source.databaseType, timeoutSeconds);
                try (Statement statement = ExecutionDatabase.timeout(connection.createStatement(), timeoutSeconds)) {
                    String[] parts = snapshot.record.backupTable.split("\\.");
                    String catalog = snapshot.mysql && parts.length == 2 ? parts[0] : connection.getCatalog();
                    String schema = snapshot.mysql ? null : parts.length == 2 ? parts[0] : connection.getSchema();
                    boolean exists = false;
                    try (ResultSet tables = connection.getMetaData().getTables(catalog, schema, parts[parts.length - 1], new String[] {"TABLE"})) {
                        while (tables.next()) if (parts[parts.length - 1].equals(tables.getString("TABLE_NAME"))) exists = true;
                    }
                    if (exists) statement.execute("DROP TABLE " + snapshot.quote(snapshot.record.backupTable));
                    snapshot.record.status = "DROPPED";
                    snapshot.record.errorMessage = null;
                    repository.updateTableBackup(snapshot.record);
                }
            } catch (Exception failure) {
                operationOutcomeUnknown |= ExecutionDatabase.outcomeUnknown(failure);
                snapshot.record.status = "DROP_FAILED";
                snapshot.record.errorMessage = failure.getMessage();
                try { repository.updateTableBackup(snapshot.record); }
                catch (Exception persistenceFailure) { errors.add("保存清理状态失败：" + persistenceFailure.getMessage()); }
                errors.add("备份范围已恢复，备份清理失败：" + snapshot.record.backupTable + "；" + failure.getMessage());
                if (operationOutcomeUnknown) break;
            }
        }
        return errors.isEmpty() ? null : String.join("\n", errors);
    }

    private static final class Snapshot {
        private final PlatformModels.TestDataSource source;
        private final DataSource datasource;
        private final PlatformModels.TableBackup record;
        private final boolean sqlServer;
        private final boolean mysql;
        private final int timeoutSeconds;
        private boolean ready;
        private String columns;
        private boolean identity;

        private Snapshot(PlatformModels.TestDataSource source, DataSource datasource, int timeoutSeconds) {
            this(source, datasource, timeoutSeconds, new PlatformModels.TableBackup());
        }

        private Snapshot(PlatformModels.TestDataSource source, DataSource datasource, int timeoutSeconds, PlatformModels.TableBackup record) {
            this.source = source;
            this.datasource = datasource;
            this.record = record;
            this.sqlServer = "SQLSERVER".equals(source.databaseType);
            this.mysql = "MYSQL".equals(source.databaseType);
            this.timeoutSeconds = timeoutSeconds;
        }

        private String quote(String name) {
            String delimiter = mysql ? "`" : "\"";
            return Arrays.stream(name.split("\\.")).map(part -> delimiter + part.replace(delimiter, delimiter + delimiter) + delimiter)
                    .collect(java.util.stream.Collectors.joining("."));
        }

        private String where() {
            return record.backupCondition == null ? "" : " WHERE (" + record.backupCondition + ")";
        }

        private void inspect(Connection connection) throws SQLException {
            List<String> names = new ArrayList<String>();
            try (Statement statement = ExecutionDatabase.timeout(connection.createStatement(), timeoutSeconds); ResultSet rows = statement.executeQuery("SELECT * FROM " + quote(record.tableName) + " WHERE 1=0")) {
                ResultSetMetaData metadata = rows.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    if (sqlServer && ("timestamp".equalsIgnoreCase(metadata.getColumnTypeName(index))
                            || "rowversion".equalsIgnoreCase(metadata.getColumnTypeName(index)))) {
                        throw new IllegalArgumentException("包含 rowversion 的表不支持数据快照恢复：" + record.tableName);
                    }
                    identity |= metadata.isAutoIncrement(index);
                    names.add(quote(metadata.getColumnName(index)));
                }
            }
            columns = String.join(",", names);
            if (mysql) {
                String[] parts = record.tableName.split("\\.");
                try (java.sql.PreparedStatement query = ExecutionDatabase.timeout(connection.prepareStatement("SELECT ENGINE FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?"), timeoutSeconds)) {
                    query.setString(1, parts.length == 2 ? parts[0] : connection.getCatalog());
                    query.setString(2, parts[parts.length - 1]);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1))) {
                            throw new IllegalArgumentException("备份恢复只支持 InnoDB 普通表：" + record.tableName);
                        }
                    }
                }
            }
        }

        private void captureIdentity(Connection connection) throws SQLException {
            // 整表快照独占整张表时，持久保存自增水位；条件快照不接管全表计数器。
            if (!sqlServer || !identity || record.backupCondition != null) return;
            try (java.sql.PreparedStatement query = ExecutionDatabase.timeout(connection.prepareStatement(
                    "SELECT CONVERT(varchar(40),last_value) FROM sys.identity_columns WHERE object_id=OBJECT_ID(?)"), timeoutSeconds)) {
                query.setString(1, record.tableName);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new SQLException("未读取到自增水位：" + record.tableName);
                    record.identityStateJson = MAPPER.createObjectNode().put("lastValue", rows.getString(1)).toString();
                }
            }
        }

        private void restore() throws Exception {
            /*
             * ========================================================================
             * 步骤1：事务还原
             * ========================================================================
             * 1) 按备份条件删除当前范围，再显式字段回写全部备份行；SQL Server 自增字段使用原值。
             * 2) 整表回写的单条 SQL 至少允许执行 300 秒；锁等待仍使用业务超时。
             * 3) PostgreSQL 在删写前锁定目标表，提交或回滚后释放；此处保留备份供案例收尾。
             */
            try (Connection connection = datasource.getConnection()) {
                ExecutionDatabase.configure(connection, source.databaseType, timeoutSeconds);
                if (columns == null) inspect(connection);
                try (Statement statement = ExecutionDatabase.timeout(connection.createStatement(), Math.max(300, timeoutSeconds))) {
                    connection.setAutoCommit(false);
                    String target = quote(record.tableName);
                    try {
                        JsonNode identityValue = record.identityStateJson == null ? null : MAPPER.readTree(record.identityStateJson).get("lastValue");
                        // 1.1 在删除前排除并发写入，避免全表回写期间出现重复键或锁升级死锁。
                        if ("POSTGRESQL".equals(source.databaseType)) statement.execute("LOCK TABLE " + target + " IN EXCLUSIVE MODE");
                        // 1.2 原表删除与快照回写在同一事务内提交，异常时整体回滚。
                        if (sqlServer && identity) statement.execute("SET IDENTITY_INSERT " + target + " ON");
                        statement.executeUpdate("DELETE FROM " + target + where());
                        statement.executeUpdate("INSERT INTO " + target + " (" + columns + ") SELECT " + columns + " FROM " + quote(record.backupTable));
                        if (sqlServer && identity) statement.execute("SET IDENTITY_INSERT " + target + " OFF");
                        if (identityValue != null) {
                            // 未使用的自增列用 TRUNCATE 恢复初始状态；已有水位按原值 RESEED。
                            if (identityValue.isNull()) {
                                if (record.rowCount != 0L) throw new IllegalStateException("未初始化自增状态与备份行数不符：" + record.tableName);
                                statement.execute("TRUNCATE TABLE " + target);
                            } else {
                                String value = new java.math.BigDecimal(identityValue.asText()).toBigIntegerExact().toString();
                                statement.execute("DBCC CHECKIDENT ('" + target + "', RESEED, " + value + ") WITH NO_INFOMSGS");
                            }
                        }
                        connection.commit();
                    } catch (Exception failure) {
                        connection.rollback();
                        throw failure;
                    }
                }
            }
        }
    }
}
