package dev.probeloom.repository;

import dev.probeloom.model.PlatformModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 平台数据库仲裁队列及资源占用；不持有业务数据库锁，不重放业务操作。 */
@Repository
public class ExecutionQueueRepository {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionQueueRepository.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ExecutionQueueRepository(JdbcTemplate platformJdbcTemplate, PlatformTransactionManager platformTransactionManager,
                                    @Value("${platform.execution.queue-transaction-timeout-seconds:120}") int timeoutSeconds) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("队列事务超时必须为正数");
        this.jdbc = platformJdbcTemplate;
        this.transaction = new TransactionTemplate(platformTransactionManager);
        this.transaction.setTimeout(timeoutSeconds);
        logger.info("平台队列事务超时：{}秒", timeoutSeconds);
    }

    public long enqueue(PlatformModels.ExecutionSubmission submission) {
        return transaction.execute(status -> {
            lockQueue();
            return persistExecution(submission);
        });
    }

    private long persistExecution(PlatformModels.ExecutionSubmission submission) {
        /*
         * ========================================================================
         * 步骤1：持久化本次执行
         * ========================================================================
         * 数据源：已校验的冻结执行输入及本次批次配置，调用方已取得队列锁。
         * 1) 执行、密文、附件引用和资源请求在调用方事务内保存。
         * 2) 全部步骤批量写入，保留原序号、选中状态及收尾标识。
         */
        logger.info("开始持久化案例执行，案例：{}", submission.caseId);
        // 1.1 核对案例和冻结输入，按本批次顺序建立执行记录。
        if (count("SELECT COUNT(*) FROM test_case WHERE id=?", submission.caseId) != 1) throw new IllegalArgumentException("案例不存在：" + submission.caseId);
        if (submission.caseKey == null || !submission.caseKey.startsWith("CASE:") || submission.snapshotCiphertext == null) {
            throw new IllegalArgumentException("执行快照和案例排他标识不能为空");
        }
        String now = Instant.now().toString();
        long id = insert("INSERT INTO test_execution(case_id,group_id,environment_id,target_action_id,trigger_type,status,submitted_by,submitted_by_name,queued_at,batch_id,batch_position) VALUES (?,?,?,?,?,'QUEUED',?,?,?,?,?)",
                submission.caseId, submission.groupId, submission.environmentId, submission.targetActionId, submission.triggerType,
                submission.submittedBy, submission.submittedByName, now, submission.batchId, submission.batchPosition);
        // 1.2 密文只保存到执行快照表，列表和详情不返回。
        jdbc.update("INSERT INTO execution_snapshot(execution_id,snapshot_ciphertext) VALUES (?,?)", id, submission.snapshotCiphertext);
        for (Long assetId : submission.assetIds) jdbc.update("INSERT INTO execution_asset_reference(execution_id,asset_id) VALUES (?,?)", id, assetId);
        jdbc.update("INSERT INTO execution_resource_request(execution_id,resource_key) VALUES (?,?)", id, submission.caseKey);
        // 1.3 初始化全部步骤，未勾选步骤的状态由提交入口明确给出。
        List<Object[]> steps = new ArrayList<Object[]>();
        for (PlatformModels.StepExecution step : submission.steps) {
            steps.add(new Object[] {id, step.sortNo, step.caseEnd ? 1 : 0, step.status, step.errorMessage,
                    submission.statisticsInterfaceKeys.get(step.sortNo)});
        }
        jdbc.batchUpdate("INSERT INTO step_execution(execution_id,sort_no,case_end_execution,status,error_message,statistics_interface_key) VALUES (?,?,?,?,?,?)",
                steps, new int[] {Types.BIGINT, Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
        logger.info("案例执行持久化完成，执行：{}，步骤：{}", id, submission.steps.size());
        return id;
    }

    public List<Long> enqueueBatch(List<PlatformModels.ExecutionSubmission> submissions, int parallelism) {
        return transaction.execute(status -> {
            /*
             * ========================================================================
             * 步骤1：原子提交批次及冻结执行
             * ========================================================================
             * 数据源：按勾选顺序准备好的冻结输入。
             * 1) 批次并发数及案例顺序同事务保存，提交前调度器不可见。
             * 2) 整批只取得一次队列锁，各案例步骤批量写入。
             */
            logger.info("开始提交执行批次，案例数：{}，并发数：{}", submissions.size(), parallelism);
            lockQueue();
            long batchId = insert("INSERT INTO execution_batch(parallelism) VALUES (?)", parallelism);
            List<Long> ids = new ArrayList<Long>();
            for (int index = 0; index < submissions.size(); index++) {
                // 1.1 按勾选顺序保存执行，共用本批次的事务和队列锁。
                PlatformModels.ExecutionSubmission submission = submissions.get(index);
                submission.batchId = batchId;
                submission.batchPosition = index;
                ids.add(persistExecution(submission));
            }
            logger.info("执行批次提交完成，批次：{}，案例数：{}", batchId, ids.size());
            return ids;
        });
    }

    public PlatformModels.ClaimedExecution claimNext() {
        return transaction.execute(status -> {
            /*
             * ========================================================================
             * 步骤1：领取可执行案例
             * ========================================================================
             * 1) 仲裁行锁仅覆盖平台库短事务，不跨业务执行持锁。
             * 2) 批次名额由数据库统计；串行批次先完成前序案例，其他批次可继续领取。
             */
            lockQueue();
            // 1.1 数据库按占用主键和更早请求筛选，不在 JVM 推断 SQL 影响范围。
            List<Long> candidates = jdbc.query("SELECT e.id FROM test_execution e JOIN execution_snapshot s ON s.execution_id=e.id LEFT JOIN execution_batch b ON b.id=e.batch_id WHERE e.status='QUEUED'"
                    + " AND (e.batch_id IS NULL OR (SELECT COUNT(*) FROM test_execution active WHERE active.batch_id=e.batch_id AND (active.status='RUNNING' OR (active.status='FAILED' AND active.progress_at IS NOT NULL AND active.finalization_phase<>'COMPLETE')))<b.parallelism)"
                    + " AND (e.batch_id IS NULL OR b.parallelism>1 OR NOT EXISTS (SELECT 1 FROM test_execution previous WHERE previous.batch_id=e.batch_id AND previous.batch_position<e.batch_position AND (previous.status IN ('QUEUED','RUNNING') OR (previous.status='FAILED' AND previous.progress_at IS NOT NULL AND previous.finalization_phase<>'COMPLETE'))))"
                    + " AND NOT EXISTS (SELECT 1 FROM execution_resource_request r JOIN execution_resource_owner o ON o.resource_key=r.resource_key WHERE r.execution_id=e.id)"
                    + " AND NOT EXISTS (SELECT 1 FROM execution_resource_request mine JOIN execution_resource_request earlier ON earlier.resource_key=mine.resource_key JOIN test_execution p ON p.id=earlier.execution_id WHERE mine.execution_id=e.id AND p.id<e.id AND p.status='QUEUED')"
                    + " ORDER BY e.id LIMIT 1", (rs, row) -> rs.getLong(1));
            if (candidates.isEmpty()) return null;
            long id = candidates.get(0);
            logger.info("开始领取案例执行：{}", id);
            // 1.2 占用主键是最终互斥约束，状态与全部 key 原子提交。
            jdbc.update("INSERT INTO execution_resource_owner(resource_key,execution_id) SELECT resource_key,execution_id FROM execution_resource_request WHERE execution_id=?", id);
            String now = Instant.now().toString();
            jdbc.update("UPDATE test_execution SET status='RUNNING',started_at=?,finalization_phase='BUSINESS',finalization_state='ACTIVE',heartbeat_at=?,progress_at=? WHERE id=? AND status='QUEUED'", now, now, now, id);
            PlatformModels.ClaimedExecution claim = new PlatformModels.ClaimedExecution();
            claim.id = id;
            claim.snapshotCiphertext = jdbc.queryForObject("SELECT snapshot_ciphertext FROM execution_snapshot WHERE execution_id=?", String.class, id);
            logger.info("案例执行领取完成：{}", id);
            return claim;
        });
    }

    public String snapshot(long executionId) {
        return jdbc.queryForObject("SELECT snapshot_ciphertext FROM execution_snapshot WHERE execution_id=?", String.class, executionId);
    }

    public void complete(long id, String status, String error) {
        if (!"PASSED".equals(status) && !"WARN".equals(status) && !"FAILED".equals(status)) throw new IllegalArgumentException("执行终态必须为 PASSED、WARN 或 FAILED");
        transaction.execute(transactionStatus -> {
            lockQueue();
            if (jdbc.update("UPDATE test_execution SET status=?,finished_at=CASE WHEN status='RUNNING' THEN ? ELSE finished_at END,error_message=?,finalization_phase='COMPLETE',finalization_state='COMPLETE',finalization_error=NULL WHERE id=? AND status IN ('RUNNING','FAILED') AND finalization_phase<>'COMPLETE'",
                    status, Instant.now().toString(), error, id) != 1) {
                throw new IllegalStateException("执行已完成或不处于可收尾状态：" + id);
            }
            jdbc.update("UPDATE step_execution SET status=CASE WHEN status='RUNNING' THEN 'FAILED' ELSE 'SKIPPED' END,error_message=? WHERE execution_id=? AND status IN ('RUNNING','PENDING')", error, id);
            jdbc.update("DELETE FROM execution_resource_owner WHERE execution_id=?", id);
            return null;
        });
    }

    public boolean endQueued(long id, String reason) {
        return transaction.execute(status -> {
            lockQueue();
            if (jdbc.update("UPDATE test_execution SET status='FAILED',finished_at=?,error_message=?,finalization_phase='COMPLETE',finalization_state='COMPLETE' WHERE id=? AND status='QUEUED'", Instant.now().toString(), reason, id) != 1) return false;
            jdbc.update("UPDATE step_execution SET status='SKIPPED',error_message=? WHERE execution_id=? AND status='PENDING'", reason, id);
            return true;
        });
    }

    public void markDispatchFailed(long id, String error) {
        transaction.execute(status -> {
            lockQueue();
            // 线程池尚未接收任务，业务未开始；仅释放本次领取的 key。
            if (jdbc.update("UPDATE test_execution SET status='FAILED',finished_at=?,error_message=?,finalization_phase='COMPLETE',finalization_state='COMPLETE' WHERE id=? AND status='RUNNING' AND finalization_phase='BUSINESS'", Instant.now().toString(), error, id) != 1) throw new IllegalStateException("执行未处于已领取状态：" + id);
            jdbc.update("UPDATE step_execution SET status='SKIPPED',error_message=? WHERE execution_id=? AND status='PENDING'", error, id);
            jdbc.update("DELETE FROM execution_resource_owner WHERE execution_id=?", id);
            return null;
        });
    }

    public void recoverInterrupted() {
        transaction.execute(status -> {
            /*
             * ========================================================================
             * 步骤1：检查执行队列与待收尾记录
             * ========================================================================
             * 数据源：平台持久队列、资源占用和步骤状态。
             * 1) 未完成收尾的执行保留恢复依据和案例占用，由监督器处理。
             * 2) 缺少冻结输入的 QUEUED 终止；其余排队记录由 dispatcher 领取。
             */
            logger.info("开始检查执行队列与待收尾记录");
            lockQueue();
            // 1.1 排队记录必须具备冻结输入；缺少检查点的运行记录等待核查。
            jdbc.update("UPDATE step_execution SET status='SKIPPED',error_message=? WHERE status IN ('RUNNING','PENDING') AND execution_id IN (SELECT id FROM test_execution WHERE status='QUEUED' AND NOT EXISTS (SELECT 1 FROM execution_snapshot s WHERE s.execution_id=test_execution.id))", "排队任务缺少执行快照");
            jdbc.update("UPDATE test_execution SET status='FAILED',finished_at=?,error_message=? WHERE status='QUEUED' AND NOT EXISTS (SELECT 1 FROM execution_snapshot s WHERE s.execution_id=test_execution.id)", Instant.now().toString(), "排队任务缺少执行快照，请重新提交");
            int interrupted = jdbc.update("UPDATE test_execution SET finalization_error=? WHERE status='RUNNING' AND heartbeat_at IS NULL AND finalization_state='WAITING'", "缺少执行检查点，需核查原操作和恢复依据");
            jdbc.update("UPDATE test_execution SET status='FAILED',finished_at=? WHERE status='RUNNING' AND progress_at IS NOT NULL AND finalization_state IN ('WAITING','PENDING')", Instant.now().toString());
            // 1.2 失败但尚未恢复的记录继续持有案例占用。
            jdbc.update("DELETE FROM execution_resource_owner WHERE execution_id IN (SELECT id FROM test_execution WHERE status<>'RUNNING' AND NOT (status='FAILED' AND progress_at IS NOT NULL AND finalization_phase<>'COMPLETE'))");
            logger.info("执行队列检查完成，需核查记录：{}", interrupted);
            return null;
        });
    }

    private void lockQueue() {
        if (jdbc.update("UPDATE execution_queue_control SET id=id WHERE id=1") != 1) throw new IllegalStateException("执行队列尚未初始化");
    }

    private long count(String sql, Object... arguments) { return jdbc.queryForObject(sql, Long.class, arguments); }

    private long insert(String sql, Object... arguments) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[] {"id"});
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
