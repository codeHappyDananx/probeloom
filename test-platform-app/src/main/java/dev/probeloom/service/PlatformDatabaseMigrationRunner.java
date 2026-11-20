package dev.probeloom.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class PlatformDatabaseMigrationRunner implements ApplicationRunner {
    private final PlatformDatabaseMigrationService service;
    @Value("${platform.migration.source.mode:}") private String sourceMode;
    @Value("${platform.migration.source.path:./data/probeloom.db}") private String sourcePath;
    @Value("${platform.migration.source.jdbcUrl:}") private String sourceUrl;
    @Value("${platform.migration.source.username:}") private String sourceUser;
    @Value("${platform.migration.source.password:}") private String sourcePassword;
    @Value("${platform.migration.source.schema:}") private String sourceSchema;
    @Value("${platform.migration.target.mode:}") private String targetMode;
    @Value("${platform.migration.target.path:./data/probeloom.db}") private String targetPath;
    @Value("${platform.migration.target.jdbcUrl:}") private String targetUrl;
    @Value("${platform.migration.target.driverClassName:org.postgresql.Driver}") private String targetDriverClassName;
    @Value("${platform.migration.target.username:}") private String targetUser;
    @Value("${platform.migration.target.password:}") private String targetPassword;
    @Value("${platform.migration.target.schema:}") private String targetSchema;
    @Value("${platform.migration.clear-target:false}") private boolean clearTarget;

    public PlatformDatabaseMigrationRunner(PlatformDatabaseMigrationService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (sourceMode == null || sourceMode.trim().isEmpty()) return;
        if (targetMode == null || targetMode.trim().isEmpty()) throw new IllegalArgumentException("迁移目标数据库模式不能为空");
        System.out.println("平台数据库迁移目标模式=" + targetMode + ", URL=" + targetUrl + ", 驱动=" + targetDriverClassName);
        service.migrate(sourceMode, sourcePath, sourceUrl, sourceUser, sourcePassword, sourceSchema,
                targetMode, targetPath, targetUrl, targetDriverClassName, targetUser, targetPassword, targetSchema, clearTarget);
        System.out.println("平台数据库迁移完成");
        System.exit(0);
    }
}
