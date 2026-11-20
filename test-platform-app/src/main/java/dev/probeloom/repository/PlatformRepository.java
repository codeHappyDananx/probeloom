package dev.probeloom.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.probeloom.model.PlatformModels;
import dev.probeloom.config.PlatformStorage;
import dev.probeloom.service.CryptoService;
import dev.probeloom.service.SnapshotRules;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
public class PlatformRepository {
    private static final int ASSERTION_FORMAT_VERSION = 2;
    private static final ObjectMapper ASSERTION_MAPPER = new ObjectMapper();
    private static final Pattern JSON_PATH_SEGMENT = Pattern.compile("(?:\\.([A-Za-z_][A-Za-z0-9_-]*)|\\[([0-9]+)\\])");
    private final JdbcTemplate jdbc;
    private final CryptoService cryptoService;
    private final PlatformStorage storage;

    @Autowired
    public PlatformRepository(JdbcTemplate platformJdbcTemplate, CryptoService cryptoService, PlatformStorage storage) {
        this.jdbc = platformJdbcTemplate;
        this.cryptoService = cryptoService;
        this.storage = storage;
    }

    public PlatformRepository(JdbcTemplate platformJdbcTemplate, CryptoService cryptoService) {
        this(platformJdbcTemplate, cryptoService, null);
    }

    @Transactional
    public void migrate() {
        PlatformSchema.initialize(jdbc, storage != null && storage.isJdbc());
    }

    public boolean hasUser(String username) { return jdbc.queryForObject("SELECT COUNT(1) FROM platform_user WHERE username=?", Integer.class, username) > 0; }
    public void createUser(String username, String passwordHash) { jdbc.update("INSERT INTO platform_user(username,password_hash) VALUES (?,?)", username, passwordHash); }
    public String findPasswordHash(String username) { try { return jdbc.queryForObject("SELECT password_hash FROM platform_user WHERE username=?", String.class, username); } catch (EmptyResultDataAccessException e) { return null; } }

    public String createSession(String username) {
        Long userId = jdbc.queryForObject("SELECT id FROM platform_user WHERE username=?", Long.class, username);
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("DELETE FROM session_token WHERE expire_at < ?", Instant.now().toString());
        jdbc.update("INSERT INTO session_token(token,user_id,expire_at) VALUES (?,?,?)", token, userId, Instant.now().plusSeconds(8 * 3600).toString());
        return token;
    }

    public boolean isTokenValid(String token) { return token != null && jdbc.queryForObject("SELECT COUNT(1) FROM session_token WHERE token=? AND expire_at>=?", Integer.class, token, Instant.now().toString()) > 0; }

    public List<PlatformModels.Environment> listEnvironments() { return jdbc.query(environmentSql() + " ORDER BY environment.name", environmentMapper()); }
    public List<PlatformModels.TestDataSource> listDataSources() { return jdbc.query("SELECT id,name,resource_key,database_type,jdbc_url,username,access_mode FROM test_datasource ORDER BY name", dataSourceMapper(false)); }
    public List<PlatformModels.FixtureDataset> listDatasets() { return jdbc.query("SELECT id,name,datasource_key,variables_json,prepare_sql,cleanup_sql FROM fixture_dataset ORDER BY name", datasetMapper()); }
    public List<PlatformModels.TestCase> listCaseSummaries() { return jdbc.query(caseSelect() + " WHERE " + activeCaseCondition() + " ORDER BY test_case.group_id IS NULL,test_case.group_id,test_case.case_no", caseMapper()); }
    public List<String> caseApplications(List<Long> caseIds) {
        if (caseIds.isEmpty()) return java.util.Collections.singletonList("default");
        String placeholders = String.join(",", java.util.Collections.nCopies(caseIds.size(), "?"));
        List<String> applications = jdbc.queryForList("SELECT DISTINCT COALESCE(NULLIF(application_name,''),'default') FROM test_case_action WHERE action_type IN ('HTTP','UPLOAD') AND case_id IN (" + placeholders + ") ORDER BY 1", String.class, caseIds.toArray());
        return applications.isEmpty() ? java.util.Collections.singletonList("default") : applications;
    }
    public List<PlatformModels.TestCase> listCases() { return listCases(null); }
    public List<PlatformModels.TestCase> listCases(Long groupId) {
        String sql = caseSelect();
        List<PlatformModels.TestCase> values = groupId == null
                ? jdbc.query(sql + " WHERE " + activeCaseCondition() + " ORDER BY test_case.group_id IS NULL,test_case.group_id,test_case.case_no", caseMapper())
                : jdbc.query(subtreeCaseSelect() + " WHERE " + activeCaseCondition() + " ORDER BY test_case.group_id,test_case.case_no", caseMapper(), groupId);
        for (PlatformModels.TestCase value : values) fillCaseChildren(value);
        return values;
    }
    public PlatformModels.CasePage listCasesPage(Integer page, Integer pageSize, String keyword, Long groupId, Boolean enabled) {
        return listCasesPage(page, pageSize, keyword, groupId, enabled, new PlatformModels.CaseTableQuery());
    }
    public PlatformModels.CasePage listCasesPage(Integer page, Integer pageSize, String keyword, Long groupId, Boolean enabled, PlatformModels.CaseTableQuery tableQuery) {
        int requestedPage = page == null ? 1 : page;
        int actualPageSize = pageSize == null ? 20 : pageSize;
        if (requestedPage < 1) throw new IllegalArgumentException("页码必须大于等于 1");
        if (actualPageSize < 1 || actualPageSize > 500) throw new IllegalArgumentException("每页条数必须在 1 到 500 之间");

        /*
         * ========================================================================
         * 步骤1：按案例及最近一次执行构造查询范围
         * ========================================================================
         * 数据源：test_case、case_group、test_execution。
         * 操作：1) 最近执行按执行 ID 取最新记录；2) 分组筛选包含子分组。
         */
        String groupCte = groupId == null ? "" : "WITH RECURSIVE group_tree(id) AS (SELECT id FROM case_group WHERE id=? UNION ALL SELECT child.id FROM case_group child JOIN group_tree parent ON child.parent_id=parent.id) ";
        String latestJoin = " LEFT JOIN (SELECT case_id,MAX(id) AS id FROM test_execution GROUP BY case_id) recent ON recent.case_id=test_case.id LEFT JOIN test_execution latest ON latest.id=recent.id";
        String latestTime = "COALESCE(latest.finished_at,latest.started_at,latest.queued_at)";
        String caseCode = "(CASE WHEN case_group.group_no IS NULL THEN '未分组' WHEN case_group.group_no < 10 THEN '0' || CAST(case_group.group_no AS VARCHAR) ELSE CAST(case_group.group_no AS VARCHAR) END"
                + " || '-' || CASE WHEN test_case.case_no < 10 THEN '00' WHEN test_case.case_no < 100 THEN '0' ELSE '' END || CAST(test_case.case_no AS VARCHAR))";
        String condition = " WHERE " + activeCaseCondition();
        List<Object> filterArgs = new ArrayList<Object>();
        if (groupId != null) {
            condition += " AND test_case.group_id IN (SELECT id FROM group_tree)";
            filterArgs.add(groupId);
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String value = "%" + keyword.trim() + "%";
            String caseLabel = "(CASE WHEN case_group.group_no IS NULL THEN '未分组' WHEN case_group.group_no < 10 THEN '0' || CAST(case_group.group_no AS VARCHAR) ELSE CAST(case_group.group_no AS VARCHAR) END"
                    + " || '-' || CASE WHEN test_case.case_no < 10 THEN '00' WHEN test_case.case_no < 100 THEN '0' ELSE '' END || CAST(test_case.case_no AS VARCHAR)"
                    + " || ' · ' || test_case.name)";
            condition += " AND (test_case.name LIKE ? OR test_case.description LIKE ? OR case_group.name LIKE ? OR CAST(test_case.case_no AS VARCHAR) LIKE ? OR " + caseLabel + " LIKE ?)";
            filterArgs.add(value);
            filterArgs.add(value);
            filterArgs.add(value);
            filterArgs.add(value);
            filterArgs.add(value);
        }
        if (enabled != null) {
            condition += " AND test_case.enabled=?";
            filterArgs.add(bool(enabled));
        }
        // 1.1 列内筛选与顶部查询叠加，计数及分页共用同一条件。
        if (!blank(tableQuery.getCaseCode())) {
            condition += " AND " + caseCode + " LIKE ?";
            filterArgs.add("%" + tableQuery.getCaseCode().trim() + "%");
        }
        if (!blank(tableQuery.getCaseName())) {
            condition += " AND test_case.name LIKE ?";
            filterArgs.add("%" + tableQuery.getCaseName().trim() + "%");
        }
        if (tableQuery.getLatestStatus() != null) {
            if (tableQuery.getLatestStatus() == PlatformModels.CaseExecutionStatus.UNEXECUTED) condition += " AND latest.id IS NULL";
            else {
                condition += " AND latest.status=?";
                filterArgs.add(tableQuery.getLatestStatus().name());
            }
        }
        java.time.LocalDate from;
        java.time.LocalDate to;
        try {
            from = blank(tableQuery.getExecutedFrom()) ? null : java.time.LocalDate.parse(tableQuery.getExecutedFrom());
            to = blank(tableQuery.getExecutedTo()) ? null : java.time.LocalDate.parse(tableQuery.getExecutedTo());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("执行日期必须为 YYYY-MM-DD");
        }
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("执行开始日期不能晚于结束日期");
        if (from != null) {
            condition += " AND " + latestTime + ">=?";
            filterArgs.add(from.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toString());
        }
        if (to != null) {
            condition += " AND " + latestTime + "<?";
            filterArgs.add(to.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toString());
        }

        /*
         * ========================================================================
         * 步骤2：按指定列排序并查询分页结果
         * ========================================================================
         * 操作：1) 排序字段及方向使用枚举；2) 空执行记录置后，案例 ID 保证分页稳定。
         */
        String direction = tableQuery.getSortDirection().name();
        String order;
        switch (tableQuery.getSortBy()) {
            case NAME: order = "test_case.name " + direction; break;
            case GROUP: order = "case_group.name IS NULL,case_group.name " + direction; break;
            case LATEST_EXECUTION: order = latestTime + " IS NULL," + latestTime + " " + direction; break;
            case STATUS: order = "latest.status IS NULL,latest.status " + direction; break;
            default: order = "case_group.group_no IS NULL,case_group.group_no " + direction + ",test_case.case_no " + direction;
        }
        // 2.1 先计算全部匹配条数，再限定页码和返回记录。
        Long total = jdbc.queryForObject(groupCte + "SELECT COUNT(1) FROM test_case LEFT JOIN case_group ON case_group.id=test_case.group_id" + latestJoin + condition, Long.class, filterArgs.toArray());
        int totalPages = total == null || total == 0 ? 1 : (int) ((total + actualPageSize - 1) / actualPageSize);
        int actualPage = Math.min(requestedPage, totalPages);
        List<Object> pageArgs = new ArrayList<Object>(filterArgs);
        pageArgs.add(actualPageSize);
        pageArgs.add((actualPage - 1) * actualPageSize);
        String select = caseSelect().replace(" FROM test_case", ",latest.id AS latest_execution_id," + latestTime + " AS latest_execution_at,latest.status AS latest_execution_status FROM test_case");
        RowMapper<PlatformModels.TestCase> mapper = caseMapper();
        List<PlatformModels.TestCase> items = jdbc.query(groupCte + select + latestJoin + condition + " ORDER BY " + order + ",test_case.id LIMIT ? OFFSET ?", (rs, row) -> {
            PlatformModels.TestCase item = mapper.mapRow(rs, row);
            item.latestExecutionId = nullableLong(rs, "latest_execution_id");
            item.latestExecutionAt = rs.getString("latest_execution_at");
            item.latestExecutionStatus = rs.getString("latest_execution_status");
            return item;
        }, pageArgs.toArray());

        PlatformModels.CasePage result = new PlatformModels.CasePage();
        result.items = items;
        result.page = actualPage;
        result.pageSize = actualPageSize;
        result.total = total == null ? 0L : total;
        return result;
    }
    public List<PlatformModels.TestCase> listRecycledCases() {
        List<PlatformModels.TestCase> values = jdbc.query(recycledCaseSelect() + " ORDER BY recycle.deleted_at DESC", recycledCaseMapper());
        for (PlatformModels.TestCase value : values) fillCaseChildren(value);
        return values;
    }
    public List<PlatformModels.CaseGroup> listGroups() {
        List<PlatformModels.CaseGroup> values = jdbc.query("SELECT id,group_no,name,parent_id,sort_no FROM case_group ORDER BY parent_id IS NOT NULL,parent_id,sort_no IS NULL,sort_no,group_no", groupMapper());
        Map<Long, PlatformModels.CaseGroup> groups = new HashMap<Long, PlatformModels.CaseGroup>();
        Map<Long, List<PlatformModels.CaseGroup>> children = new HashMap<Long, List<PlatformModels.CaseGroup>>();
        for (PlatformModels.CaseGroup value : values) {
            groups.put(value.id, value);
            value.caseIds = directGroupCaseIds(value.id);
            if (value.parentId != null) {
                List<PlatformModels.CaseGroup> childValues = children.get(value.parentId);
                if (childValues == null) { childValues = new ArrayList<PlatformModels.CaseGroup>(); children.put(value.parentId, childValues); }
                childValues.add(value);
            }
        }
        for (List<PlatformModels.CaseGroup> childValues : children.values()) Collections.sort(childValues, groupOrder());
        for (PlatformModels.CaseGroup value : values) if (value.parentId == null || !groups.containsKey(value.parentId)) populateGroupTree(value, children, "", 0);
        return values;
    }
    public List<PlatformModels.SchedulePlan> listSchedules() { return jdbc.query("SELECT id,group_id,environment_id,name,cron_expression,enabled FROM schedule_plan ORDER BY name", scheduleMapper()); }

    @Transactional
    public PlatformModels.Environment saveEnvironment(PlatformModels.Environment value) {
        require(value.name, "环境名称不能为空");
        require(value.refapp, "环境必须填写应用分组");
        dev.probeloom.service.HttpCaseRules.validateConnections(value.connections);
        if (value.connections.isEmpty()) throw new IllegalArgumentException("运行环境至少配置一个连接");
        String connections;
        try { connections = ASSERTION_MAPPER.writeValueAsString(value.connections); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("HTTP 连接配置不合法", e); }
        boolean defaultEnvironment = Boolean.TRUE.equals(value.defaultEnvironment);
        if (defaultEnvironment) jdbc.update("UPDATE test_environment SET is_default=0 WHERE refapp=? OR (refapp IS NULL AND ? IS NULL)", value.refapp, value.refapp);
        if (value.id == null) value.id = insert("INSERT INTO test_environment(name,refapp,is_default,connections_json) VALUES (?,?,?,?)", new Object[] {value.name, value.refapp, defaultEnvironment ? 1 : 0, connections});
        else jdbc.update("UPDATE test_environment SET name=?,refapp=?,is_default=?,connections_json=? WHERE id=?", value.name, value.refapp, defaultEnvironment ? 1 : 0, connections, value.id);
        return findEnvironment(value.id);
    }

    public PlatformModels.Environment findEnvironment(Long id) { return jdbc.queryForObject(environmentSql() + " WHERE environment.id=?", environmentMapper(), id); }

    public PlatformModels.TestDataSource saveDataSource(PlatformModels.TestDataSource value) {
        require(value.name, "数据源名称不能为空");
        require(value.resourceKey, "数据源逻辑标识不能为空");
        require(value.databaseType, "数据库类型不能为空");
        require(value.jdbcUrl, "JDBC 地址不能为空");
        if ("SQLITE".equals(value.databaseType)) { value.username = ""; value.password = ""; }
        if (!"SQLITE".equals(value.databaseType)) require(value.username, "数据库用户不能为空");
        require(value.accessMode, "数据源访问模式不能为空");
        if (value.id == null) {
            if (!"SQLITE".equals(value.databaseType)) require(value.password, "新增数据源必须填写口令");
            value.id = insert("INSERT INTO test_datasource(name,resource_key,database_type,jdbc_url,username,password_ciphertext,access_mode) VALUES (?,?,?,?,?,?,?)", new Object[] {value.name, value.resourceKey, value.databaseType, value.jdbcUrl, value.username, cryptoService.encrypt(value.password), value.accessMode});
        } else if (blank(value.password)) {
            jdbc.update("UPDATE test_datasource SET name=?,resource_key=?,database_type=?,jdbc_url=?,username=?,access_mode=? WHERE id=?", value.name, value.resourceKey, value.databaseType, value.jdbcUrl, value.username, value.accessMode, value.id);
        } else {
            jdbc.update("UPDATE test_datasource SET name=?,resource_key=?,database_type=?,jdbc_url=?,username=?,password_ciphertext=?,access_mode=? WHERE id=?", value.name, value.resourceKey, value.databaseType, value.jdbcUrl, value.username, cryptoService.encrypt(value.password), value.accessMode, value.id);
        }
        return findDataSource(value.id, false);
    }

    public PlatformModels.TestDataSource findDataSource(Long id, boolean includePassword) {
        String fields = includePassword ? "id,name,resource_key,database_type,jdbc_url,username,password_ciphertext,access_mode" : "id,name,resource_key,database_type,jdbc_url,username,access_mode";
        return jdbc.queryForObject("SELECT " + fields + " FROM test_datasource WHERE id=?", dataSourceMapper(includePassword), id);
    }

    public PlatformModels.TestDataSource findDataSource(String resourceKey, boolean includePassword) {
        String fields = includePassword ? "id,name,resource_key,database_type,jdbc_url,username,password_ciphertext,access_mode" : "id,name,resource_key,database_type,jdbc_url,username,access_mode";
        try { return jdbc.queryForObject("SELECT " + fields + " FROM test_datasource WHERE resource_key=?", dataSourceMapper(includePassword), resourceKey); }
        catch (EmptyResultDataAccessException e) { throw new IllegalStateException("数据源不存在：" + resourceKey); }
    }

    public PlatformModels.FixtureDataset saveDataset(PlatformModels.FixtureDataset value) {
        require(value.name, "固定数据集名称不能为空");
        require(value.datasourceKey, "固定数据集必须选择数据源标识");
        require(value.cleanupSql, "固定数据集必须配置后置清理 SQL");
        if (value.id == null) value.id = insert("INSERT INTO fixture_dataset(name,datasource_key,variables_json,prepare_sql,cleanup_sql) VALUES (?,?,?,?,?)", new Object[] {value.name, value.datasourceKey, value.variablesJson, value.prepareSql, value.cleanupSql});
        else jdbc.update("UPDATE fixture_dataset SET name=?,datasource_key=?,variables_json=?,prepare_sql=?,cleanup_sql=? WHERE id=?", value.name, value.datasourceKey, value.variablesJson, value.prepareSql, value.cleanupSql, value.id);
        return findDataset(value.id);
    }

    public PlatformModels.FixtureDataset findDataset(Long id) { return jdbc.queryForObject("SELECT id,name,datasource_key,variables_json,prepare_sql,cleanup_sql FROM fixture_dataset WHERE id=?", datasetMapper(), id); }

    @Transactional
    public PlatformModels.TestCase saveCase(PlatformModels.TestCase value) {
        require(value.name, "案例名称不能为空");
        if (value.groupId != null) findGroup(value.groupId);
        if (value.id == null) {
            ensureCaseName(value.groupId, value.name, null);
            value.caseNo = nextCaseNo(value.groupId);
            value.id = insert("INSERT INTO test_case(group_id,case_no,name,description,enabled) VALUES (?,?,?,?,?)", new Object[] {value.groupId, value.caseNo, value.name, value.description, enabledFlag(value.enabled)});
        } else {
            PlatformModels.TestCase current = loadCase(value.id);
            boolean moved = current.groupId == null ? value.groupId != null : !current.groupId.equals(value.groupId);
            ensureCaseName(value.groupId, value.name, value.id);
            value.caseNo = moved ? nextCaseNo(value.groupId) : current.caseNo;
            if (value.groupId == null) {
                jdbc.update("UPDATE test_case SET group_id=NULL,case_no=?,name=?,description=?,enabled=? WHERE id=?", value.caseNo, value.name, value.description, enabledFlag(value.enabled), value.id);
            } else {
                jdbc.update("UPDATE test_case SET group_id=?,case_no=?,name=?,description=?,enabled=? WHERE id=?", value.groupId, value.caseNo, value.name, value.description, enabledFlag(value.enabled), value.id);
            }
        }
        if (Boolean.TRUE.equals(value.replaceActions)) {
            validateCaseActions(value.actions);
            jdbc.update("DELETE FROM test_case_upload WHERE action_id IN (SELECT id FROM test_case_action WHERE case_id=?)", value.id);
            jdbc.update("DELETE FROM test_case_action WHERE case_id=?", value.id);
            for (PlatformModels.CaseAction action : emptyIfNull(value.actions)) insertAction(value.id, action);
        }
        return loadCase(value.id);
    }

    public PlatformModels.TestCase loadCase(Long id) {
        PlatformModels.TestCase value = jdbc.queryForObject(caseSelect() + " WHERE test_case.id=? AND " + activeCaseCondition(), caseMapper(), id);
        fillCaseChildren(value);
        return value;
    }

    @Transactional
    public void deleteCase(Long id) {
        deleteCases(Collections.singletonList(id));
    }

    @Transactional
    public void deleteCases(List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) throw new IllegalArgumentException("请选择要删除的案例");
        String deletedAt = Instant.now().toString();
        // 跨页勾选可超过 SQLite 占位符上限；分段写入仍处于同一事务。
        for (int start = 0; start < caseIds.size(); start += 500) {
            List<Long> ids = caseIds.subList(start, Math.min(start + 500, caseIds.size()));
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            List<Object> arguments = new ArrayList<Object>();
            arguments.add(deletedAt);
            arguments.addAll(ids);
            int count = jdbc.update("INSERT INTO test_case_recycle_bin(case_id,deleted_at) SELECT id,? FROM test_case WHERE id IN (" + placeholders + ") AND " + activeCaseCondition(), arguments.toArray());
            if (count != ids.size()) throw new IllegalArgumentException("所选案例不存在、已删除或重复，请刷新列表后重试");
            jdbc.update("UPDATE test_case SET recycled=1 WHERE id IN (" + placeholders + ")", ids.toArray());
        }
    }

    @Transactional
    public PlatformModels.TestCase restoreCase(Long id) {
        if (jdbc.update("DELETE FROM test_case_recycle_bin WHERE case_id=?", id) == 0) {
            throw new IllegalArgumentException("案例不在回收站中");
        }
        PlatformModels.TestCase value = jdbc.queryForObject(caseSelect() + " WHERE test_case.id=?", caseMapper(), id);
        ensureCaseName(value.groupId, value.name, id);
        jdbc.update("UPDATE test_case SET recycled=0 WHERE id=?", id);
        return loadCase(id);
    }

    @Transactional
    public void permanentlyDeleteCase(Long id) {
        jdbc.update("UPDATE execution_queue_control SET id=id WHERE id=1");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM test_execution WHERE case_id=? AND status IN ('QUEUED','RUNNING')", Long.class, id) > 0) {
            throw new IllegalStateException("案例仍有排队或执行任务，不能永久删除");
        }
        if (jdbc.update("DELETE FROM test_case_recycle_bin WHERE case_id=?", id) == 0) {
            throw new IllegalArgumentException("案例不在回收站中");
        }
        List<Long> assetIds = jdbc.query("SELECT upload.asset_id FROM test_case_upload upload JOIN test_case_action action ON action.id=upload.action_id WHERE action.case_id=?", (rs, row) -> rs.getLong(1), id);
        jdbc.update("DELETE FROM test_case_upload WHERE action_id IN (SELECT id FROM test_case_action WHERE case_id=?)", id);
        jdbc.update("DELETE FROM test_case_action WHERE case_id=?", id);
        jdbc.update("DELETE FROM execution_resource_owner WHERE execution_id IN (SELECT id FROM test_execution WHERE case_id=?)", id);
        jdbc.update("DELETE FROM execution_resource_request WHERE execution_id IN (SELECT id FROM test_execution WHERE case_id=?)", id);
        jdbc.update("DELETE FROM execution_asset_reference WHERE execution_id IN (SELECT id FROM test_execution WHERE case_id=?)", id);
        jdbc.update("DELETE FROM execution_snapshot WHERE execution_id IN (SELECT id FROM test_execution WHERE case_id=?)", id);
        jdbc.update("DELETE FROM step_execution WHERE execution_id IN (SELECT id FROM test_execution WHERE case_id=?)", id);
        jdbc.update("DELETE FROM assertion_execution WHERE execution_id IN (SELECT id FROM test_execution WHERE case_id=?)", id);
        jdbc.update("DELETE FROM test_execution WHERE case_id=?", id);
        jdbc.update("DELETE FROM test_case WHERE id=?", id);
        for (Long assetId : assetIds) jdbc.update("DELETE FROM test_file_asset WHERE id=? AND NOT EXISTS (SELECT 1 FROM test_case_upload WHERE asset_id=?) AND NOT EXISTS (SELECT 1 FROM execution_asset_reference WHERE asset_id=?)", assetId, assetId, assetId);
    }

    public PlatformModels.TestCase findCaseByGroupAndName(Long groupId, String name) {
        try {
            PlatformModels.TestCase value = groupId == null
                    ? jdbc.queryForObject(caseSelect() + " WHERE test_case.group_id IS NULL AND test_case.name=? AND " + activeCaseCondition(), caseMapper(), name)
                    : jdbc.queryForObject(caseSelect() + " WHERE test_case.group_id=? AND test_case.name=? AND " + activeCaseCondition(), caseMapper(), groupId, name);
            fillCaseChildren(value);
            return value;
        } catch (EmptyResultDataAccessException e) { return null; }
    }

    public PlatformModels.TestCase findRecycledCaseByGroupAndName(Long groupId, String name) {
        List<PlatformModels.TestCase> values = groupId == null
                ? jdbc.query(recycledCaseSelect() + " WHERE test_case.group_id IS NULL AND test_case.name=?", recycledCaseMapper(), name)
                : jdbc.query(recycledCaseSelect() + " WHERE test_case.group_id=? AND test_case.name=?", recycledCaseMapper(), groupId, name);
        if (values.isEmpty()) return null;
        if (values.size() > 1) throw new IllegalArgumentException("回收站存在多个同名案例，请先在回收站恢复指定案例后再导入：" + name);
        PlatformModels.TestCase value = values.get(0);
        fillCaseChildren(value);
        return value;
    }

    @Transactional
    public PlatformModels.CaseGroup saveGroup(PlatformModels.CaseGroup value) {
        require(value.name, "分组名称不能为空");
        if (value.parentId != null) findGroup(value.parentId);
        if (value.id == null) {
            Integer groupNo = nextGroupNo();
            ensureGroupName(value.parentId, value.name, null);
            value.id = insert("INSERT INTO case_group(group_no,name,parent_id,sort_no) VALUES (?,?,?,?)", new Object[] {groupNo, value.name, value.parentId, groupNo});
        } else {
            PlatformModels.CaseGroup current = findGroup(value.id);
            assertNotDescendant(value.id, value.parentId);
            ensureGroupName(value.parentId, value.name, value.id);
            jdbc.update("UPDATE case_group SET name=?,parent_id=? WHERE id=?", value.name, value.parentId, value.id);
        }
        PlatformModels.CaseGroup saved = findGroup(value.id);
        Set<Long> targetCaseIds = new LinkedHashSet<Long>(emptyIfNull(value.caseIds));
        for (Long caseId : saved.caseIds) if (!targetCaseIds.contains(caseId)) assignCaseGroup(caseId, null);
        for (Long caseId : targetCaseIds) {
            PlatformModels.TestCase testCase = loadCase(caseId);
            if (!saved.id.equals(testCase.groupId)) assignCaseGroup(caseId, saved.id);
        }
        return findGroup(value.id);
    }

    @Transactional
    public void deleteGroup(Long id) {
        deleteGroup(id, false);
    }

    @Transactional
    public void deleteGroup(Long id, boolean deleteCases) {
        findGroup(id);
        if (jdbc.queryForObject("SELECT COUNT(1) FROM case_group WHERE parent_id=?", Integer.class, id) > 0) {
            throw new IllegalArgumentException("分组包含子分组，不能删除");
        }
        jdbc.update("DELETE FROM schedule_plan WHERE group_id=?", id);
        if (deleteCases) {
            jdbc.update("INSERT INTO test_case_recycle_bin(case_id,deleted_at) SELECT id,? FROM test_case WHERE group_id=? AND " + activeCaseCondition(), Instant.now().toString(), id);
            jdbc.update("UPDATE test_case SET recycled=1 WHERE group_id=?", id);
        }
        for (Long caseId : allGroupCaseIds(id)) assignCaseGroup(caseId, null);
        jdbc.update("UPDATE test_execution SET group_id=NULL WHERE group_id=?", id);
        jdbc.update("DELETE FROM case_group WHERE id=?", id);
    }

    public List<Long> groupCaseIds(Long groupId) {
        return jdbc.query("WITH RECURSIVE group_tree(id) AS (SELECT id FROM case_group WHERE id=? UNION ALL SELECT child.id FROM case_group child JOIN group_tree parent ON child.parent_id=parent.id) SELECT test_case.id FROM test_case JOIN group_tree ON group_tree.id=test_case.group_id WHERE " + activeCaseCondition() + " ORDER BY test_case.group_id,test_case.case_no", (rs, row) -> rs.getLong(1), groupId);
    }

    public PlatformModels.SchedulePlan saveSchedule(PlatformModels.SchedulePlan value) {
        require(value.name, "计划名称不能为空");
        requireId(value.groupId, "计划必须选择分组");
        findGroup(value.groupId);
        if (value.environmentId != null) findEnvironment(value.environmentId);
        require(value.cronExpression, "Cron 表达式不能为空");
        if (value.id == null) value.id = insert("INSERT INTO schedule_plan(group_id,environment_id,name,cron_expression,enabled) VALUES (?,?,?,?,?)", new Object[] {value.groupId, value.environmentId, value.name, value.cronExpression, enabledFlag(value.enabled)});
        else jdbc.update("UPDATE schedule_plan SET group_id=?,environment_id=?,name=?,cron_expression=?,enabled=? WHERE id=?", value.groupId, value.environmentId, value.name, value.cronExpression, enabledFlag(value.enabled), value.id);
        return jdbc.queryForObject("SELECT id,group_id,environment_id,name,cron_expression,enabled FROM schedule_plan WHERE id=?", scheduleMapper(), value.id);
    }

    public long createExecution(Long caseId, Long groupId, Long environmentId, Long targetActionId, String triggerType) { return insert("INSERT INTO test_execution(case_id,group_id,environment_id,target_action_id,trigger_type,status) VALUES (?,?,?,?,?,?)", new Object[] {caseId, groupId, environmentId, targetActionId, triggerType, "QUEUED"}); }
    public void startExecution(long id) { jdbc.update("UPDATE test_execution SET status='RUNNING',started_at=? WHERE id=?", Instant.now().toString(), id); }
    public void finishExecution(long id, String status, String error) { jdbc.update("UPDATE test_execution SET status=?,finished_at=?,error_message=? WHERE id=?", status, Instant.now().toString(), error, id); }
    public void saveExecutionMessage(long id, String message) { jdbc.update("UPDATE test_execution SET message=? WHERE id=?", message, id); }
    public void saveStepExecution(long executionId, PlatformModels.StepExecution step) { insert("INSERT INTO step_execution(execution_id,sort_no,case_end_execution,status,request_body,response_body,resolved_variables_json,error_message,duration_ms) VALUES (?,?,?,?,?,?,?,?,?)", new Object[] {executionId, step.sortNo, bool(step.caseEnd), step.status, step.requestBody, step.responseBody, step.resolvedVariablesJson, step.errorMessage, step.durationMs}); }
    public void updateStepExecution(long executionId, PlatformModels.StepExecution step) { jdbc.update("UPDATE step_execution SET status=?,request_body=?,response_body=?,resolved_variables_json=?,error_message=?,duration_ms=? WHERE execution_id=? AND sort_no=? AND case_end_execution=?", step.status, step.requestBody, step.responseBody, step.resolvedVariablesJson, step.errorMessage, step.durationMs, executionId, step.sortNo, bool(step.caseEnd)); }
    public void updateStepExecutionStatus(long executionId, int sortNo, String status, String errorMessage, boolean end) { jdbc.update("UPDATE step_execution SET status=?, error_message=? WHERE execution_id=? AND sort_no=? AND case_end_execution=?", status, errorMessage, executionId, sortNo, bool(end)); }
    public void saveAssertionExecution(long executionId, PlatformModels.AssertionExecution assertion) { insert("INSERT INTO assertion_execution(execution_id,step_sort_no,case_end_execution,assertion_type,status,expected_value,actual_value,diff_message,expected_template,expected_path,expected_display_value) VALUES (?,?,?,?,?,?,?,?,?,?,?)", new Object[] {executionId, assertion.stepSortNo, bool(assertion.caseEnd), assertion.assertionType, assertion.status, assertion.expectedValue, assertion.actualValue, assertion.diffMessage, assertion.expectedTemplate, assertion.expectedPath, assertion.expectedDisplayValue}); }
    public List<PlatformModels.Execution> listExecutions() { return pageExecutions(1, 100, null, null, null, null, null, null, false).items; }

    public PlatformModels.ExecutionPage pageExecutions(int page, int pageSize, Long submittedBy, String keyword,
            String status, String triggerType, Long caseId, Long environmentId, boolean recoveryPending) {
        if (page < 1 || pageSize < 1 || pageSize > 500) throw new IllegalArgumentException("页码必须为正整数，每页条数为 1 至 500");
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> arguments = new ArrayList<Object>();
        if (submittedBy != null) { where.append(" AND e.submitted_by=?"); arguments.add(submittedBy); }
        if (!blank(keyword)) {
            where.append(" AND (c.name LIKE ? OR e.submitted_by_name LIKE ? OR CAST(e.id AS VARCHAR) LIKE ? OR CAST(c.case_no AS VARCHAR) LIKE ?)");
            for (int i = 0; i < 4; i++) arguments.add("%" + keyword.trim() + "%");
        }
        if (!blank(status)) { where.append(" AND e.status=?"); arguments.add(status); }
        if (recoveryPending) where.append(" AND e.status='FAILED' AND e.progress_at IS NOT NULL AND e.finalization_phase<>'COMPLETE'");
        if (!blank(triggerType)) { where.append(" AND e.trigger_type=?"); arguments.add(triggerType); }
        if (caseId != null) { where.append(" AND e.case_id=?"); arguments.add(caseId); }
        if (environmentId != null) { where.append(" AND e.environment_id=?"); arguments.add(environmentId); }
        String from = " FROM test_execution e LEFT JOIN test_case c ON c.id=e.case_id LEFT JOIN execution_batch b ON b.id=e.batch_id";
        PlatformModels.ExecutionPage result = new PlatformModels.ExecutionPage();
        result.total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, arguments.toArray());
        result.page = page; result.pageSize = pageSize;
        arguments.add(pageSize); arguments.add(((long) page - 1L) * pageSize);
        result.items = jdbc.query("SELECT e.*,b.parallelism AS batch_parallelism" + from + where + " ORDER BY e.id DESC LIMIT ? OFFSET ?", executionMapper(), arguments.toArray());
        fillExecutionWait(result.items);
        return result;
    }
    public List<PlatformModels.Execution> executionStatuses(List<Long> executionIds) {
        if (executionIds == null || executionIds.isEmpty()) throw new IllegalArgumentException("执行标识不能为空");
        for (Long id : executionIds) if (id == null || id <= 0) throw new IllegalArgumentException("执行标识必须是正整数");
        List<PlatformModels.Execution> values = new ArrayList<PlatformModels.Execution>();
        // 分段查询明确的执行标识，避开 SQLite 的占位符数量限制，不限制结果总数。
        for (int start = 0; start < executionIds.size(); start += 500) {
            List<Long> ids = executionIds.subList(start, Math.min(start + 500, executionIds.size()));
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            values.addAll(jdbc.query("SELECT e.*,b.parallelism AS batch_parallelism FROM test_execution e LEFT JOIN execution_batch b ON b.id=e.batch_id WHERE e.id IN (" + placeholders + ") ORDER BY e.id", executionMapper(), ids.toArray()));
        }
        fillExecutionWait(values);
        return values;
    }

    public PlatformModels.Execution loadExecution(Long id) {
        PlatformModels.Execution value = jdbc.queryForObject("SELECT e.*,b.parallelism AS batch_parallelism FROM test_execution e LEFT JOIN execution_batch b ON b.id=e.batch_id WHERE e.id=?", executionMapper(), id);
        fillExecutionWait(Collections.singletonList(value));
        String snapshot = jdbc.query("SELECT snapshot_ciphertext FROM execution_snapshot WHERE execution_id=?",
                rs -> rs.next() ? rs.getString(1) : null, id);
        if (snapshot != null) {
            try {
                JsonNode caseNode = ASSERTION_MAPPER.readTree(cryptoService.decrypt(snapshot)).path("testCase");
                value.frozenCase = ASSERTION_MAPPER.treeToValue(caseNode, PlatformModels.TestCase.class);
                for (PlatformModels.CaseAction action : value.frozenCase.actions) {
                    if (action.upload != null) action.upload.contentBase64 = null;
                }
            } catch (Exception e) {
                throw new IllegalStateException("读取执行冻结案例失败：" + id, e);
            }
        }
        value.steps = jdbc.query("SELECT id,sort_no,case_end_execution,status,request_body,response_body,resolved_variables_json,error_message,duration_ms FROM step_execution WHERE execution_id=? ORDER BY case_end_execution,sort_no", stepExecutionMapper(), id);
        value.assertions = jdbc.query("SELECT id,step_sort_no,case_end_execution,assertion_type,status,expected_value,actual_value,diff_message,expected_template,expected_path,expected_display_value FROM assertion_execution WHERE execution_id=? ORDER BY id", assertionExecutionMapper(), id);
        value.backups = jdbc.query("SELECT id,datasource_key,table_name,snapshot_name,backup_table,backup_condition,identity_state_json,status,row_count,error_message FROM table_backup WHERE execution_id=? ORDER BY id", (rs, row) -> {
            PlatformModels.TableBackup backup = new PlatformModels.TableBackup();
            backup.id = rs.getLong("id");
            backup.datasourceKey = rs.getString("datasource_key");
            backup.tableName = rs.getString("table_name");
            backup.snapshotName = rs.getString("snapshot_name");
            backup.backupTable = rs.getString("backup_table");
            backup.backupCondition = rs.getString("backup_condition");
            backup.identityStateJson = rs.getString("identity_state_json");
            backup.status = rs.getString("status");
            backup.rowCount = rs.getObject("row_count") == null ? null : rs.getLong("row_count");
            backup.errorMessage = rs.getString("error_message");
            return backup;
        }, id);
        return value;
    }

    private void fillExecutionWait(List<PlatformModels.Execution> executions) {
        List<PlatformModels.Execution> queued = new ArrayList<PlatformModels.Execution>();
        for (PlatformModels.Execution execution : executions) {
            if ("QUEUED".equals(execution.status)) queued.add(execution);
        }
        if (queued.isEmpty()) return;
        for (PlatformModels.Execution execution : queued) execution.waitReason = "CAPACITY";
        // 按批次查询等待关系，避免每条排队记录单独往返平台库拖慢状态刷新。
        for (int start = 0; start < queued.size(); start += 400) {
            List<PlatformModels.Execution> chunk = queued.subList(start, Math.min(start + 400, queued.size()));
            Map<Long, PlatformModels.Execution> byId = new LinkedHashMap<Long, PlatformModels.Execution>();
            List<Object> ids = new ArrayList<Object>();
            for (PlatformModels.Execution execution : chunk) { byId.put(execution.id, execution); ids.add(execution.id); }
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            List<Object> twice = new ArrayList<Object>(ids); twice.addAll(ids);
            List<Map<String, Object>> blockers = jdbc.queryForList("SELECT r.execution_id AS waiting_id,o.execution_id AS blocking_id FROM execution_resource_request r JOIN execution_resource_owner o ON o.resource_key=r.resource_key WHERE r.execution_id IN (" + placeholders + ")"
                    + " UNION SELECT mine.execution_id AS waiting_id,p.id AS blocking_id FROM execution_resource_request mine JOIN execution_resource_request earlier ON earlier.resource_key=mine.resource_key JOIN test_execution p ON p.id=earlier.execution_id WHERE mine.execution_id IN (" + placeholders + ") AND p.id<mine.execution_id AND p.status='QUEUED'", twice.toArray());
            for (Map<String, Object> blocker : blockers) {
                PlatformModels.Execution execution = byId.get(((Number) blocker.get("waiting_id")).longValue());
                execution.waitReason = "CASE";
                execution.blockedByExecutionIds.add(((Number) blocker.get("blocking_id")).longValue());
            }
            List<Map<String, Object>> batchBlockers = jdbc.queryForList("SELECT e.id AS waiting_id,p.id AS blocking_id FROM test_execution e JOIN execution_batch b ON b.id=e.batch_id JOIN test_execution p ON p.batch_id=e.batch_id WHERE e.id IN (" + placeholders + ") AND ((b.parallelism=1 AND p.id=(SELECT MIN(previous.id) FROM test_execution previous WHERE previous.batch_id=e.batch_id AND previous.batch_position<e.batch_position AND (previous.status IN ('QUEUED','RUNNING') OR (previous.status='FAILED' AND previous.progress_at IS NOT NULL AND previous.finalization_phase<>'COMPLETE')))) OR (b.parallelism>1 AND (p.status='RUNNING' OR (p.status='FAILED' AND p.progress_at IS NOT NULL AND p.finalization_phase<>'COMPLETE')) AND (SELECT COUNT(*) FROM test_execution active WHERE active.batch_id=e.batch_id AND (active.status='RUNNING' OR (active.status='FAILED' AND active.progress_at IS NOT NULL AND active.finalization_phase<>'COMPLETE')))>=b.parallelism)) ORDER BY p.id", ids.toArray());
            for (Map<String, Object> blocker : batchBlockers) {
                PlatformModels.Execution execution = byId.get(((Number) blocker.get("waiting_id")).longValue());
                if ("CASE".equals(execution.waitReason)) continue;
                execution.waitReason = "BATCH";
                execution.blockedByExecutionIds.add(((Number) blocker.get("blocking_id")).longValue());
            }
        }
    }

    public void createTableBackup(long executionId, PlatformModels.TableBackup backup) {
        backup.id = insert("INSERT INTO table_backup(execution_id,datasource_key,table_name,snapshot_name,backup_table,backup_condition,status) VALUES (?,?,?,?,?,?,?)",
                new Object[] {executionId, backup.datasourceKey, backup.tableName, backup.snapshotName, backup.backupTable, backup.backupCondition, backup.status});
    }

    public void updateTableBackup(PlatformModels.TableBackup backup) {
        jdbc.update("UPDATE table_backup SET status=?,row_count=?,error_message=?,identity_state_json=? WHERE id=?",
                backup.status, backup.rowCount, backup.errorMessage, backup.identityStateJson, backup.id);
    }

    public List<Map<String, Object>> listUnfinishedTableBackups() {
        return jdbc.queryForList("SELECT id,execution_id,datasource_key,table_name,backup_table,backup_condition,status,error_message FROM table_backup WHERE status<>'DROPPED' ORDER BY id");
    }

    private void assignCaseGroup(Long caseId, Long groupId) {
        if (groupId != null) findGroup(groupId);
        if (jdbc.queryForObject("SELECT COUNT(1) FROM test_case WHERE id=?", Integer.class, caseId) == 0) throw new IllegalArgumentException("案例不存在");
        List<String> activeNames = jdbc.queryForList("SELECT name FROM test_case WHERE id=? AND " + activeCaseCondition(), String.class, caseId);
        if (!activeNames.isEmpty()) ensureCaseName(groupId, activeNames.get(0), caseId);
        if (groupId == null) {
            jdbc.update("UPDATE test_case SET group_id=NULL,case_no=? WHERE id=?", nextCaseNo(null), caseId);
        } else {
            jdbc.update("UPDATE test_case SET group_id=?,case_no=? WHERE id=?", groupId, nextCaseNo(groupId), caseId);
        }
    }

    private void fillCaseChildren(PlatformModels.TestCase value) {
        value.actions = jdbc.query("SELECT id,action_type,name,description,sort_no,dataset_name,datasource_key,snapshot_name,clear_after_backup,snapshot_rows_json,variables_json,prepare_sql,cleanup_sql,application_name,request_body,assertions_json,query_sql,expected_rows_json,expected_row_count,compare_mode,core_assertion,run_on_case_end,expected_result,response_variables_json FROM test_case_action WHERE case_id=? ORDER BY sort_no", actionMapper(), value.id);
        for (PlatformModels.CaseAction action : value.actions) {
            action.upload = findCaseUpload(action.id);
        }
    }

    public PlatformModels.TestFileAsset findFileAsset(Long id) {
        return jdbc.queryForObject("SELECT id,file_name,content_type,content_sha256,content FROM test_file_asset WHERE id=?", fileAssetMapper(), id);
    }

    private PlatformModels.CaseUpload findCaseUpload(Long actionId) {
        try {
            return jdbc.queryForObject("SELECT upload.action_id,upload.connection_key,upload.asset_id,asset.file_name,asset.content_type,asset.content,upload.path,upload.file_field,upload.headers_json,upload.form_fields_json,upload.assertions_json,upload.response_variables_json,upload.attachment_mode,upload.attachment_sheet_name FROM test_case_upload upload JOIN test_file_asset asset ON asset.id=upload.asset_id WHERE upload.action_id=?", caseUploadMapper(), actionId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private PlatformModels.CaseGroup findGroup(Long id) {
        PlatformModels.CaseGroup value = jdbc.queryForObject("SELECT id,group_no,name,parent_id,sort_no FROM case_group WHERE id=?", groupMapper(), id);
        value.caseIds = directGroupCaseIds(value.id);
        return value;
    }

    private void ensureGroupName(Long parentId, String name, Long excludedId) {
        List<Long> ids = parentId == null
                ? jdbc.query("SELECT id FROM case_group WHERE parent_id IS NULL AND name=?", (rs, row) -> rs.getLong(1), name)
                : jdbc.query("SELECT id FROM case_group WHERE parent_id=? AND name=?", (rs, row) -> rs.getLong(1), parentId, name);
        for (Long id : ids) if (!id.equals(excludedId)) throw new IllegalArgumentException("同一父分组下分组名称重复：" + name);
    }

    private void assertNotDescendant(Long groupId, Long parentId) {
        Long currentId = parentId;
        while (currentId != null) {
            if (groupId.equals(currentId)) throw new IllegalArgumentException("父分组不能是当前分组或其子分组");
            currentId = jdbc.queryForObject("SELECT parent_id FROM case_group WHERE id=?", (rs, row) -> nullableLong(rs, "parent_id"), currentId);
        }
    }

    private void populateGroupTree(PlatformModels.CaseGroup value, Map<Long, List<PlatformModels.CaseGroup>> children, String parentPath, int depth) {
        value.depth = depth;
        value.fullPath = blank(parentPath) ? value.name : parentPath + " / " + value.name;
        value.descendantGroupCount = 0;
        value.subtreeCaseCount = value.caseIds.size();
        List<PlatformModels.CaseGroup> childValues = children.get(value.id);
        if (childValues == null) return;
        for (PlatformModels.CaseGroup child : childValues) {
            populateGroupTree(child, children, value.fullPath, depth + 1);
            value.descendantGroupCount += child.descendantGroupCount + 1;
            value.subtreeCaseCount += child.subtreeCaseCount;
        }
    }

    private Comparator<PlatformModels.CaseGroup> groupOrder() {
        return (first, second) -> {
            if (first.sortNo == null && second.sortNo != null) return 1;
            if (first.sortNo != null && second.sortNo == null) return -1;
            if (first.sortNo != null && !first.sortNo.equals(second.sortNo)) return first.sortNo.compareTo(second.sortNo);
            return first.groupNo.compareTo(second.groupNo);
        };
    }

    private void ensureCaseName(Long groupId, String name, Long excludedId) {
        List<Long> ids = groupId == null
                ? jdbc.query("SELECT id FROM test_case WHERE group_id IS NULL AND name=? AND " + activeCaseCondition(), (rs, row) -> rs.getLong(1), name)
                : jdbc.query("SELECT id FROM test_case WHERE group_id=? AND name=? AND " + activeCaseCondition(), (rs, row) -> rs.getLong(1), groupId, name);
        for (Long id : ids) if (!id.equals(excludedId)) throw new IllegalArgumentException((groupId == null ? "未分组案例名称重复：" : "同一分组下案例名称重复：") + name);
    }

    private Integer nextGroupNo() { return nextNumber("GROUP"); }
    private Integer nextCaseNo(Long groupId) { return nextNumber(groupId == null ? "UNGROUPED_CASE" : "GROUP_CASE_" + groupId); }
    private Integer nextNumber(String scope) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(1) FROM platform_number_sequence WHERE scope=?", Integer.class, scope);
        if (existing == 0) jdbc.update("INSERT INTO platform_number_sequence(scope,next_value) VALUES (?,1)", scope);
        Integer nextValue = jdbc.queryForObject("SELECT next_value FROM platform_number_sequence WHERE scope=?", Integer.class, scope);
        jdbc.update("UPDATE platform_number_sequence SET next_value=? WHERE scope=?", nextValue + 1, scope);
        return nextValue;
    }

    private String snapshotRowsJson(PlatformModels.CaseAction action) {
        if (!"BACKUP".equals(action.actionType)) return null;
        try {
            return ASSERTION_MAPPER.writeValueAsString(SnapshotRules.snapshotItems(action));
        } catch (Exception e) {
            throw new IllegalArgumentException("备份步骤数据格式化失败：" + action.name, e);
        }
    }

    private List<PlatformModels.SnapshotItem> parseSnapshotRows(String json) {
        try {
            return ASSERTION_MAPPER.readValue(json, ASSERTION_MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, PlatformModels.SnapshotItem.class));
        } catch (Exception e) {
            throw new IllegalStateException("备份步骤数据读取失败", e);
        }
    }

    private void insertAction(Long caseId, PlatformModels.CaseAction action) {
        require(action.actionType, "案例步骤类型不能为空");
        require(action.name, "案例步骤名称不能为空");
        if (!"INSERT".equals(action.actionType) && !"UPDATE".equals(action.actionType) && !"UPLOAD".equals(action.actionType) && !"HTTP".equals(action.actionType) && !"ASSERT".equals(action.actionType) && !"CLEANUP".equals(action.actionType) && !"BACKUP".equals(action.actionType) && !"RESTORE".equals(action.actionType)) throw new IllegalArgumentException("案例步骤类型不支持：" + action.actionType);
        String assertionsJson = interfaceAction(action.actionType) ? requireAssertionJson(action.assertionsJson, action.name) : action.assertionsJson;
        Long actionId = insert("INSERT INTO test_case_action(case_id,action_type,name,description,sort_no,dataset_name,datasource_key,snapshot_name,clear_after_backup,snapshot_rows_json,variables_json,prepare_sql,cleanup_sql,application_name,request_body,assertions_json,query_sql,expected_rows_json,expected_row_count,compare_mode,core_assertion,run_on_case_end,expected_result,response_variables_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", new Object[] {caseId, action.actionType, action.name, action.description, number(action.sortNo), action.datasetName, action.datasourceKey, action.snapshotName, action.clearAfterBackup == null ? null : bool(action.clearAfterBackup), snapshotRowsJson(action), action.variablesJson, action.prepareSql, action.cleanupSql, action.applicationName, action.requestBody, assertionsJson, action.querySql, action.expectedRowsJson, action.expectedRowCount, action.compareMode, bool(action.coreAssertion), bool(action.runOnCaseEnd), action.expectedResult, action.responseVariablesJson});
        if ("UPLOAD".equals(action.actionType)) saveCaseUpload(actionId, action.upload);
    }

    private void validateCaseActions(List<PlatformModels.CaseAction> actions) {
        SnapshotRules.validate(emptyIfNull(actions));
        int lastBusinessAction = -1;
        List<PlatformModels.CaseAction> caseActions = emptyIfNull(actions);
        for (int index = 0; index < caseActions.size(); index++) {
            PlatformModels.CaseAction action = caseActions.get(index);
            dev.probeloom.service.CaseWorkbookCodec.validateTableAction(action);
            dev.probeloom.service.HttpCaseRules.validate(action);
            validateExpectedOutcome(action);
            if ("ASSERT".equals(action.actionType) && "ROW_COUNT".equals(action.compareMode)
                    && (action.expectedRowCount == null || action.expectedRowCount < 0)) {
                throw new IllegalArgumentException("期望行数必须是非负整数：" + action.name);
            }
            if ("HTTP".equals(action.actionType) || "UPLOAD".equals(action.actionType)) {
                lastBusinessAction = index;
            }
            if (!Boolean.TRUE.equals(action.coreAssertion)) continue;
            if ("ASSERT".equals(action.actionType)) {
                continue;
            }
            if ("HTTP".equals(action.actionType)) {
                if (!isExpectedFailure(action) && !hasAssertionTemplate(action.assertionsJson)) {
                    throw new IllegalArgumentException("调用接口步骤配置为核心核对，但未配置接口断言：" + action.name);
                }
                continue;
            }
            if ("UPLOAD".equals(action.actionType)) {
                if (action.upload == null || (!isExpectedFailure(action) && !hasAssertionTemplate(action.upload.assertionsJson))) {
                    throw new IllegalArgumentException("上传文件步骤配置为核心核对，但未配置接口断言：" + action.name);
                }
                continue;
            }
            throw new IllegalArgumentException("核心核对只能配置在调用接口、上传文件或数据库断言步骤：" + action.name);
        }
        boolean cleanupStarted = false;
        for (int index = lastBusinessAction + 1; index < caseActions.size(); index++) {
            PlatformModels.CaseAction action = caseActions.get(index);
            if ("CLEANUP".equals(action.actionType)) {
                cleanupStarted = true;
            } else if (cleanupStarted && "ASSERT".equals(action.actionType)) {
                throw new IllegalArgumentException("最终清理步骤之后不允许配置数据库断言");
            }
        }
    }

    private void saveCaseUpload(Long actionId, PlatformModels.CaseUpload upload) {
        if (upload == null) throw new IllegalArgumentException("上传步骤缺少文件配置");
        require(upload.connection, "上传步骤缺少连接标识");
        require(upload.path, "上传步骤缺少接口路径");
        require(upload.fileName, "上传步骤缺少文件名");
        require(upload.contentType, "上传步骤缺少文件类型");
        require(upload.contentBase64, "上传步骤缺少文件内容");
        byte[] content;
        try { content = Base64.getDecoder().decode(upload.contentBase64); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("上传步骤文件内容不是有效 Base64", e); }
        if (content.length == 0) throw new IllegalArgumentException("上传步骤文件不能为空");
        String sha256 = sha256(content);
        Long assetId;
        try {
            assetId = jdbc.queryForObject("SELECT id FROM test_file_asset WHERE content_sha256=?", Long.class, sha256);
        } catch (EmptyResultDataAccessException e) {
            assetId = insert("INSERT INTO test_file_asset(file_name,content_type,content_sha256,content) VALUES (?,?,?,?)", new Object[] {upload.fileName, upload.contentType, sha256, content});
        }
        String assertionsJson = requireAssertionJson(upload.assertionsJson, "上传步骤接口断言");
        jdbc.update("INSERT INTO test_case_upload(action_id,asset_id,connection_key,path,file_field,headers_json,form_fields_json,assertions_json,response_variables_json,attachment_mode,attachment_sheet_name) VALUES (?,?,?,?,?,?,?,?,?,?,?)", actionId, assetId, upload.connection, upload.path, blank(upload.fileField) ? "file" : upload.fileField, upload.headersJson, upload.formFieldsJson, assertionsJson, upload.responseVariablesJson, blank(upload.attachmentMode) ? "BASE64" : upload.attachmentMode, upload.attachmentSheetName);
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException("计算文件摘要失败", e); }
    }

    private String environmentSql() { return "SELECT environment.id,environment.name,environment.refapp,environment.is_default,environment.connections_json FROM test_environment environment"; }
    private String caseSelect() { return "SELECT test_case.id,test_case.group_id,case_group.group_no,case_group.name AS group_name,test_case.case_no,test_case.name,test_case.description,test_case.enabled FROM test_case LEFT JOIN case_group ON case_group.id=test_case.group_id"; }
    private String subtreeCaseSelect() { return "WITH RECURSIVE group_tree(id) AS (SELECT id FROM case_group WHERE id=? UNION ALL SELECT child.id FROM case_group child JOIN group_tree parent ON child.parent_id=parent.id) " + caseSelect() + " JOIN group_tree ON group_tree.id=test_case.group_id"; }
    private String recycledCaseSelect() { return "SELECT test_case.id,test_case.group_id,case_group.group_no,case_group.name AS group_name,test_case.case_no,test_case.name,test_case.description,test_case.enabled,recycle.deleted_at FROM test_case JOIN test_case_recycle_bin recycle ON recycle.case_id=test_case.id LEFT JOIN case_group ON case_group.id=test_case.group_id"; }
    private String activeCaseCondition() { return "NOT EXISTS (SELECT 1 FROM test_case_recycle_bin recycle WHERE recycle.case_id=test_case.id)"; }
    private List<Long> directGroupCaseIds(Long groupId) { return jdbc.query("SELECT id FROM test_case WHERE group_id=? AND " + activeCaseCondition() + " ORDER BY case_no", (rs, row) -> rs.getLong(1), groupId); }
    private List<Long> allGroupCaseIds(Long groupId) { return jdbc.query("SELECT id FROM test_case WHERE group_id=? ORDER BY case_no", (rs, row) -> rs.getLong(1), groupId); }
    private boolean hasTemplate(String value) { return !blank(value) && !"{}".equals(value.trim()); }
    private boolean interfaceAction(String actionType) { return "HTTP".equals(actionType) || "UPLOAD".equals(actionType); }
    private boolean hasAssertionTemplate(String value) {
        if (!hasTemplate(value)) return false;
        try {
            JsonNode root = ASSERTION_MAPPER.readTree(value);
            if (root != null && root.isObject() && root.path("version").asInt() == ASSERTION_FORMAT_VERSION && root.has("expected")) {
                JsonNode expected = root.get("expected");
                return expected != null && (!expected.isObject() || expected.size() > 0);
            }
        } catch (Exception ignored) {
            return true;
        }
        return true;
    }
    private String requireAssertionJson(String source, String label) {
        if (blank(source)) {
            return "{\"version\":2,\"expected\":{}}";
        }
        try {
            JsonNode root = ASSERTION_MAPPER.readTree(source);
            if (root == null || !root.isObject() || root.path("version").asInt() != ASSERTION_FORMAT_VERSION || !root.has("expected")) {
                throw new IllegalArgumentException(label + "必须使用 version=2 的统一接口断言结构");
            }
            return root.toString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " JSON 格式不正确", e);
        }
    }
    private boolean isExpectedFailure(PlatformModels.CaseAction action) { return "FAILURE".equalsIgnoreCase(action.expectedResult); }
    private void validateExpectedOutcome(PlatformModels.CaseAction action) {
        String result = blank(action.expectedResult) ? "SUCCESS" : action.expectedResult.trim().toUpperCase();
        boolean interfaceAction = "UPLOAD".equals(action.actionType);
        if (!interfaceAction && !blank(action.expectedResult) && !"SUCCESS".equals(result)) {
            throw new IllegalArgumentException("只有调用接口或上传文件步骤可以配置预期结果");
        }
        if (!interfaceAction) {
            action.expectedResult = null;
            return;
        }
        if (!"SUCCESS".equals(result) && !"FAILURE".equals(result)) {
            throw new IllegalArgumentException("预期结果只支持 SUCCESS 或 FAILURE：" + action.name);
        }
        action.expectedResult = result;
    }
    private Long insert(final String sql, final Object[] args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[] {"id"});
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, holder);
        return holder.getKey().longValue();
    }

    private Integer number(Integer value) { return value == null ? 0 : value; }
    private Integer enabledFlag(Boolean value) { return Boolean.FALSE.equals(value) ? 0 : 1; }
    private Integer bool(Boolean value) { return Boolean.TRUE.equals(value) ? 1 : 0; }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private boolean same(Object first, Object second) { return first == null ? second == null : first.equals(second); }
    private void require(String value, String message) { if (blank(value)) throw new IllegalArgumentException(message); }
    private void requireId(Long value, String message) { if (value == null) throw new IllegalArgumentException(message); }
    private <T> List<T> emptyIfNull(List<T> value) { return value == null ? Collections.<T>emptyList() : value; }
    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException { return rs.getObject(column) == null ? null : rs.getLong(column); }
    private String groupCode(int groupNo) { return String.format("%02d", groupNo); }
    private String caseCode(Integer groupNo, int caseNo) { return groupNo == null ? String.format("未分组-%03d", caseNo) : groupCode(groupNo) + "-" + String.format("%03d", caseNo); }

    private RowMapper<PlatformModels.Environment> environmentMapper() { return (rs, row) -> { PlatformModels.Environment value = new PlatformModels.Environment(); value.id=rs.getLong("id"); value.name=rs.getString("name"); value.refapp=rs.getString("refapp"); value.defaultEnvironment=rs.getInt("is_default") == 1; try { value.connections=ASSERTION_MAPPER.readValue(rs.getString("connections_json"), new com.fasterxml.jackson.core.type.TypeReference<List<PlatformModels.ProtocolConnection>>() { }); } catch (java.io.IOException e) { throw new IllegalStateException("读取连接配置失败", e); } return value; }; }
    private RowMapper<PlatformModels.TestDataSource> dataSourceMapper(boolean includePassword) { return (rs, row) -> { PlatformModels.TestDataSource value = new PlatformModels.TestDataSource(); value.id=rs.getLong("id"); value.name=rs.getString("name"); value.resourceKey=rs.getString("resource_key"); value.databaseType=rs.getString("database_type"); value.jdbcUrl=rs.getString("jdbc_url"); value.username=rs.getString("username"); value.accessMode=rs.getString("access_mode"); if (includePassword) value.password=cryptoService.decrypt(rs.getString("password_ciphertext")); return value; }; }
    private RowMapper<PlatformModels.FixtureDataset> datasetMapper() { return (rs, row) -> { PlatformModels.FixtureDataset value = new PlatformModels.FixtureDataset(); value.id=rs.getLong("id"); value.name=rs.getString("name"); value.datasourceKey=rs.getString("datasource_key"); value.variablesJson=rs.getString("variables_json"); value.prepareSql=rs.getString("prepare_sql"); value.cleanupSql=rs.getString("cleanup_sql"); return value; }; }
    private RowMapper<PlatformModels.TestCase> caseMapper() { return (rs, row) -> { PlatformModels.TestCase value = new PlatformModels.TestCase(); value.id=rs.getLong("id"); value.groupId=nullableLong(rs,"group_id"); Number groupNo=(Number)rs.getObject("group_no"); value.groupName=rs.getString("group_name"); value.caseNo=rs.getInt("case_no"); value.groupCode=groupNo == null ? null : groupCode(groupNo.intValue()); value.caseCode=caseCode(groupNo == null ? null : groupNo.intValue(), value.caseNo); value.name=rs.getString("name"); value.description=rs.getString("description"); value.enabled=rs.getInt("enabled") == 1; return value; }; }
    private RowMapper<PlatformModels.TestCase> recycledCaseMapper() { return (rs, row) -> { PlatformModels.TestCase value = caseMapper().mapRow(rs, row); value.deletedAt=rs.getString("deleted_at"); return value; }; }
    private RowMapper<PlatformModels.CaseAction> actionMapper() { return (rs, row) -> { PlatformModels.CaseAction value = new PlatformModels.CaseAction(); value.id=rs.getLong("id"); value.actionType=rs.getString("action_type"); value.name=rs.getString("name"); value.description=rs.getString("description"); value.sortNo=rs.getInt("sort_no"); value.datasetName=rs.getString("dataset_name"); value.datasourceKey=rs.getString("datasource_key"); value.snapshotName=rs.getString("snapshot_name"); value.clearAfterBackup=rs.getObject("clear_after_backup") == null ? null : rs.getInt("clear_after_backup") == 1; String snapshotRowsJson = rs.getString("snapshot_rows_json"); if ("BACKUP".equals(value.actionType) && snapshotRowsJson != null && !snapshotRowsJson.trim().isEmpty()) value.snapshotRows = parseSnapshotRows(snapshotRowsJson); if ("BACKUP".equals(value.actionType) && value.snapshotRows.isEmpty()) value.snapshotRows = SnapshotRules.snapshotItems(value); value.variablesJson=rs.getString("variables_json"); value.prepareSql=rs.getString("prepare_sql"); value.cleanupSql=rs.getString("cleanup_sql"); value.applicationName=rs.getString("application_name"); value.requestBody=rs.getString("request_body"); value.assertionsJson=rs.getString("assertions_json"); value.querySql=rs.getString("query_sql"); value.expectedRowsJson=rs.getString("expected_rows_json"); value.expectedRowCount=rs.getObject("expected_row_count") == null ? null : rs.getLong("expected_row_count"); value.compareMode=rs.getString("compare_mode"); value.coreAssertion=rs.getInt("core_assertion") == 1; value.runOnCaseEnd=rs.getInt("run_on_case_end") == 1; value.expectedResult=rs.getString("expected_result"); value.responseVariablesJson=rs.getString("response_variables_json"); return value; }; }
    private RowMapper<PlatformModels.TestFileAsset> fileAssetMapper() { return (rs, row) -> { PlatformModels.TestFileAsset value = new PlatformModels.TestFileAsset(); value.id=rs.getLong("id"); value.fileName=rs.getString("file_name"); value.contentType=rs.getString("content_type"); value.contentSha256=rs.getString("content_sha256"); value.content=rs.getBytes("content"); return value; }; }
    private RowMapper<PlatformModels.CaseUpload> caseUploadMapper() { return (rs, row) -> { PlatformModels.CaseUpload value = new PlatformModels.CaseUpload(); value.actionId=rs.getLong("action_id"); value.assetId=rs.getLong("asset_id"); value.fileName=rs.getString("file_name"); value.contentType=rs.getString("content_type"); value.contentBase64=Base64.getEncoder().encodeToString(rs.getBytes("content")); value.connection=rs.getString("connection_key"); value.path=rs.getString("path"); value.fileField=rs.getString("file_field"); value.headersJson=rs.getString("headers_json"); value.formFieldsJson=rs.getString("form_fields_json"); value.assertionsJson=rs.getString("assertions_json"); value.responseVariablesJson=rs.getString("response_variables_json"); value.attachmentMode=rs.getString("attachment_mode"); value.attachmentSheetName=rs.getString("attachment_sheet_name"); return value; }; }
    private RowMapper<PlatformModels.CaseGroup> groupMapper() { return (rs, row) -> { PlatformModels.CaseGroup value = new PlatformModels.CaseGroup(); value.id=rs.getLong("id"); value.groupNo=rs.getInt("group_no"); value.groupCode=groupCode(value.groupNo); value.name=rs.getString("name"); value.parentId=nullableLong(rs,"parent_id"); Number sortNo=(Number)rs.getObject("sort_no"); value.sortNo=sortNo == null ? null : sortNo.intValue(); return value; }; }
    private RowMapper<PlatformModels.SchedulePlan> scheduleMapper() { return (rs, row) -> { PlatformModels.SchedulePlan value = new PlatformModels.SchedulePlan(); value.id=rs.getLong("id"); value.groupId=rs.getLong("group_id"); value.environmentId=nullableLong(rs,"environment_id"); value.name=rs.getString("name"); value.cronExpression=rs.getString("cron_expression"); value.enabled=rs.getInt("enabled") == 1; return value; }; }
    private RowMapper<PlatformModels.Execution> executionMapper() { return (rs, row) -> { PlatformModels.Execution value = new PlatformModels.Execution(); value.id=rs.getLong("id"); value.batchId=nullableLong(rs,"batch_id"); value.batchPosition=rs.getObject("batch_position")==null?null:rs.getInt("batch_position"); value.batchParallelism=rs.getObject("batch_parallelism")==null?null:rs.getInt("batch_parallelism"); value.caseId=nullableLong(rs,"case_id"); value.targetActionId=nullableLong(rs,"target_action_id"); value.groupId=nullableLong(rs,"group_id"); value.environmentId=nullableLong(rs,"environment_id"); value.triggerType=rs.getString("trigger_type"); value.status=rs.getString("status"); value.startedAt=rs.getString("started_at"); value.finishedAt=rs.getString("finished_at"); value.errorMessage=rs.getString("error_message"); value.message=rs.getString("message"); value.submittedBy=nullableLong(rs,"submitted_by"); value.submittedByName=rs.getString("submitted_by_name"); value.queuedAt=rs.getString("queued_at"); value.finalizationPhase=rs.getString("finalization_phase"); value.finalizationState=rs.getString("finalization_state"); value.finalizationError=rs.getString("finalization_error"); value.operationConfirmation=rs.getString("operation_confirmation"); value.finalizationDeadlineAt=rs.getString("finalization_deadline_at"); value.remoteOutcomeUnknown=rs.getInt("remote_outcome_unknown")==1; value.heartbeatAt=rs.getString("heartbeat_at"); value.progressAt=rs.getString("progress_at"); return value; }; }
    private RowMapper<PlatformModels.StepExecution> stepExecutionMapper() { return (rs, row) -> { PlatformModels.StepExecution value = new PlatformModels.StepExecution(); value.id=rs.getLong("id"); value.sortNo=rs.getInt("sort_no"); value.caseEnd=rs.getInt("case_end_execution") == 1; value.status=rs.getString("status"); value.requestBody=rs.getString("request_body"); value.responseBody=rs.getString("response_body"); value.resolvedVariablesJson=rs.getString("resolved_variables_json"); value.errorMessage=rs.getString("error_message"); Number duration=(Number)rs.getObject("duration_ms"); value.durationMs=duration == null ? null : duration.longValue(); return value; }; }
    private RowMapper<PlatformModels.AssertionExecution> assertionExecutionMapper() { return (rs, row) -> { PlatformModels.AssertionExecution value = new PlatformModels.AssertionExecution(); value.id=rs.getLong("id"); Number stepSortNo=(Number)rs.getObject("step_sort_no"); value.stepSortNo=stepSortNo == null ? null : stepSortNo.intValue(); value.caseEnd=rs.getInt("case_end_execution") == 1; value.assertionType=rs.getString("assertion_type"); value.status=rs.getString("status"); value.expectedValue=rs.getString("expected_value"); value.actualValue=rs.getString("actual_value"); value.diffMessage=rs.getString("diff_message"); value.expectedTemplate=rs.getString("expected_template"); value.expectedPath=rs.getString("expected_path"); value.expectedDisplayValue=rs.getString("expected_display_value"); return value; }; }
}
