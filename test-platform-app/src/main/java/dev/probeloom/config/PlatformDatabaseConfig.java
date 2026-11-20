package dev.probeloom.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableTransactionManagement
public class PlatformDatabaseConfig {
    @Bean
    public DataSource platformDataSource(
            PlatformStorage storage,
            @Value("${platform.datasource.default.driverClassName:org.postgresql.Driver}") String driverClassName,
            @Value("${platform.datasource.default.jdbcUrl:}") String jdbcUrl,
            @Value("${platform.datasource.default.username:}") String username,
            @Value("${platform.datasource.default.password:}") String password,
            @Value("${platform.datasource.default.schema:}") String schema) {
        if (!storage.isJdbc()) {
            SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
            dataSource.setDriverClass(org.sqlite.JDBC.class);
            dataSource.setUrl("jdbc:sqlite:" + storage.getDatabasePath().toString());
            org.sqlite.SQLiteConfig sqlite = new org.sqlite.SQLiteConfig();
            sqlite.enforceForeignKeys(true);
            dataSource.setConnectionProperties(sqlite.toProperties());
            return dataSource;
        }
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) throw new IllegalStateException("平台 JDBC 地址不能为空");
        if (schema != null && !schema.trim().isEmpty() && !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("平台数据库 schema 只允许字母、数字和下划线");
        }
        try {
            Class.forName(driverClassName).asSubclass(java.sql.Driver.class);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("平台数据库驱动未加载：" + driverClassName, e);
        }
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        HikariConfig config = new HikariConfig();
        config.setPoolName("platform-db");
        config.setMinimumIdle(1);
        config.setMaximumPoolSize(10);
        config.setMaxLifetime(TimeUnit.MINUTES.toMillis(20));
        config.setKeepaliveTime(TimeUnit.MINUTES.toMillis(5));
        config.setDriverClassName(driverClassName);
        config.setJdbcUrl(schema == null || schema.trim().isEmpty()
                ? jdbcUrl
                : jdbcUrl + separator + "currentSchema=" + schema);
        config.setUsername(username);
        config.setPassword(password);
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate platformJdbcTemplate(DataSource platformDataSource,
                                            @Value("${platform.execution.timeout-seconds:30}") int timeoutSeconds) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("平台数据库语句超时必须为正数");
        JdbcTemplate jdbc = new JdbcTemplate(platformDataSource) {
            @Override
            protected void applyStatementSettings(Statement statement) throws SQLException {
                super.applyStatementSettings(statement);
                // 事务剩余时限和单条 SQL 上限取较小值，避免事务延长后放大锁等待。
                statement.setQueryTimeout(Math.min(timeoutSeconds, statement.getQueryTimeout()));
            }
        };
        jdbc.setQueryTimeout(timeoutSeconds);
        return jdbc;
    }

    @Bean
    public PlatformTransactionManager platformTransactionManager(DataSource platformDataSource) {
        return new DataSourceTransactionManager(platformDataSource);
    }
}
