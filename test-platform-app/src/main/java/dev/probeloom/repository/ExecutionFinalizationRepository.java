package dev.probeloom.repository;

import dev.probeloom.model.PlatformModels;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 分别保存执行结果和收尾检查点；收尾完成后由执行队列释放占用。 */
@Repository
public class ExecutionFinalizationRepository {
    private final JdbcTemplate jdbc;

    public ExecutionFinalizationRepository(JdbcTemplate platformJdbcTemplate) {
        this.jdbc = platformJdbcTemplate;
    }

    public Map<String, Object> load(long id) {
        return jdbc.queryForMap("SELECT status,finished_at,finalization_phase,finalization_state,finalization_deadline_at,heartbeat_at,progress_at,variables_ciphertext,remote_outcome_unknown,finalization_error FROM test_execution WHERE id=?", id);
    }

    public List<Long> candidates() {
        return jdbc.query("SELECT id FROM test_execution WHERE status IN ('RUNNING','FAILED') AND finalization_phase<>'COMPLETE' AND (finalization_state='ENDING' OR (finalization_deadline_at IS NOT NULL AND finalization_deadline_at<?) OR (finalization_state='WAITING' AND finalization_deadline_at IS NULL AND progress_at<?) OR (finalization_state<>'WAITING' AND (heartbeat_at<? OR progress_at<?))) ORDER BY heartbeat_at,id LIMIT 100",
                (rs, row) -> rs.getLong(1), Instant.now().toString(), Instant.now().minusSeconds(600).toString(), Instant.now().minusSeconds(30).toString(), Instant.now().minusSeconds(600).toString());
    }

    public void heartbeat(long id) {
        jdbc.update("UPDATE test_execution SET heartbeat_at=? WHERE id=? AND status IN ('RUNNING','FAILED') AND finalization_phase<>'COMPLETE'", Instant.now().toString(), id);
    }

    public boolean stopRequested(long id) {
        return "ENDING".equals(load(id).get("finalization_state"));
    }

    public void requestStop(long id) {
        if (expired(load(id))) requestEnd(id, "阻塞超过10分钟，自动结束执行");
    }

    public boolean expired(Map<String, Object> checkpoint) {
        String deadline = (String) checkpoint.get("finalization_deadline_at");
        String progress = (String) checkpoint.get("progress_at");
        return deadline != null ? Instant.parse(deadline).isBefore(Instant.now())
                : progress != null && Instant.parse(progress).plusSeconds(600).isBefore(Instant.now());
    }

    public boolean requestEnd(long id, String reason) {
        String now = Instant.now().toString();
        return jdbc.update("UPDATE test_execution SET status='FAILED',finished_at=CASE WHEN status='RUNNING' THEN ? ELSE finished_at END,finalization_state='ENDING',error_message=?,finalization_error=?,heartbeat_at=? WHERE id=? AND status IN ('RUNNING','FAILED') AND finalization_phase<>'COMPLETE' AND finalization_state<>'ENDING'",
                now, reason, reason, Instant.now().minusSeconds(31).toString(), id) == 1;
    }

    public void phase(long id, String phase) {
        String now = Instant.now().toString();
        jdbc.update("UPDATE test_execution SET finalization_phase=?,finalization_state=CASE WHEN finalization_state='ENDING' THEN 'ENDING' ELSE 'ACTIVE' END,finalization_error=NULL,heartbeat_at=?,progress_at=? WHERE id=? AND status IN ('RUNNING','FAILED') AND finalization_phase<>'COMPLETE'", phase, now, now, id);
    }

    public void checkpoint(long id, String variablesCiphertext, boolean remoteUnknown) {
        String now = Instant.now().toString();
        jdbc.update("UPDATE test_execution SET variables_ciphertext=?,remote_outcome_unknown=?,heartbeat_at=?,progress_at=? WHERE id=? AND status IN ('RUNNING','FAILED') AND finalization_phase<>'COMPLETE'",
                variablesCiphertext, remoteUnknown ? 1 : 0, now, now, id);
    }

    @Transactional
    public void saveStep(long id, PlatformModels.StepExecution step, String variablesCiphertext, boolean remoteUnknown) {
        jdbc.update("UPDATE step_execution SET status=?,request_body=?,response_body=?,resolved_variables_json=?,error_message=?,duration_ms=? WHERE execution_id=? AND sort_no=? AND case_end_execution=?",
                step.status, step.requestBody, step.responseBody, step.resolvedVariablesJson, step.errorMessage, step.durationMs, id, step.sortNo, step.caseEnd ? 1 : 0);
        checkpoint(id, variablesCiphertext, remoteUnknown);
        if ("FAILED".equals(step.status)) recordFailure(id, step.errorMessage == null ? "案例步骤失败，详见步骤结果" : step.errorMessage);
    }

    public void defer(long id, String state, String reason) {
        String now = Instant.now().toString();
        String deadline = (String) load(id).get("finalization_deadline_at");
        if (deadline == null) deadline = Instant.now().plusSeconds(600).toString();
        jdbc.update("UPDATE test_execution SET status='FAILED',finished_at=CASE WHEN status='RUNNING' THEN ? ELSE finished_at END,finalization_state=CASE WHEN finalization_state='ENDING' THEN 'ENDING' ELSE ? END,finalization_error=?,finalization_deadline_at=?,heartbeat_at=?,progress_at=? WHERE id=? AND status IN ('RUNNING','FAILED') AND finalization_phase<>'COMPLETE'",
                now, state, reason, deadline, now, now, id);
    }

    public void recordFailure(long id, String reason) {
        jdbc.update("UPDATE test_execution SET status='FAILED',finished_at=CASE WHEN status='RUNNING' THEN ? ELSE finished_at END,error_message=CASE WHEN finalization_state='ENDING' THEN error_message ELSE ? END WHERE id=? AND status IN ('RUNNING','FAILED') AND finalization_phase<>'COMPLETE'", Instant.now().toString(), reason, id);
    }

    public void awaitOperation(long id, String reason) {
        defer(id, "WAITING", reason);
        jdbc.update("UPDATE test_execution SET remote_outcome_unknown=1 WHERE id=? AND finalization_phase<>'COMPLETE'", id);
    }
}
