package dev.probeloom.web;

import dev.probeloom.model.PlatformModels;
import dev.probeloom.repository.PlatformRepository;
import javax.servlet.http.HttpServletRequest;
import dev.probeloom.service.AuthService;
import dev.probeloom.service.CaseWorkbookService;
import dev.probeloom.service.ExecutionService;
import dev.probeloom.service.ScheduleService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PlatformController {
    private final PlatformRepository repository;
    private final AuthService authService;
    private final ExecutionService executionService;
    private final ScheduleService scheduleService;
    private final CaseWorkbookService caseWorkbookService;

    public PlatformController(PlatformRepository repository, AuthService authService,
                              ExecutionService executionService, ScheduleService scheduleService, CaseWorkbookService caseWorkbookService) {
        this.repository = repository;
        this.authService = authService;
        this.executionService = executionService;
        this.scheduleService = scheduleService;
        this.caseWorkbookService = caseWorkbookService;
    }

    @PostMapping("/auth/login")
    public ApiResponse<PlatformModels.LoginResult> login(@RequestBody PlatformModels.LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping("/auth/status")
    public ApiResponse<Map<String, Boolean>> authenticationStatus() {
        return ApiResponse.ok(Collections.singletonMap("initializationRequired", authService.initializationRequired()));
    }

    @PostMapping("/auth/initialize")
    public ApiResponse<PlatformModels.LoginResult> initializeAdmin(@RequestBody PlatformModels.InitializeAdminRequest request) {
        return ApiResponse.ok(authService.initializeAdmin(request));
    }

    @GetMapping("/bootstrap")
    public ApiResponse<Map<String, Object>> bootstrap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("executionParallelism", executionService.executionParallelism());
        result.put("environments", repository.listEnvironments());
        result.put("datasources", repository.listDataSources());
        result.put("datasets", repository.listDatasets());
        result.put("cases", Collections.emptyList());
        result.put("groups", repository.listGroups());
        result.put("schedules", repository.listSchedules());
        result.put("executions", repository.listExecutions());
        return ApiResponse.ok(result);
    }



    @GetMapping("/cases/page")
    public ApiResponse<PlatformModels.CasePage> casePage(
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "groupId", required = false) Long groupId,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @ModelAttribute PlatformModels.CaseTableQuery tableQuery) {
        return ApiResponse.ok(repository.listCasesPage(page, pageSize, keyword, groupId, enabled, tableQuery));
    }

    @PostMapping("/cases/applications")
    public ApiResponse<List<String>> caseApplications(@RequestBody List<Long> caseIds) {
        return ApiResponse.ok(repository.caseApplications(caseIds));
    }

    @GetMapping("/cases/summaries")
    public ApiResponse<List<PlatformModels.TestCase>> caseSummaries() {
        return ApiResponse.ok(repository.listCaseSummaries());
    }

    @GetMapping(value = "/case-workbook/template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> caseWorkbookTemplate() {
        return workbook("test-case-template.xlsx", caseWorkbookService.template());
    }

    @GetMapping(value = "/cases/{id}/workbook", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportCaseWorkbook(@PathVariable Long id) {
        return workbook("test-case-" + id + ".xlsx", caseWorkbookService.exportCase(id));
    }

    @PostMapping(value = "/case-workbook/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PlatformModels.TestCase> importCaseWorkbook(@RequestParam("file") MultipartFile file, @RequestParam(value = "groupId", required = false) Long groupId) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("请选择案例工作簿");
        }
        try (InputStream input = file.getInputStream()) {
            return ApiResponse.ok(caseWorkbookService.importCase(input, groupId));
        } catch (IOException e) {
            throw new IllegalArgumentException("读取案例工作簿失败：" + e.getMessage(), e);
        }
    }

    @PostMapping("/environments") public ApiResponse<PlatformModels.Environment> saveEnvironment(@RequestBody PlatformModels.Environment value) { return ApiResponse.ok(repository.saveEnvironment(value)); }
    @PostMapping("/datasources") public ApiResponse<PlatformModels.TestDataSource> saveDatasource(@RequestBody PlatformModels.TestDataSource value) { return ApiResponse.ok(repository.saveDataSource(value)); }
    @PostMapping("/datasets") public ApiResponse<PlatformModels.FixtureDataset> saveDataset(@RequestBody PlatformModels.FixtureDataset value) { return ApiResponse.ok(repository.saveDataset(value)); }
    @PostMapping("/cases") public ApiResponse<PlatformModels.TestCase> saveCase(@RequestBody PlatformModels.TestCase value) { return ApiResponse.ok(repository.saveCase(value)); }
    @PostMapping("/groups") public ApiResponse<PlatformModels.CaseGroup> saveGroup(@RequestBody PlatformModels.CaseGroup value) { return ApiResponse.ok(repository.saveGroup(value)); }

    @PostMapping("/groups/create")
    public ApiResponse<PlatformModels.CaseGroup> createGroup(@RequestBody CreateGroupRequest request) {
        PlatformModels.CaseGroup group = new PlatformModels.CaseGroup();
        group.name = request.name;
        group.parentId = request.parentId;
        return ApiResponse.ok(repository.saveGroup(group));
    }

    public static class CreateGroupRequest {
        public String name;
        public Long parentId;
    }

    /**
     * 删除无子分组的分组，可同时将直属案例移入回收站。
     * 执行顺序：校验分组 → 事务内处理案例及分组 → 重载定时计划 → 返回结果。
     * @param id 分组标识
     * @param deleteCases true 时回收直属案例，默认 false，仅解除案例归属
     * @return 空结果表示删除完成
     */
    @DeleteMapping("/groups/{id}")
    public ApiResponse<Void> deleteGroup(@PathVariable Long id, @RequestParam(value = "deleteCases", defaultValue = "false") boolean deleteCases) {
        // ========== 1. 数据库操作与调度更新 ==========
        // 案例回收与分组删除同事务提交，提交后重新加载定时计划。
        repository.deleteGroup(id, deleteCases);
        scheduleService.reload();
        return ApiResponse.ok(null);
    }

    @PostMapping("/schedules")
    public ApiResponse<PlatformModels.SchedulePlan> saveSchedule(@RequestBody PlatformModels.SchedulePlan value) {
        PlatformModels.SchedulePlan saved = repository.saveSchedule(value);
        scheduleService.reload();
        return ApiResponse.ok(saved);
    }

    @PostMapping("/datasources/{id}/test")
    public ApiResponse<Void> testDatasource(@PathVariable Long id) {
        executionService.testConnection(id);
        return ApiResponse.ok(null);
    }





    @PostMapping("/cases/{id}/run")
    public ApiResponse<Long> runCase(@PathVariable Long id, @RequestBody PlatformModels.RunRequest request, HttpServletRequest servletRequest) {
        // ========== 1. 认证归属与入队 ==========
        // 操作人只取服务端会话，案例与环境按请求冻结。
        Map<String, Object> user = currentUser(servletRequest);
        return ApiResponse.ok(executionService.queueCase(id, null, request.environmentId, request.actionId, request.actionIds, "MANUAL", request.environmentIds, (Long) user.get("id"), (String) user.get("username")));
    }

    /**
     * 批量执行案例：按勾选顺序入队，单条失败不阻断后续案例。
     * 执行顺序：校验整批参数和环境，逐条入队，返回每条执行标识或错误。
     * @param request 必填案例标识列表及运行环境标识
     * @return 与请求顺序一致的逐条提交结果
     */
    @PostMapping("/cases/run-batch")
    public ApiResponse<List<PlatformModels.BatchRunItem>> runCases(@RequestBody PlatformModels.BatchRunRequest request, HttpServletRequest servletRequest) {
        // ========== 1. 业务处理与返回 ==========
        // 服务先校验并冻结整批输入，再同事务入队；单条准备错误由服务记录。
        Map<String, Object> user = currentUser(servletRequest);
        return ApiResponse.ok(executionService.queueCases(request.caseIds, request.environmentId, request.environmentIds, request.parallelism, (Long) user.get("id"), (String) user.get("username")));
    }

    /**
     * 查询本次执行状态：按明确执行标识查询摘要，供批量列表轮询。
     * 执行顺序：校验执行标识列表，查询对应记录，返回状态摘要。
     * @param request 必填执行标识列表
     * @return 对应执行记录，不受最近执行条数限制
     */
    @PostMapping("/executions/status")
    public ApiResponse<List<PlatformModels.Execution>> executionStatuses(@RequestBody PlatformModels.ExecutionStatusRequest request) {
        // ========== 1. 数据库查询与返回 ==========
        // 仅查询执行摘要，步骤、断言及备份内容由详情接口读取。
        return ApiResponse.ok(repository.executionStatuses(request.executionIds));
    }

    /**
     * 回收案例：将案例从管理列表移入回收站，保留步骤和上传文件以便恢复。
     *
     * 执行顺序：1）按案例标识定位有效案例；2）写入回收站；3）返回处理结果。
     *
     * @param id 案例标识
     * @return 空结果表示已移入回收站
     */
    @DeleteMapping("/cases/{id}")
    public ApiResponse<Void> deleteCase(@PathVariable Long id) {
        // ========== 1. 数据库操作 ==========
        // Step1: 只标记回收状态，保留原案例内容用于恢复。
        repository.deleteCase(id);
        return ApiResponse.ok(null);
    }

    /**
     * 批量回收案例：所选案例全部移入回收站，保留步骤、文件和执行记录。
     * 执行顺序：校验非空 → 按有效案例批量写入 → 核对数量并提交；失败整体回滚。
     * @param caseIds 非空且不重复的案例标识列表，支持启用及停用案例
     * @return 空结果表示全部案例已移入回收站
     */
    @PostMapping("/cases/delete-batch")
    public ApiResponse<Void> deleteCases(@RequestBody List<Long> caseIds) {
        // ========== 1. 校验与数据库操作 ==========
        // 由仓储统一校验及回收，避免逐条请求造成部分删除。
        repository.deleteCases(caseIds);
        return ApiResponse.ok(null);
    }

    /**
     * 恢复案例：移除回收站标记，使原案例重新出现在案例管理列表。
     *
     * 执行顺序：1）校验案例位于回收站；2）移除回收标记；3）返回恢复后的案例。
     *
     * @param id 回收站中的案例标识
     * @return 恢复后的完整案例
     */
    @PostMapping("/cases/{id}/restore")
    public ApiResponse<PlatformModels.TestCase> restoreCase(@PathVariable Long id) {
        // ========== 1. 数据库操作 ==========
        // Step1: 恢复原记录，不重新生成案例、步骤或文件。
        return ApiResponse.ok(repository.restoreCase(id));
    }

    /**
     * 彻底删除案例：仅处理回收站中的案例，删除后不可恢复。
     *
     * @param id 回收站中的案例标识
     * @return 空结果表示案例及其关联数据已删除
     */
    @DeleteMapping("/cases/{id}/recycle-bin")
    public ApiResponse<Void> permanentlyDeleteCase(@PathVariable Long id) {
        repository.permanentlyDeleteCase(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/groups/{id}/run")
    public ApiResponse<List<Long>> runGroup(@PathVariable Long id, @RequestBody PlatformModels.GroupRunRequest request, HttpServletRequest servletRequest) {
        // ========== 1. 认证归属与入队 ==========
        // 同一提交人的分组案例复用统一持久化队列。
        Map<String, Object> user = currentUser(servletRequest);
        return ApiResponse.ok(executionService.queueGroup(id, request.environmentId, "MANUAL", request.environmentIds, request.parallelism, (Long) user.get("id"), (String) user.get("username")));
    }

    @GetMapping("/cases/{id:\\d+}") public ApiResponse<PlatformModels.TestCase> loadCase(@PathVariable Long id) { return ApiResponse.ok(repository.loadCase(id)); }
    @GetMapping("/cases/recycle-bin") public ApiResponse<List<PlatformModels.TestCase>> listRecycledCases() { return ApiResponse.ok(repository.listRecycledCases()); }
    @GetMapping("/executions") public ApiResponse<List<PlatformModels.Execution>> listExecutions() { return ApiResponse.ok(repository.listExecutions()); }
    @GetMapping("/executions/{id}") public ApiResponse<PlatformModels.Execution> loadExecution(@PathVariable Long id) { return ApiResponse.ok(repository.loadExecution(id)); }

    @PostMapping("/executions/{id}/end")
    public ApiResponse<PlatformModels.Execution> endExecution(@PathVariable Long id, HttpServletRequest servletRequest) {
        executionService.endExecution(id, (String) currentUser(servletRequest).get("username"));
        return ApiResponse.ok(repository.loadExecution(id));
    }

    /**
     * 分页查询执行摘要；仅本人筛选取认证会话，其他条件按请求查询。
     * 执行顺序：取得会话身份 → 数据库过滤分页 → 返回摘要，不加载步骤或备份详情。
     * @param mine true 时只查询当前提交人的执行
     * @return 当前页记录与总条数
     */
    @GetMapping("/executions/page")
    public ApiResponse<PlatformModels.ExecutionPage> executionPage(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "mine", defaultValue = "false") boolean mine,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "triggerType", required = false) String triggerType,
            @RequestParam(value = "caseId", required = false) Long caseId,
            @RequestParam(value = "environmentId", required = false) Long environmentId,
            @RequestParam(value = "recoveryPending", defaultValue = "false") boolean recoveryPending,
            HttpServletRequest request) {
        // ========== 1. 认证过滤与数据库查询 ==========
        Long submittedBy = mine ? (Long) currentUser(request).get("id") : null;
        return ApiResponse.ok(repository.pageExecutions(page, pageSize, submittedBy, keyword, status, triggerType, caseId, environmentId, recoveryPending));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> currentUser(HttpServletRequest request) {
        return (Map<String, Object>) request.getAttribute(AuthInterceptor.AUTHENTICATED_USER);
    }

    private ResponseEntity<byte[]> workbook(String filename, byte[] content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(content);
    }
}
