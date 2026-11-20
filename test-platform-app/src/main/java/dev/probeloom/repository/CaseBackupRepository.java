package dev.probeloom.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.probeloom.model.CaseBackupModels.Config;
import dev.probeloom.model.CaseBackupModels.Run;
import dev.probeloom.service.CryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class CaseBackupRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CryptoService crypto;

    public CaseBackupRepository(JdbcTemplate platformJdbcTemplate, ObjectMapper mapper, CryptoService crypto) {
        this.jdbc = platformJdbcTemplate;
        this.mapper = mapper;
        this.crypto = crypto;
    }

    public Config config(boolean includePassword) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT config_json,password_ciphertext FROM case_backup_config WHERE id=1");
        if (rows.isEmpty()) return new Config();
        try {
            Config value = mapper.readValue((String) rows.get(0).get("config_json"), Config.class);
            String ciphertext = (String) rows.get(0).get("password_ciphertext");
            value.passwordConfigured = ciphertext != null;
            if (includePassword) value.password = crypto.decrypt(ciphertext);
            return value;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取案例备份配置失败", e);
        }
    }

    @Transactional
    public void save(Config value) {
        try {
            String json = mapper.writeValueAsString(value);
            String ciphertext = crypto.encrypt(value.password);
            if (jdbc.update("UPDATE case_backup_config SET config_json=?,password_ciphertext=? WHERE id=1", json, ciphertext) == 0) {
                jdbc.update("INSERT INTO case_backup_config(id,config_json,password_ciphertext) VALUES (1,?,?)", json, ciphertext);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("保存案例备份配置失败", e);
        }
    }

    public boolean running() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM case_backup_run WHERE status='RUNNING'", Integer.class) != 0;
    }

    @Transactional
    public Run start(Config config, String trigger, String slot) {
        if (running()) throw new IllegalStateException("已有案例备份正在执行");
        if (slot != null && jdbc.update("UPDATE case_backup_config SET last_scheduled_slot=? WHERE id=1 AND (last_scheduled_slot IS NULL OR last_scheduled_slot<>?)", slot, slot) == 0) return null;
        String now = Instant.now().toString();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO case_backup_run(trigger_type,status,repository_type,repository_url,started_at) VALUES (?,'RUNNING',?,?,?)", new String[] { "id" });
            statement.setString(1, trigger);
            statement.setString(2, config.repositoryType);
            statement.setString(3, config.repositoryUrl);
            statement.setString(4, now);
            return statement;
        }, key);
        return jdbc.queryForObject("SELECT * FROM case_backup_run WHERE id=?", runMapper(), key.getKey().longValue());
    }

    public void finish(long id, String status, int count, String revision, String error) {
        jdbc.update("UPDATE case_backup_run SET status=?,finished_at=?,case_count=?,revision=?,error_message=? WHERE id=? AND status='RUNNING'", status, Instant.now().toString(), count, revision, error, id);
    }

    public void recoverInterrupted() {
        jdbc.update("UPDATE case_backup_run SET status='FAILED',finished_at=?,error_message='平台重启，备份执行中断；请核对仓库提交版本后重新备份' WHERE status='RUNNING'", Instant.now().toString());
    }

    public List<Run> recentRuns() {
        return jdbc.query("SELECT * FROM case_backup_run ORDER BY id DESC LIMIT 20", runMapper());
    }

    private RowMapper<Run> runMapper() {
        return (rs, row) -> {
            Run value = new Run();
            value.id = rs.getLong("id");
            value.triggerType = rs.getString("trigger_type");
            value.status = rs.getString("status");
            value.repositoryType = rs.getString("repository_type");
            value.repositoryUrl = rs.getString("repository_url");
            value.startedAt = rs.getString("started_at");
            value.finishedAt = rs.getString("finished_at");
            value.caseCount = rs.getInt("case_count");
            value.revision = rs.getString("revision");
            value.errorMessage = rs.getString("error_message");
            return value;
        };
    }
}
