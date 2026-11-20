package dev.probeloom.service;

import dev.probeloom.repository.PlatformRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import dev.probeloom.repository.ExecutionQueueRepository;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/** 接收执行请求前完成平台库初始化、中断任务收尾及未完成备份检查。 */
@Component
public class PlatformStartupCheck {
    private static final Logger logger = LoggerFactory.getLogger(PlatformStartupCheck.class);
    private final PlatformRepository repository;
    private final ExecutionQueueRepository queue;
    private final String migrationSourceMode;

    public PlatformStartupCheck(PlatformRepository repository, ExecutionQueueRepository queue,
                                @Value("${platform.migration.source.mode:}") String migrationSourceMode) {
        this.repository = repository;
        this.queue = queue;
        this.migrationSourceMode = migrationSourceMode;
    }

    @PostConstruct
    public void initialize() {
        if (!migrationSourceMode.trim().isEmpty()) return;

        /*
         * ========================================================================
         * 步骤1：初始化平台库结构
         * ========================================================================
         * 1) 复用平台库迁移入口，确保执行表及字段已存在。
         * 2) 数据库迁移命令由专用入口处理，不执行任务状态收尾。
         */
        logger.info("开始检查平台库结构");
        // 1.1 先完成结构初始化，再检查历史执行状态。
        repository.migrate();
        logger.info("平台库结构检查完成");

        /*
         * ========================================================================
         * 步骤2：核查执行队列与收尾依据
         * ========================================================================
         * 数据源：平台库 test_execution、step_execution。
         * 1) 未完成收尾的运行记录继续持有案例占用，由案例监督器接续。
         * 2) 有冻结输入的排队任务继续调度，缺少冻结输入的排队记录终止。
         */
        logger.info("开始检查服务重启遗留任务");
        queue.recoverInterrupted();
        logger.info("服务重启遗留任务检查完成");

        /*
         * ========================================================================
         * 步骤3：检测未完成还原或清理的备份
         * ========================================================================
         * 数据源：平台库 table_backup 中尚未删除的备份记录。
         * 1) 在启动调度前列出执行编号、原表、备份位置及错误，供核对处理。
         * 2) 自动接续依据收尾检查点和案例执行锁判断，备份告警不代替恢复结果。
         */
        logger.info("开始检查未完成还原或清理的备份");
        List<Map<String, Object>> backups = repository.listUnfinishedTableBackups();
        for (Map<String, Object> backup : backups) {
            logger.warn("发现未完成还原或清理的备份，执行：{}，数据源：{}，原表：{}，备份表：{}，条件：{}，状态：{}，错误：{}",
                    backup.get("execution_id"), backup.get("datasource_key"), backup.get("table_name"),
                    backup.get("backup_table"), backup.get("backup_condition"), backup.get("status"), backup.get("error_message"));
        }
        logger.info("未完成还原或清理的备份检查完成，共：{}", backups.size());
    }
}
