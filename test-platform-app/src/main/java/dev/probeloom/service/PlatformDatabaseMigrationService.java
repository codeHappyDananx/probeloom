package dev.probeloom.service;

import dev.probeloom.repository.PlatformSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class PlatformDatabaseMigrationService {
    private static final List<String> TABLES = Arrays.asList(
            "platform_user", "session_token", "platform_number_sequence", "case_group",
            "test_environment", "test_datasource", "fixture_dataset",
            "test_case", "test_case_recycle_bin", "test_case_action", "test_file_asset", "test_case_upload",
            "schedule_plan", "case_backup_config", "case_backup_run", "execution_batch", "test_execution", "step_execution", "assertion_execution", "table_backup",
            "execution_snapshot", "execution_asset_reference", "execution_resource_request", "execution_resource_owner");

    public void migrate(String sourceMode, String sourcePath, String sourceUrl, String sourceUser, String sourcePassword, String sourceSchema,
                         String targetMode, String targetPath, String targetUrl, String targetDriverClassName, String targetUser, String targetPassword, String targetSchema,
                         boolean clearTarget) {
        if (!isJdbcMode(sourceMode) && !isJdbcMode(targetMode)
                && Paths.get(sourcePath).toAbsolutePath().normalize().equals(Paths.get(targetPath).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("迁移源和目标不能是同一个 SQLite 文件");
        }
        backupSqliteTarget(targetMode, targetPath);
        JdbcTemplate source = new JdbcTemplate(dataSource(sourceMode, sourcePath, sourceUrl, sourceUser, sourcePassword, sourceSchema));
        JdbcTemplate target = new JdbcTemplate(dataSource(targetMode, targetPath, targetUrl, targetDriverClassName, targetUser, targetPassword, targetSchema));
        boolean targetPostgres = isJdbcMode(targetMode) || (targetUrl != null && targetUrl.toLowerCase().startsWith("jdbc:"));
        System.out.println("平台数据库迁移建表模式=" + targetPostgres + ", targetMode=" + targetMode + ", targetUrl=" + targetUrl);
        PlatformSchema.initialize(target, targetPostgres);
        if (clearTarget) clearTarget(target);
        for (String table : TABLES) if (!"session_token".equals(table)) copyTable(source, target, table);
        if (targetPostgres) resetSequences(target);
    }

    private void copyTable(JdbcTemplate source, JdbcTemplate target, String table) {
        List<String> columns = columns(source, table);
        if (columns.isEmpty()) return;
        String names = String.join(",", columns);
        List<Map<String, Object>> rows = source.queryForList("SELECT " + names + " FROM " + table);
        int before = target.queryForObject("SELECT COUNT(1) FROM " + table, Integer.class);
        try {
            int batchSize = 100;
            for (int start = 0; start < rows.size(); start += batchSize) {
                int end = Math.min(start + batchSize, rows.size());
                String rowPlaceholders = "(" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
                String sql = "INSERT INTO " + table + " (" + names + ") VALUES " + String.join(",", Collections.nCopies(end - start, rowPlaceholders));
                List<Object> parameters = new ArrayList<Object>((end - start) * columns.size());
                for (int rowIndex = start; rowIndex < end; rowIndex++) {
                    Map<String, Object> row = rows.get(rowIndex);
                    for (String column : columns) parameters.add(row.get(column));
                }
                target.update(sql, parameters.toArray());
            }
        } catch (Exception e) {
            throw new IllegalStateException("迁移数据冲突，表：" + table, e);
        }
        int after = target.queryForObject("SELECT COUNT(1) FROM " + table, Integer.class);
        if (after - before != rows.size()) throw new IllegalStateException("迁移行数校验失败，表：" + table);
    }

    private void clearTarget(JdbcTemplate target) {
        List<String> reversed = new ArrayList<String>(TABLES);
        Collections.reverse(reversed);
        for (String table : reversed) target.update("DELETE FROM " + table);
    }

    private List<String> columns(JdbcTemplate jdbc, String table) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            List<String> result = new ArrayList<String>();
            try (java.sql.ResultSet rs = metadata.getColumns(null, null, table, null)) {
                while (rs.next()) result.add(rs.getString("COLUMN_NAME"));
            }
            if (result.isEmpty()) {
                try (java.sql.ResultSet rs = metadata.getColumns(null, null, table.toUpperCase(), null)) {
                    while (rs.next()) result.add(rs.getString("COLUMN_NAME"));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("读取表结构失败: " + table, e);
        }
    }

    private void resetSequences(JdbcTemplate jdbc) {
        for (String table : TABLES) {
            if ("session_token".equals(table) || "platform_number_sequence".equals(table) || "case_backup_config".equals(table) || "test_case_recycle_bin".equals(table) || "test_case_upload".equals(table)
                    || "execution_snapshot".equals(table) || "execution_asset_reference".equals(table) || "execution_resource_request".equals(table) || "execution_resource_owner".equals(table)) continue;
            jdbc.queryForObject("SELECT setval(pg_get_serial_sequence(?, 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM " + table, Long.class, table);
        }
    }

    private void backupSqliteTarget(String mode, String path) {
        if ("jdbc".equalsIgnoreCase(mode) || "server".equalsIgnoreCase(mode) || path == null || path.trim().isEmpty()) return;
        try {
            Path target = Paths.get(path).toAbsolutePath().normalize();
            if (Files.exists(target)) {
                String suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                Files.copy(target, Paths.get(target.toString() + ".bak-" + suffix));
            }
        } catch (Exception e) { throw new IllegalStateException("SQLite 目标库备份失败", e); }
    }

    private boolean isPostgres(JdbcTemplate jdbc) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            return product.contains("postgres");
        } catch (Exception e) { throw new IllegalStateException("无法识别数据库类型", e); }
    }

    private DataSource dataSource(String mode, String path, String url, String username, String password, String schema) {
        return dataSource(mode, path, url, "org.postgresql.Driver", username, password, schema);
    }

    private DataSource dataSource(String mode, String path, String url, String driverClassName, String username, String password, String schema) {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        if (!isJdbcMode(mode)) {
            dataSource.setDriverClass(org.sqlite.JDBC.class);
            Path databasePath = Paths.get(path).toAbsolutePath().normalize();
            dataSource.setUrl("jdbc:sqlite:" + databasePath);
            org.sqlite.SQLiteConfig sqlite = new org.sqlite.SQLiteConfig();
            sqlite.enforceForeignKeys(true);
            dataSource.setConnectionProperties(sqlite.toProperties());
            try { return new SingleConnectionDataSource(dataSource.getConnection(), true); }
            catch (Exception e) { throw new IllegalStateException("无法连接 SQLite 数据库", e); }
        }
        if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("迁移 JDBC 地址不能为空");
        try { dataSource.setDriverClass(Class.forName(driverClassName).asSubclass(java.sql.Driver.class)); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("平台数据库驱动未加载: " + driverClassName, e); }
        String separator = url.contains("?") ? "&" : "?";
        dataSource.setUrl(schema == null || schema.trim().isEmpty() ? url : url + separator + "currentSchema=" + schema);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        try { return new SingleConnectionDataSource(dataSource.getConnection(), true); }
        catch (Exception e) { throw new IllegalStateException("无法连接平台数据库", e); }
    }

    private boolean isJdbcMode(String mode) {
        return "jdbc".equalsIgnoreCase(mode) || "server".equalsIgnoreCase(mode);
    }
}
