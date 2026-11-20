package dev.probeloom.service;

import dev.probeloom.repository.ExecutionFinalizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 扫描案例执行记录；可随平台运行，也可用 supervisor.only 独立进程运行。 */
@Component
@DependsOn("platformStartupCheck")
public class CaseExecutionSupervisor {
    private static final Logger logger = LoggerFactory.getLogger(CaseExecutionSupervisor.class);
    private final ExecutionService executions;
    private final ExecutionFinalizationRepository finalizations;
    private final Set<Long> recovering = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scanner = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "case-supervisor"));
    private ExecutorService workers;
    @Value("${platform.execution.parallelism:4}") private int parallelism;
    @Value("${platform.migration.source.mode:}") private String migrationSourceMode;

    public CaseExecutionSupervisor(ExecutionService executions, ExecutionFinalizationRepository finalizations) {
        this.executions = executions;
        this.finalizations = finalizations;
    }

    @PostConstruct
    public void start() {
        if (!migrationSourceMode.trim().isEmpty()) return;
        workers = Executors.newFixedThreadPool(parallelism, r -> new Thread(r, "case-finalizer"));
        scanner.scheduleWithFixedDelay(this::scan, 5, 5, TimeUnit.SECONDS);
    }

    private void scan() {
        /*
         * ========================================================================
         * 步骤1：扫描并派发未完成收尾的案例
         * ========================================================================
         * 数据源：平台库执行心跳、进展时间与收尾检查点。
         * 1) 心跳失联30秒或10分钟无进展时检查，案例锁负责排除原执行者。
         * 2) 只派发收尾任务，不重放业务；等待超时和手动结束均进入备份还原。
         */
        try {
            executions.heartbeatCases();
            for (Long id : finalizations.candidates()) {
                if (recovering.size() >= parallelism) break;
                if (!recovering.add(id)) continue;
                logger.info("开始检查案例收尾，执行：{}", id);
                workers.execute(() -> {
                    try { executions.recoverCase(id); }
                    catch (Exception e) { logger.error("案例收尾检查失败，保留检查点，执行：{}", id, e); }
                    finally { recovering.remove(id); }
                });
                logger.info("案例收尾检查已派发，执行：{}", id);
            }
        } catch (Exception e) {
            logger.error("案例监督器扫描失败，下一轮继续", e);
        }
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        scanner.shutdown();
        while (!scanner.awaitTermination(1, TimeUnit.SECONDS)) { /* 等待扫描派发完成。 */ }
        if (workers == null) return;
        workers.shutdown();
        while (!workers.awaitTermination(30, TimeUnit.SECONDS)) logger.info("等待案例监督器收尾完成，任务数：{}", recovering.size());
    }
}
