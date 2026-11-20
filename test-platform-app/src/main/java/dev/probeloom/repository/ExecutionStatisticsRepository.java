package dev.probeloom.repository;

import dev.probeloom.config.PlatformStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ExecutionStatisticsRepository {
    private static final ZoneOffset BEIJING = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter UTC_BOUND = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private final JdbcTemplate jdbc;
    private final String resultDay;

    public ExecutionStatisticsRepository(JdbcTemplate platformJdbcTemplate, PlatformStorage storage) {
        jdbc = platformJdbcTemplate;
        resultDay = storage.isJdbc()
                ? "to_char(CAST(SUBSTR(e.finished_at,1,19) AS TIMESTAMP) + INTERVAL '8 hours','YYYY-MM-DD')"
                : "date(SUBSTR(e.finished_at,1,19),'+8 hours')";
    }

    public Map<String, Object> overview(String from, String to, Long groupId, Long caseId, String interfaceKey) {
        Query query = new Query(from, to, groupId, caseId);
        List<Map<String, Object>> points = jdbc.query(query.ranked(interfaceKey)
                + " SELECT result_day,group_id,outcome,COUNT(*) AS amount FROM daily GROUP BY result_day,group_id,outcome ORDER BY result_day",
                (rs, row) -> fields("date", rs.getString("result_day"), "groupId", rs.getLong("group_id"),
                        "outcome", rs.getString("outcome"), "count", rs.getLong("amount")), query.parameters(interfaceKey));
        if (interfaceKey != null) return fields("points", points);
        // 接口范围的“未执行”指接口步骤没有运行，口径不同，只在案例范围统计没跑的案例。
        IdleCases idle = idleCases(query, from, to, groupId, caseId);
        List<Map<String, Object>> notRun = new ArrayList<>();
        idle.daily.forEach((key, amount) -> {
            String[] parts = key.split("\\|");
            notRun.add(fields("date", parts[0], "groupId", Long.parseLong(parts[1]), "count", amount));
        });
        List<Map<String, Object>> neverRun = new ArrayList<>();
        idle.never.forEach((group, amount) -> neverRun.add(fields("groupId", group, "count", amount)));
        return fields("points", points, "notRun", notRun, "neverRun", neverRun);
    }

    /**
     * 没跑的案例：当前范围内已启用、未回收的案例，从首次执行那天起，某天没有完整执行结果即记为当天没跑。
     * date 为空时列出从未执行过的案例。每条附带该日结束前最后一次完整执行，便于判断停跑了多久。
     */
    public Map<String, Object> notRun(String from, String to, Long groupId, Long caseId, String date, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("页码须为正整数，每页条数为 1 至 100");
        Query query = new Query(from, to, groupId, caseId);
        LocalDate target = date == null ? null : day(date);
        if (target != null && (target.isBefore(day(from)) || target.isAfter(day(to)))) throw new IllegalArgumentException("统计日期须在查询区间内");
        IdleCases idle = idleCases(query, from, to, groupId, caseId);
        List<ScopeCase> matched = new ArrayList<>();
        for (ScopeCase item : idle.cases) {
            if (target == null ? item.firstDay == null
                    : item.firstDay != null && !item.firstDay.isAfter(target) && !idle.results.contains(item.id + "|" + target)) matched.add(item);
        }
        String bound = UTC_BOUND.format((target == null ? day(to) : target).plusDays(1).atStartOfDay().toInstant(BEIJING));
        List<ScopeCase> pageItems = matched.subList(Math.min((page - 1) * pageSize, matched.size()), Math.min(page * pageSize, matched.size()));
        Map<Long, Map<String, Object>> lastRuns = new java.util.HashMap<>();
        if (!pageItems.isEmpty()) {
            List<Object> lastArgs = new ArrayList<>();
            StringBuilder ids = new StringBuilder();
            for (ScopeCase item : pageItems) {
                ids.append(ids.length() == 0 ? "?" : ",?");
                lastArgs.add(item.id);
            }
            lastArgs.add(bound);
            jdbc.query("SELECT * FROM (SELECT e.case_id,e.id,e.finished_at,e.status,ROW_NUMBER() OVER (PARTITION BY e.case_id"
                    + " ORDER BY SUBSTR(e.finished_at,1,19) DESC,e.id DESC) AS run_rank FROM test_execution e WHERE e.case_id IN (" + ids + ") AND e.finished_at<?"
                    + " AND e.started_at IS NOT NULL AND e.status IN ('PASSED','FAILED','WARN','INTERRUPTED') AND e.target_action_id IS NULL"
                    + " AND NOT EXISTS (SELECT 1 FROM step_execution s WHERE s.execution_id=e.id AND s.case_end_execution=0 AND s.status='NOT_SELECTED')) last_run"
                    + " WHERE run_rank=1", (org.springframework.jdbc.core.RowCallbackHandler) rs -> lastRuns.put(rs.getLong("case_id"),
                    fields("lastExecutionId", rs.getLong("id"), "lastFinishedAt", rs.getString("finished_at"), "lastStatus", rs.getString("status"))),
                    lastArgs.toArray());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (ScopeCase item : pageItems) {
            Map<String, Object> value = fields("caseId", item.id, "groupId", item.groupId, "caseCode", item.code, "caseName", item.name);
            if (lastRuns.containsKey(item.id)) value.putAll(lastRuns.get(item.id));
            items.add(value);
        }
        return fields("items", items, "total", (long) matched.size(), "page", page, "pageSize", pageSize);
    }

    private IdleCases idleCases(Query query, String from, String to, Long groupId, Long caseId) {
        IdleCases idle = new IdleCases();
        List<Object> args = new ArrayList<>();
        boolean subtree = groupId != null && groupId > 0;
        String sql = subtree ? "WITH RECURSIVE scope_group(id) AS (SELECT id FROM case_group WHERE id=?"
                + " UNION ALL SELECT c.id FROM case_group c JOIN scope_group p ON p.id=c.parent_id) " : "";
        if (subtree) args.add(groupId);
        sql += "SELECT c.id,COALESCE(c.group_id,0) AS group_id,c.case_no,c.name,g.group_no,f.first_started"
                + " FROM test_case c LEFT JOIN case_group g ON g.id=c.group_id"
                + " LEFT JOIN (SELECT case_id,MIN(started_at) AS first_started FROM test_execution WHERE started_at IS NOT NULL GROUP BY case_id) f ON f.case_id=c.id"
                + " WHERE c.recycled=0 AND c.enabled=1";
        if (subtree) sql += " AND c.group_id IN (SELECT id FROM scope_group)";
        else if (groupId != null && groupId == 0) sql += " AND c.group_id IS NULL";
        if (caseId != null) {
            sql += " AND c.id=?";
            args.add(caseId);
        }
        sql += " ORDER BY CASE WHEN g.group_no IS NULL THEN 1 ELSE 0 END,g.group_no,c.case_no,c.id";
        idle.cases = jdbc.query(sql, (rs, row) -> {
            ScopeCase item = new ScopeCase();
            item.id = rs.getLong("id");
            item.groupId = rs.getLong("group_id");
            int groupNo = rs.getInt("group_no");
            item.code = (rs.wasNull() ? "未分组" : String.format("%02d", groupNo)) + String.format("-%03d", rs.getInt("case_no"));
            item.name = rs.getString("name");
            String first = rs.getString("first_started");
            item.firstDay = beijingDay(first);
            return item;
        }, args.toArray());
        jdbc.query(query.ranked(null) + " SELECT case_id,result_day FROM daily",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> idle.results.add(rs.getLong("case_id") + "|" + rs.getString("result_day")),
                query.parameters(null));
        LocalDate start = day(from);
        LocalDate today = LocalDate.now(BEIJING);
        LocalDate end = day(to).isAfter(today) ? today : day(to);
        for (ScopeCase item : idle.cases) {
            if (item.firstDay == null) {
                idle.never.merge(item.groupId, 1L, Long::sum);
                continue;
            }
            for (LocalDate current = item.firstDay.isAfter(start) ? item.firstDay : start; !current.isAfter(end); current = current.plusDays(1)) {
                if (!idle.results.contains(item.id + "|" + current)) idle.daily.merge(current + "|" + item.groupId, 1L, Long::sum);
            }
        }
        return idle;
    }

    private static final class ScopeCase {
        long id;
        long groupId;
        String code;
        String name;
        LocalDate firstDay;
    }

    private static final class IdleCases {
        List<ScopeCase> cases = new ArrayList<>();
        java.util.Set<String> results = new java.util.HashSet<>();
        Map<String, Long> daily = new java.util.TreeMap<>();
        Map<Long, Long> never = new java.util.TreeMap<>();
    }

    public Map<String, Object> interfaces(String from, String to, Long groupId) {
        Query query = new Query(from, to, groupId, null);
        List<String> keys = jdbc.queryForList(query.eligible + " SELECT DISTINCT s.statistics_interface_key FROM eligible e"
                + " JOIN step_execution s ON s.execution_id=e.id WHERE s.statistics_interface_key IS NOT NULL"
                + " ORDER BY s.statistics_interface_key", String.class, query.parameters(null));
        Long runsWithoutSnapshot = jdbc.queryForObject(query.eligible
                + " SELECT COUNT(*) FROM eligible e WHERE NOT EXISTS (SELECT 1 FROM execution_snapshot s WHERE s.execution_id=e.id)",
                Long.class, query.parameters(null));
        return fields("keys", keys, "runsWithoutSnapshot", runsWithoutSnapshot);
    }

    public Map<String, Object> details(String from, String to, Long groupId, Long caseId, String interfaceKey,
                                       String date, String field, String status, boolean byCase, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("页码须为正整数，每页条数为 1 至 100");
        if (!"outcome".equals(field) && !"case".equals(field)) throw new IllegalArgumentException("未知统计结果类型");
        if (status != null && !Arrays.asList("PASSED", "FAILED", "WARN", "INTERRUPTED", "NOT_RUN").contains(status)) {
            throw new IllegalArgumentException("未知统计结果");
        }
        Query query = new Query(from, to, groupId, caseId);
        List<Object> args = new ArrayList<>(Arrays.asList(query.parameters(interfaceKey)));
        String sql = query.ranked(interfaceKey) + ", filtered AS (SELECT * FROM daily WHERE 1=1";
        if (date != null) {
            sql += " AND result_day=?";
            args.add(day(date).toString());
        }
        if (status != null) {
            sql += " AND " + ("case".equals(field) ? "case_status" : "outcome") + "=?";
            args.add(status);
        }
        sql += ")";
        Long total = jdbc.queryForObject(sql + " SELECT " + (byCase ? "COUNT(DISTINCT case_id)" : "COUNT(*)") + " FROM filtered",
                Long.class, args.toArray());
        args.add(pageSize);
        args.add((page - 1L) * pageSize);
        List<Map<String, Object>> items;
        if (byCase) {
            sql += ", summarized AS (SELECT f.*, ROW_NUMBER() OVER (PARTITION BY case_id ORDER BY result_day DESC) AS case_rank,"
                    + count("PASSED", "passed") + ',' + count("FAILED", "failed") + ',' + count("WARN", "warned") + ','
                    + count("INTERRUPTED", "interrupted") + ',' + count("NOT_RUN", "not_run") + ','
                    + "SUM(CASE WHEN case_status='FAILED' THEN 1 ELSE 0 END) OVER (PARTITION BY case_id) AS case_failed FROM filtered f)"
                    + " SELECT * FROM summarized WHERE case_rank=1 ORDER BY failed DESC,case_failed DESC,group_id,case_no,case_id LIMIT ? OFFSET ?";
            items = jdbc.query(sql, (rs, row) -> {
                Map<String, Object> value = row(rs);
                value.put("counts", fields("PASSED", rs.getLong("passed"), "FAILED", rs.getLong("failed"),
                        "WARN", rs.getLong("warned"), "INTERRUPTED", rs.getLong("interrupted"), "NOT_RUN", rs.getLong("not_run")));
                value.put("caseFailed", rs.getLong("case_failed"));
                return value;
            }, args.toArray());
        } else {
            sql += ", paged AS (SELECT * FROM filtered ORDER BY result_day DESC,finished_at DESC,id DESC LIMIT ? OFFSET ?)"
                    + " SELECT p.*,s.id AS failed_step_id,s.sort_no AS failed_sort_no,s.case_end_execution AS failed_case_end,"
                    + " COALESCE(s.error_message,p.error_message) AS failure_message FROM paged p LEFT JOIN step_execution s ON s.id=("
                    + "SELECT failed.id FROM step_execution failed WHERE failed.execution_id=p.id AND failed.status='FAILED'";
            if (interfaceKey != null && !"case".equals(field)) {
                sql += " AND (p.outcome<>'FAILED' OR failed.statistics_interface_key=?)";
                args.add(interfaceKey);
            }
            sql += " ORDER BY failed.case_end_execution,failed.sort_no LIMIT 1) ORDER BY p.result_day DESC,p.finished_at DESC,p.id DESC";
            items = jdbc.query(sql, (rs, row) -> {
                Map<String, Object> value = row(rs);
                value.put("failedStepId", rs.getObject("failed_step_id"));
                value.put("failedSortNo", rs.getObject("failed_sort_no"));
                value.put("failedCaseEnd", rs.getInt("failed_case_end") == 1);
                value.put("errorMessage", rs.getString("failure_message"));
                return value;
            }, args.toArray());
        }
        return fields("items", items, "total", total, "page", page, "pageSize", pageSize);
    }

    private static String count(String status, String alias) {
        return "SUM(CASE WHEN outcome='" + status + "' THEN 1 ELSE 0 END) OVER (PARTITION BY case_id) AS " + alias;
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        int groupNo = rs.getInt("group_no");
        String groupCode = rs.wasNull() ? "未分组" : String.format("%02d", groupNo);
        return fields("executionId", rs.getLong("id"), "caseId", rs.getLong("case_id"), "groupId", rs.getLong("group_id"),
                "caseCode", groupCode + String.format("-%03d", rs.getInt("case_no")), "caseName", rs.getString("case_name"),
                "date", rs.getString("result_day"), "finishedAt", rs.getString("finished_at"),
                "caseStatus", rs.getString("case_status"), "outcome", rs.getString("outcome"));
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    /** 执行时间按 UTC ISO 文本保存，取前 19 位换算为北京时间日期。 */
    private static LocalDate beijingDay(String instant) {
        if (instant == null || instant.length() < 19) return null;
        try {
            return java.time.LocalDateTime.parse(instant.substring(0, 19)).plusHours(8).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate day(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("统计日期须为 YYYY-MM-DD", e);
        }
    }

    private final class Query {
        private final String eligible;
        private final List<Object> args = new ArrayList<>();

        Query(String from, String to, Long groupId, Long caseId) {
            LocalDate start = day(from);
            LocalDate end = day(to);
            if (start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
            if (end.isAfter(start.plusDays(365))) throw new IllegalArgumentException("统计日期区间最多 366 天");
            boolean subtree = groupId != null && groupId > 0;
            String sql = subtree ? "WITH RECURSIVE scope_group(id) AS (SELECT id FROM case_group WHERE id=?"
                    + " UNION ALL SELECT c.id FROM case_group c JOIN scope_group p ON p.id=c.parent_id), " : "WITH ";
            if (subtree) args.add(groupId);
            sql += "eligible AS (SELECT e.id,e.case_id,e.finished_at,e.status AS case_status,e.error_message,"
                    + "c.name AS case_name,c.case_no,COALESCE(c.group_id,0) AS group_id,g.group_no," + resultDay + " AS result_day"
                    + " FROM test_execution e JOIN test_case c ON c.id=e.case_id LEFT JOIN case_group g ON g.id=c.group_id"
                    + " WHERE c.recycled=0 AND c.enabled=1 AND e.finished_at>=? AND e.finished_at<? AND e.started_at IS NOT NULL"
                    + " AND e.status IN ('PASSED','FAILED','WARN','INTERRUPTED') AND e.target_action_id IS NULL"
                    + " AND NOT EXISTS (SELECT 1 FROM step_execution s WHERE s.execution_id=e.id AND s.case_end_execution=0 AND s.status='NOT_SELECTED')";
            args.add(UTC_BOUND.format(start.atStartOfDay().toInstant(BEIJING)));
            args.add(UTC_BOUND.format(end.plusDays(1).atStartOfDay().toInstant(BEIJING)));
            if (subtree) sql += " AND c.group_id IN (SELECT id FROM scope_group)";
            else if (groupId != null && groupId == 0) sql += " AND c.group_id IS NULL";
            else if (groupId != null) throw new IllegalArgumentException("分组编号不合法");
            if (caseId != null) {
                if (caseId <= 0) throw new IllegalArgumentException("案例编号不合法");
                sql += " AND c.id=?";
                args.add(caseId);
            }
            eligible = sql + ")";
        }

        String ranked(String interfaceKey) {
            String sql = eligible;
            if (interfaceKey != null) {
                // 先按冻结接口筛选，再取同案例同日的末次执行。多次调用在同一次整例执行内合成一个结果。
                sql += ", interface_run AS (SELECT s.execution_id,MAX(CASE s.status WHEN 'FAILED' THEN 4 WHEN 'WARN' THEN 3"
                        + " WHEN 'PASSED' THEN 2 WHEN 'INTERRUPTED' THEN 1 ELSE 0 END) AS verdict FROM eligible e"
                        + " JOIN step_execution s ON s.execution_id=e.id WHERE s.statistics_interface_key=? GROUP BY s.execution_id)";
            }
            // ISO 时间的小数位可变。补足小数位后按完成时间排序，同一时刻用执行编号确定顺序。
            sql += ", ranked AS (SELECT e.*, " + (interfaceKey == null ? "e.case_status" : "CASE i.verdict WHEN 4 THEN 'FAILED'"
                    + " WHEN 3 THEN 'WARN' WHEN 2 THEN 'PASSED' WHEN 1 THEN 'INTERRUPTED' ELSE 'NOT_RUN' END") + " AS outcome,"
                    + "ROW_NUMBER() OVER (PARTITION BY e.case_id,e.result_day ORDER BY SUBSTR(e.finished_at,1,19) DESC,"
                    + "SUBSTR(REPLACE(SUBSTR(e.finished_at,21),'Z','') || '000000000',1,9) DESC,e.id DESC) AS day_rank FROM eligible e"
                    + (interfaceKey == null ? "" : " JOIN interface_run i ON i.execution_id=e.id") + ")";
            return sql + ", daily AS (SELECT * FROM ranked WHERE day_rank=1)";
        }

        Object[] parameters(String interfaceKey) {
            List<Object> parameters = new ArrayList<>(args);
            if (interfaceKey != null) parameters.add(interfaceKey);
            return parameters.toArray();
        }
    }
}
