package dev.probeloom.repository;

import org.springframework.jdbc.core.JdbcTemplate;

/** ProbeLoom 初始库结构，支持 SQLite 与 PostgreSQL。 */
public final class PlatformSchema {
    private PlatformSchema() { }

    public static void initialize(JdbcTemplate jdbc, boolean postgres) {
        String id = postgres ? "BIGSERIAL" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String blob = postgres ? "BYTEA" : "BLOB";
        jdbc.execute("CREATE TABLE IF NOT EXISTS case_backup_config (id INTEGER PRIMARY KEY CHECK(id=1), config_json TEXT NOT NULL, password_ciphertext TEXT, last_scheduled_slot TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS case_backup_run (id " + id + (postgres ? " PRIMARY KEY" : "") + ", trigger_type TEXT NOT NULL, status TEXT NOT NULL, repository_type TEXT NOT NULL, repository_url TEXT NOT NULL, started_at TEXT NOT NULL, finished_at TEXT, case_count INTEGER NOT NULL DEFAULT 0, revision TEXT, error_message TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS platform_user (id " + id + (postgres ? " PRIMARY KEY" : "") + ", username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS session_token (token TEXT PRIMARY KEY, user_id INTEGER NOT NULL, expire_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS platform_number_sequence (scope TEXT PRIMARY KEY, next_value INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS case_group (id " + id + (postgres ? " PRIMARY KEY" : "") + ", group_no INTEGER NOT NULL UNIQUE, name TEXT NOT NULL, parent_id INTEGER, sort_no INTEGER, CHECK (parent_id IS NULL OR parent_id <> id), FOREIGN KEY(parent_id) REFERENCES case_group(id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_environment (id " + id + (postgres ? " PRIMARY KEY" : "") + ", name TEXT NOT NULL UNIQUE, refapp TEXT NOT NULL, is_default INTEGER NOT NULL DEFAULT 0, connections_json TEXT NOT NULL DEFAULT '[]')");
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_datasource (id " + id + (postgres ? " PRIMARY KEY" : "") + ", name TEXT NOT NULL UNIQUE, resource_key TEXT NOT NULL UNIQUE, database_type TEXT NOT NULL, jdbc_url TEXT NOT NULL, username TEXT NOT NULL, password_ciphertext TEXT, access_mode TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fixture_dataset (id " + id + (postgres ? " PRIMARY KEY" : "") + ", name TEXT NOT NULL UNIQUE, datasource_key TEXT NOT NULL, variables_json TEXT, prepare_sql TEXT, cleanup_sql TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_case (id " + id + (postgres ? " PRIMARY KEY" : "") + ", group_id INTEGER, case_no INTEGER NOT NULL, name TEXT NOT NULL, description TEXT, enabled INTEGER NOT NULL DEFAULT 1, recycled INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_case_recycle_bin (case_id INTEGER PRIMARY KEY, deleted_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_case_action (id " + id + (postgres ? " PRIMARY KEY" : "") + ", case_id INTEGER NOT NULL, action_type TEXT NOT NULL, name TEXT NOT NULL, sort_no INTEGER NOT NULL, dataset_name TEXT, datasource_key TEXT, variables_json TEXT, prepare_sql TEXT, cleanup_sql TEXT, application_name TEXT, request_body TEXT, assertions_json TEXT, query_sql TEXT, expected_rows_json TEXT, expected_row_count BIGINT, compare_mode TEXT, core_assertion INTEGER NOT NULL DEFAULT 0, response_variables_json TEXT, run_on_case_end INTEGER NOT NULL DEFAULT 0, expected_result TEXT, description TEXT, snapshot_name TEXT, clear_after_backup INTEGER, snapshot_rows_json TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_file_asset (id " + id + (postgres ? " PRIMARY KEY" : "") + ", file_name TEXT NOT NULL, content_type TEXT NOT NULL, content_sha256 TEXT NOT NULL UNIQUE, content " + blob + " NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_case_upload (action_id INTEGER PRIMARY KEY, asset_id INTEGER NOT NULL, connection_key TEXT NOT NULL, path TEXT NOT NULL, file_field TEXT NOT NULL DEFAULT 'file', headers_json TEXT, form_fields_json TEXT, assertions_json TEXT, response_variables_json TEXT, attachment_mode TEXT NOT NULL DEFAULT 'BASE64', attachment_sheet_name TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS schedule_plan (id " + id + (postgres ? " PRIMARY KEY" : "") + ", group_id INTEGER NOT NULL, environment_id INTEGER, name TEXT NOT NULL, cron_expression TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS execution_batch (id " + id + (postgres ? " PRIMARY KEY" : "") + ", parallelism INTEGER NOT NULL CHECK(parallelism>=1))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_execution (id " + id + (postgres ? " PRIMARY KEY" : "") + ", case_id INTEGER, group_id INTEGER, environment_id INTEGER, target_action_id INTEGER, trigger_type TEXT NOT NULL, status TEXT NOT NULL, started_at TEXT, finished_at TEXT, error_message TEXT, message TEXT, finalization_phase TEXT NOT NULL DEFAULT 'BUSINESS', finalization_state TEXT NOT NULL DEFAULT 'WAITING', heartbeat_at TEXT, progress_at TEXT, variables_ciphertext TEXT, remote_outcome_unknown INTEGER NOT NULL DEFAULT 0, finalization_error TEXT, operation_confirmation TEXT, finalization_deadline_at TEXT, batch_id BIGINT REFERENCES execution_batch(id), batch_position INTEGER CHECK(batch_position>=0), submitted_by BIGINT, submitted_by_name TEXT, queued_at TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS step_execution (id " + id + (postgres ? " PRIMARY KEY" : "") + ", execution_id INTEGER NOT NULL, sort_no INTEGER NOT NULL, status TEXT NOT NULL, request_body TEXT, response_body TEXT, resolved_variables_json TEXT, error_message TEXT, duration_ms INTEGER, case_end_execution INTEGER NOT NULL DEFAULT 0, statistics_interface_key TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS assertion_execution (id " + id + (postgres ? " PRIMARY KEY" : "") + ", execution_id INTEGER NOT NULL, step_sort_no INTEGER, assertion_type TEXT NOT NULL, status TEXT NOT NULL, expected_value TEXT, actual_value TEXT, diff_message TEXT, expected_template TEXT, expected_path TEXT, expected_display_value TEXT, case_end_execution INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS table_backup (id " + id + (postgres ? " PRIMARY KEY" : "") + ", execution_id INTEGER NOT NULL, datasource_key TEXT NOT NULL, table_name TEXT NOT NULL, snapshot_name TEXT NOT NULL, backup_table TEXT NOT NULL, status TEXT NOT NULL, row_count BIGINT, error_message TEXT, backup_condition TEXT, identity_state_json TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS execution_snapshot (execution_id BIGINT PRIMARY KEY REFERENCES test_execution(id), snapshot_ciphertext TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS execution_asset_reference (execution_id BIGINT NOT NULL REFERENCES test_execution(id),asset_id BIGINT NOT NULL REFERENCES test_file_asset(id),PRIMARY KEY(execution_id,asset_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS execution_resource_request (execution_id BIGINT NOT NULL REFERENCES test_execution(id), resource_key TEXT NOT NULL, PRIMARY KEY(execution_id,resource_key))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS execution_resource_owner (resource_key TEXT PRIMARY KEY, execution_id BIGINT NOT NULL REFERENCES test_execution(id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS execution_queue_control (id INTEGER PRIMARY KEY CHECK(id=1))");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_case_backup_running ON case_backup_run(status) WHERE status='RUNNING'");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_step_statistics_interface ON step_execution(statistics_interface_key,execution_id) WHERE statistics_interface_key IS NOT NULL");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_execution_statistics_time ON test_execution(finished_at,case_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_execution_finalization ON test_execution(status,finalization_state,heartbeat_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_table_backup_execution ON table_backup(execution_id)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_test_case_group_code ON test_case(group_id,case_no) WHERE group_id IS NOT NULL");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_test_case_ungrouped_code ON test_case(case_no) WHERE group_id IS NULL");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_test_case_active_group_name ON test_case(group_id,name) WHERE group_id IS NOT NULL AND recycled=0");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_test_case_active_ungrouped_name ON test_case(name) WHERE group_id IS NULL AND recycled=0");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_case_group_root_name ON case_group(name) WHERE parent_id IS NULL");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_case_group_sibling_name ON case_group(parent_id,name) WHERE parent_id IS NOT NULL");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_case_group_parent_sort ON case_group(parent_id,sort_no,group_no)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_test_case_group ON test_case(group_id,case_no)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_test_case_upload_asset ON test_case_upload(asset_id)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_execution_batch_position ON test_execution(batch_id,batch_position)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_execution_batch_status ON test_execution(batch_id,status)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_step_execution_phase ON step_execution(execution_id,sort_no,case_end_execution)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_execution_asset ON execution_asset_reference(asset_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_execution_request_key ON execution_resource_request(resource_key,execution_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_execution_owner_id ON execution_resource_owner(execution_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_execution_queue ON test_execution(status,id)");
        jdbc.update("INSERT INTO execution_queue_control(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM execution_queue_control WHERE id=1)");
    }
}
