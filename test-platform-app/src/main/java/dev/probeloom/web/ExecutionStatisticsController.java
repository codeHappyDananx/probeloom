package dev.probeloom.web;

import dev.probeloom.repository.ExecutionStatisticsRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/statistics")
public class ExecutionStatisticsController {
    private final ExecutionStatisticsRepository statistics;

    public ExecutionStatisticsController(ExecutionStatisticsRepository statistics) {
        this.statistics = statistics;
    }

    /** 查询指定北京时间日期区间内按直属分组和日期聚合的执行结果，日期两端均包含。 */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(@RequestParam("from") String from, @RequestParam("to") String to,
            @RequestParam(value = "groupId", required = false) Long groupId, @RequestParam(value = "caseId", required = false) Long caseId,
            @RequestParam(value = "interfaceKey", required = false) String interfaceKey) {
        return ApiResponse.ok(statistics.overview(from, to, groupId, caseId, interfaceKey));
    }

    /** 列出统计区间和分组内有整例执行记录的冻结接口标识。 */
    @GetMapping("/interfaces")
    public ApiResponse<Map<String, Object>> interfaces(@RequestParam("from") String from, @RequestParam("to") String to,
            @RequestParam(value = "groupId", required = false) Long groupId) {
        return ApiResponse.ok(statistics.interfaces(from, to, groupId));
    }

    /** 按同一趋势口径查询案例汇总或逐日执行明细。状态条件在日去重后应用。 */
    @GetMapping("/details")
    public ApiResponse<Map<String, Object>> details(@RequestParam("from") String from, @RequestParam("to") String to,
            @RequestParam(value = "groupId", required = false) Long groupId, @RequestParam(value = "caseId", required = false) Long caseId,
            @RequestParam(value = "interfaceKey", required = false) String interfaceKey, @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "field", defaultValue = "outcome") String field, @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "byCase", defaultValue = "false") boolean byCase, @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.ok(statistics.details(from, to, groupId, caseId, interfaceKey, date, field, status, byCase, page, pageSize));
    }

    /** 列出某天没跑的案例（已启用、未回收、当天无完整执行）；不传 date 时列出从未执行过的案例。 */
    @GetMapping("/not-run")
    public ApiResponse<Map<String, Object>> notRun(@RequestParam("from") String from, @RequestParam("to") String to,
            @RequestParam(value = "groupId", required = false) Long groupId, @RequestParam(value = "caseId", required = false) Long caseId,
            @RequestParam(value = "date", required = false) String date, @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.ok(statistics.notRun(from, to, groupId, caseId, date, page, pageSize));
    }
}
