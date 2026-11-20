package dev.probeloom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.probeloom.model.PlatformModels;
import dev.probeloom.repository.PlatformRepository;
import dev.probeloom.repository.ExecutionQueueRepository;
import dev.probeloom.repository.ExecutionFinalizationRepository;
import dev.probeloom.config.PlatformStorage;
import org.springframework.beans.factory.annotation.Value;
import javax.annotation.PostConstruct;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.jayway.jsonpath.JsonPath;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Driver;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@DependsOn("platformStartupCheck")
public class ExecutionService {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionService.class);
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_NOT_SELECTED = "NOT_SELECTED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_PASSED = "PASSED";
    private static final String STATUS_WARN = "WARN";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SKIPPED = "SKIPPED";
    
    private static final Pattern VARIABLE = Pattern.compile("\\\\{\\\\{\\\\s*([a-zA-Z0-9_.-]+)\\\\s*}}".replace("\\\\", "\\"));
    private final PlatformRepository repository;
    private final ObjectMapper objectMapper;
    private final Map<String, ProtocolAdapter> protocolAdapters = new LinkedHashMap<String, ProtocolAdapter>();
    private final HttpUploadClient httpUploadClient = new HttpUploadClient();
    @org.springframework.beans.factory.annotation.Autowired private ExecutionQueueRepository queue;
    @org.springframework.beans.factory.annotation.Autowired private CryptoService crypto;
    @org.springframework.beans.factory.annotation.Autowired private ExecutionFinalizationRepository finalizations;
    @org.springframework.beans.factory.annotation.Autowired private PlatformStorage storage;
    @Value("${platform.supervisor.only:false}") private boolean supervisorOnly;
    @Value("${platform.execution.parallelism:4}") private int parallelism = 4;
    @Value("${platform.execution.timeout-seconds:30}") private int timeoutSeconds = 30;
    @Value("${platform.migration.source.mode:}") private String migrationSourceMode = "";
    private ExecutorService workers;
    private final ScheduledExecutorService dispatcher = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "execution-dispatcher"));
    private final AtomicInteger running = new AtomicInteger();
    private final ThreadLocal<ExecutionInput> currentInput = new ThreadLocal<ExecutionInput>();
    private final Map<Long, Thread> activeCases = new java.util.concurrent.ConcurrentHashMap<Long, Thread>();

    public ExecutionService(PlatformRepository repository, ObjectMapper objectMapper, List<ProtocolAdapter> adapters) {
        this.repository = repository;
        this.objectMapper = objectMapper.copy()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        for (ProtocolAdapter adapter : adapters) {
            if (protocolAdapters.put(adapter.protocol(), adapter) != null) throw new IllegalArgumentException("协议适配器重复：" + adapter.protocol());
        }
    }

    @PostConstruct
    public void startDispatcher() {
        if (!migrationSourceMode.trim().isEmpty() || supervisorOnly) return;
        if (parallelism < 1 || timeoutSeconds < 1) throw new IllegalArgumentException("执行并发数和数据库超时必须为正数");
        workers = Executors.newFixedThreadPool(parallelism, r -> new Thread(r, "execution-worker"));
        dispatcher.scheduleWithFixedDelay(this::dispatch, 0, 200, TimeUnit.MILLISECONDS);
    }

    private void dispatch() {
        try {
            while (running.get() < parallelism && !workers.isShutdown()) {
                PlatformModels.ClaimedExecution claim = queue.claimNext();
                if (claim == null) return;
                running.incrementAndGet();
                try {
                    workers.execute(() -> {
                        try (CaseExecutionLock lock = CaseExecutionLock.acquire(storage, claim.id)) {
                            if (lock == null) return;
                            Map<String, Object> checkpoint = finalizations.load(claim.id);
                            if (!STATUS_RUNNING.equals(checkpoint.get("status")) || !"BUSINESS".equals(checkpoint.get("finalization_phase"))
                                    || !"ACTIVE".equals(checkpoint.get("finalization_state"))) return;
                            activeCases.put(claim.id, Thread.currentThread());
                            executeCase(claim);
                        }
                        catch (Exception e) {
                            logger.error("执行线程异常，保留检查点等待案例收尾，执行：{}", claim.id, e);
                        }
                        finally {
                            currentInput.remove();
                            activeCases.remove(claim.id);
                            Thread.interrupted();
                            running.decrementAndGet();
                        }
                    });
                } catch (RuntimeException e) {
                    running.decrementAndGet();
                    try (CaseExecutionLock lock = CaseExecutionLock.acquire(storage, claim.id)) {
                        if (lock != null) queue.markDispatchFailed(claim.id, "执行线程派发失败：" + safeMessage(e));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("执行队列调度失败，保留数据库状态", e);
        }
    }

    public static class ExecutionInput {
        public PlatformModels.TestCase testCase;
        public Map<String, PlatformModels.Environment> environments = new LinkedHashMap<String, PlatformModels.Environment>();
        public Map<String, PlatformModels.TestDataSource> datasources = new LinkedHashMap<String, PlatformModels.TestDataSource>();
        public List<Long> selectedActionIds = new ArrayList<Long>();
        @com.fasterxml.jackson.annotation.JsonIgnore public boolean remoteOutcomeUnknown;
        @com.fasterxml.jackson.annotation.JsonIgnore public boolean executingEnd;
        @com.fasterxml.jackson.annotation.JsonIgnore public boolean finalizing;
    }

    public long queueCase(Long caseId, Long groupId, Long environmentId, Long actionId, String triggerType) {
        return queueCase(caseId, groupId, environmentId, actionId, null, triggerType);
    }

    public long queueCase(Long caseId, Long groupId, Long environmentId, Long actionId, List<Long> actionIds, String triggerType) {
        return queueCase(caseId, groupId, environmentId, actionId, actionIds, triggerType, Collections.emptyMap());
    }

    public long queueCase(Long caseId, Long groupId, Long environmentId, Long actionId, List<Long> actionIds,
                          String triggerType, Map<String, Long> environmentIds) {
        return queueCase(caseId, groupId, environmentId, actionId, actionIds, triggerType, environmentIds, null, "系统定时任务");
    }

    public long queueCase(Long caseId, Long groupId, Long environmentId, Long actionId, List<Long> actionIds,
                          String triggerType, Map<String, Long> environmentIds, Long userId, String username) {
        return queue.enqueue(prepareCase(caseId, groupId, environmentId, actionId, actionIds, triggerType, environmentIds, userId, username));
    }

    private PlatformModels.ExecutionSubmission prepareCase(Long caseId, Long groupId, Long environmentId, Long actionId, List<Long> actionIds,
                          String triggerType, Map<String, Long> environmentIds, Long userId, String username) {
        PlatformModels.TestCase testCase = repository.loadCase(caseId);
        if (testCase.actions.isEmpty()) {
            throw new IllegalStateException("案例未配置步骤，不能执行");
        }
        List<Long> selectedActionIds = selectedActionIds(testCase.actions, actionId, actionIds);
        if (actionIds != null && actionIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个案例步骤");
        }
        List<PlatformModels.CaseAction> selectedActions = testCase.actions.stream()
                .filter(action -> selectedActionIds.isEmpty() || selectedActionIds.contains(action.id))
                .collect(java.util.stream.Collectors.toList());
        SnapshotRules.validate(selectedActions);
        for (PlatformModels.CaseAction action : selectedActions) CaseWorkbookCodec.validateTableAction(action);
        Map<String, PlatformModels.Environment> environments = resolveEnvironments(selectedActions, environmentId, environmentIds);
        if (!Boolean.TRUE.equals(testCase.enabled)) throw new IllegalStateException("案例已停用");
        ExecutionInput input = new ExecutionInput();
        input.testCase = testCase;
        input.environments = environments;
        input.selectedActionIds = selectedActionIds;
        for (PlatformModels.CaseAction action : testCase.actions) {
            if (action.upload != null) action.upload.contentBase64 = null;
            if (!selectedActionIds.isEmpty() && !selectedActionIds.contains(action.id)) continue;
            if (action.datasourceKey != null && !action.datasourceKey.isEmpty()) {
                input.datasources.put(action.datasourceKey, repository.findDataSource(action.datasourceKey, true));
            }
            if ("HTTP".equals(action.actionType)) {
                HttpCaseRules.validate(action);
            }
            if ("BACKUP".equals(action.actionType)) for (PlatformModels.SnapshotItem item : SnapshotRules.snapshotItems(action)) {
                input.datasources.put(item.datasourceKey, repository.findDataSource(item.datasourceKey, true));
            }
        }
        PlatformModels.ExecutionSubmission submission = new PlatformModels.ExecutionSubmission();
        for (PlatformModels.CaseAction action : testCase.actions) if ("UPLOAD".equals(action.actionType)
                && (selectedActionIds.isEmpty() || selectedActionIds.contains(action.id))
                && !submission.assetIds.contains(action.upload.assetId)) submission.assetIds.add(action.upload.assetId);
        submission.caseId = caseId;
        submission.groupId = groupId;
        submission.environmentId = environments.isEmpty() ? null : environments.values().iterator().next().id;
        submission.targetActionId = actionId;
        submission.triggerType = triggerType;
        submission.submittedBy = userId;
        submission.submittedByName = username;
        Map<String, Long> environmentKey = new java.util.TreeMap<String, Long>();
        environments.forEach((application, environment) -> environmentKey.put(application, environment.id));
        try {
            submission.caseKey = "CASE:" + caseId + ":" + objectMapper.writeValueAsString(environmentKey);
            submission.snapshotCiphertext = crypto.encrypt(objectMapper.writeValueAsString(input));
        } catch (java.io.IOException e) { throw new IllegalStateException("冻结执行输入失败", e); }
        for (PlatformModels.CaseAction action : testCase.actions) {
            PlatformModels.StepExecution step = new PlatformModels.StepExecution();
            step.sortNo = action.sortNo;
            step.status = selectedActionIds.isEmpty() || selectedActionIds.contains(action.id) ? STATUS_PENDING : STATUS_NOT_SELECTED;
            if (STATUS_NOT_SELECTED.equals(step.status)) {
                step.errorMessage = "本次未勾选";
            } else {
                String interfaceKey = ExecutionInterfaceIdentity.key(action);
                if (interfaceKey != null) submission.statisticsInterfaceKeys.put(action.sortNo, interfaceKey);
            }
            submission.steps.add(step);
            if (Boolean.TRUE.equals(action.runOnCaseEnd)) {
                PlatformModels.StepExecution endStep = new PlatformModels.StepExecution();
                endStep.sortNo = action.sortNo;
                endStep.caseEnd = true;
                endStep.status = step.status;
                endStep.errorMessage = step.errorMessage;
                submission.steps.add(endStep);
            }
        }
        return submission;
    }

    public int executionParallelism() {
        return parallelism;
    }

    public List<PlatformModels.BatchRunItem> queueCases(List<Long> caseIds, Long environmentId, Map<String, Long> environmentIds,
                                                       Integer batchParallelism, Long userId, String username) {
        /*
         * ========================================================================
         * 步骤1：校验批量执行请求
         * ========================================================================
         * 1) 案例标识按用户勾选顺序传入，必须非空且不重复。
         * 2) 同一目标应用共用所选环境，各案例按步骤目标应用路由。
         */
        logger.info("开始校验批量执行请求，环境：{}", environmentId);
        // 1.1 校验整批参数，避免部分入队后才发现请求结构错误。
        if (caseIds == null || caseIds.isEmpty()) throw new IllegalArgumentException("请至少选择一个案例");
        if (batchParallelism == null || batchParallelism < 1 || batchParallelism > parallelism) {
            throw new IllegalArgumentException("本批次并发数必须为1至" + parallelism + "的整数；1表示串行执行");
        }
        java.util.Set<Long> uniqueIds = new java.util.HashSet<Long>();
        for (Long caseId : caseIds) {
            if (caseId == null || caseId <= 0 || !uniqueIds.add(caseId)) throw new IllegalArgumentException("案例标识必须是非重复的正整数");
        }
        if (environmentId != null) repository.findEnvironment(environmentId);
        for (Map.Entry<String, Long> entry : environmentIds.entrySet()) {
            PlatformModels.Environment selected = repository.findEnvironment(entry.getValue());
            if (!entry.getKey().equals(environmentApplication(selected.refapp))) throw new IllegalArgumentException("运行环境目标应用不匹配：" + entry.getKey());
        }
        logger.info("批量执行请求校验完成，案例数：{}", caseIds.size());

        /*
         * ========================================================================
         * 步骤2：冻结输入并原子提交批次
         * ========================================================================
         * 1) 在平台事务外准备全部输入；校验失败的案例记录提交错误。
         * 2) 成功准备的案例及本批次并发数同事务入队，提交前不启动。
         */
        logger.info("开始提交批量案例，案例数：{}", caseIds.size());
        List<PlatformModels.BatchRunItem> items = new ArrayList<PlatformModels.BatchRunItem>();
        List<PlatformModels.BatchRunItem> accepted = new ArrayList<PlatformModels.BatchRunItem>();
        List<PlatformModels.ExecutionSubmission> submissions = new ArrayList<PlatformModels.ExecutionSubmission>();
        for (Long caseId : caseIds) {
            // 2.1 每条返回独立的执行标识或错误，前端据此绑定本次结果。
            PlatformModels.BatchRunItem item = new PlatformModels.BatchRunItem();
            item.caseId = caseId;
            try {
                if (!Boolean.TRUE.equals(repository.loadCase(caseId).enabled)) throw new IllegalStateException("案例已停用");
                submissions.add(prepareCase(caseId, null, environmentId, null, null, "MANUAL", environmentIds, userId, username));
                accepted.add(item);
            } catch (RuntimeException e) {
                item.errorMessage = safeMessage(e);
                logger.warn("批量案例提交失败，案例：{}，原因：{}", caseId, item.errorMessage);
            }
            items.add(item);
        }
        // 2.2 批次及全部可执行案例原子落库，返回与勾选顺序对应的执行编号。
        if (!submissions.isEmpty()) {
            List<Long> executionIds = queue.enqueueBatch(submissions, batchParallelism);
            for (int index = 0; index < accepted.size(); index++) accepted.get(index).executionId = executionIds.get(index);
        }
        logger.info("批量案例提交完成，案例数：{}", items.size());
        return items;
    }

    public List<Long> queueGroup(Long groupId, Long environmentId, String triggerType) {
        return queueGroup(groupId, environmentId, triggerType, Collections.emptyMap(), parallelism, null, "系统定时任务");
    }

    public List<Long> queueGroup(Long groupId, Long environmentId, String triggerType, Map<String, Long> environmentIds,
                                 Integer batchParallelism, Long userId, String username) {
        /*
         * ========================================================================
         * 步骤1：冻结分组案例并提交批次
         * ========================================================================
         * 数据源：分组顺序及本次所选环境。
         * 1) 全部输入准备完成后，按指定并发数原子入队。
         * 2) 定时入口明确使用部署并发数，手工入口使用本次选择。
         */
        logger.info("开始准备分组执行，分组：{}，并发数：{}", groupId, batchParallelism);
        if (batchParallelism == null || batchParallelism < 1 || batchParallelism > parallelism) {
            throw new IllegalArgumentException("本批次并发数必须为1至" + parallelism + "的整数；1表示串行执行");
        }
        // 1.1 准备期间不发布部分执行，避免前序案例提前启动。
        List<PlatformModels.ExecutionSubmission> submissions = new ArrayList<PlatformModels.ExecutionSubmission>();
        for (Long caseId : repository.groupCaseIds(groupId)) {
            submissions.add(prepareCase(caseId, groupId, environmentId, null, null, triggerType, environmentIds, userId, username));
        }
        // 1.2 批次设置和冻结执行由平台库同事务提交。
        List<Long> ids = submissions.isEmpty() ? Collections.emptyList() : queue.enqueueBatch(submissions, batchParallelism);
        logger.info("分组执行提交完成，分组：{}，案例数：{}", groupId, ids.size());
        return ids;
    }

    public void testConnection(Long datasourceId) {
        PlatformModels.TestDataSource source = repository.findDataSource(datasourceId, true);
        try (Connection ignored = dataSource(source).getConnection()) {
            // 连接可建立即通过。
        } catch (Exception e) {
            throw new IllegalStateException("数据源连接失败：" + safeMessage(e), e);
        }
    }

    private List<Long> selectedActionIds(List<PlatformModels.CaseAction> actions, Long actionId, List<Long> actionIds) {
        List<Long> selected = actionIds == null ? new ArrayList<Long>() : new ArrayList<Long>(actionIds);
        if (actionId != null) {
            if (actionIds != null && !actionIds.isEmpty()) throw new IllegalArgumentException("不能同时指定单步骤和多个步骤");
            selected.add(actionId);
        }
        if (selected.isEmpty()) return selected;
        List<Long> known = new ArrayList<Long>();
        for (PlatformModels.CaseAction action : actions) known.add(action.id);
        for (Long selectedId : selected) if (selectedId == null || !known.contains(selectedId)) throw new IllegalArgumentException("案例步骤不存在：" + selectedId);
        List<Long> ordered = new ArrayList<Long>();
        for (PlatformModels.CaseAction action : actions) if (selected.contains(action.id)) ordered.add(action.id);
        return ordered;
    }

    static String environmentApplication(String application) {
        return application == null || application.trim().isEmpty() ? "default" : application;
    }

    private Map<String, PlatformModels.Environment> resolveEnvironments(List<PlatformModels.CaseAction> actions,
                                                                       Long environmentId, Map<String, Long> environmentIds) {
        /*
         * ========================================================================
         * 步骤1：按目标应用确定本次执行环境
         * ========================================================================
         * 数据源：案例步骤的应用、执行请求及基础配置中的运行环境。
         * 操作：
         *   1) 同应用步骤共用一个环境；未声明应用的步骤使用 default。
         *   2) 未手选时采用该应用默认环境，未设默认时沿用目录首项。
         *   3) 入队前核对应用归属，保存本次环境快照。
         */
        logger.info("开始解析案例目标应用的运行环境");
        Map<String, PlatformModels.Environment> result = new LinkedHashMap<String, PlatformModels.Environment>();
        List<PlatformModels.Environment> configured = repository.listEnvironments();
        Map<String, Long> selected = new LinkedHashMap<String, Long>(environmentIds);
        // 1.1 单环境入口的选择只作用于该环境所属目标应用。
        if (environmentId != null) {
            PlatformModels.Environment environment = repository.findEnvironment(environmentId);
            selected.putIfAbsent(environmentApplication(environment.refapp), environmentId);
        }
        java.util.Set<String> applications = actions.stream()
                .filter(action -> "HTTP".equals(action.actionType) || "UPLOAD".equals(action.actionType))
                .map(action -> environmentApplication(action.applicationName))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        for (String application : applications) {
            // 1.2 每个目标应用独立解析，不使用其他应用的运行环境。
            PlatformModels.Environment environment = null;
            if (selected.containsKey(application)) {
                environment = repository.findEnvironment(selected.get(application));
                if (!application.equals(environmentApplication(environment.refapp))) throw new IllegalArgumentException("运行环境目标应用不匹配：" + application);
            } else {
                for (PlatformModels.Environment candidate : configured) {
                    if (application.equals(environmentApplication(candidate.refapp))
                            && (environment == null || Boolean.TRUE.equals(candidate.defaultEnvironment))) environment = candidate;
                }
            }
            if (environment == null) throw new IllegalArgumentException("请先配置目标应用【" + application + "】的运行环境");
            result.put(application, environment);
        }
        logger.info("案例运行环境解析完成，目标应用：{}", result.keySet());
        return result;
    }

    private void executeCase(PlatformModels.ClaimedExecution claim) {
        ExecutionInput input;
        try {
            input = objectMapper.readValue(crypto.decrypt(claim.snapshotCiphertext), ExecutionInput.class);
        } catch (Exception e) {
            queue.complete(claim.id, STATUS_FAILED, "读取冻结执行输入失败：" + safeMessage(e));
            return;
        }
        currentInput.set(input);
        TableSnapshotSession snapshots = new TableSnapshotSession(repository, claim.id, timeoutSeconds);
        List<PlatformModels.CaseAction> actions = input.testCase.actions.stream()
                .filter(action -> input.selectedActionIds.isEmpty() || input.selectedActionIds.contains(action.id))
                .collect(java.util.stream.Collectors.toList());
        Map<String, Object> variables = new HashMap<String, Object>();
        try {
            String routingMessage = input.environments.entrySet().stream()
                    .map(entry -> entry.getKey() + "：" + entry.getValue().name)
                    .collect(java.util.stream.Collectors.joining("；", "运行环境：", ""));
            repository.saveExecutionMessage(claim.id, routingMessage);
            ExecutionSummary summary = executeSteps(claim.id, input.environments, actions, variables, snapshots);
            if (summary.businessFailed || summary.cleanupFailed) finalizations.recordFailure(claim.id, "案例步骤失败，详见步骤结果");
        } catch (Exception e) {
            finalizations.recordFailure(claim.id, safeMessage(e));
        } finally {
            Thread.interrupted();
            finishCase(claim.id, input, actions, variables, snapshots);
        }
    }

    void heartbeatCases() {
        activeCases.forEach((id, thread) -> {
            finalizations.heartbeat(id);
            Map<String, Object> checkpoint = finalizations.load(id);
            if ("ENDING".equals(checkpoint.get("finalization_state")) && "BUSINESS".equals(checkpoint.get("finalization_phase"))) thread.interrupt();
        });
    }

    public void endExecution(long id, String operator) {
        String reason = operator + "结束执行";
        if (queue.endQueued(id, reason)) return;
        if (!finalizations.requestEnd(id, reason) && !finalizations.stopRequested(id)) throw new IllegalStateException("执行已经结束");
    }

    /** 独立监督器只接续收尾，不重放案例业务步骤。 */
    void recoverCase(long id) throws Exception {
        try (CaseExecutionLock lock = CaseExecutionLock.acquire(storage, id)) {
            if (lock == null) {
                finalizations.requestStop(id);
                return;
            }
            PlatformModels.Execution execution = repository.loadExecution(id);
            if ((!STATUS_RUNNING.equals(execution.status) && !STATUS_FAILED.equals(execution.status)) || "COMPLETE".equals(execution.finalizationPhase)) return;
            Map<String, Object> checkpoint = finalizations.load(id);
            if (finalizations.expired(checkpoint)) {
                finalizations.requestEnd(id, "阻塞超过10分钟，自动结束执行");
                checkpoint = finalizations.load(id);
            }
            boolean ending = "ENDING".equals(checkpoint.get("finalization_state"));
            if (!ending && "WAITING".equals(checkpoint.get("finalization_state"))) return;
            // 活动阶段失联先进入有限等待；到期或手动结束后接续还原。
            if (!ending && "RESTORE".equals(checkpoint.get("finalization_phase"))
                    && !"PENDING".equals(checkpoint.get("finalization_state"))
                    && execution.backups.stream().anyMatch(backup -> !java.util.Arrays.asList("RESTORED", "DROP_FAILED", "DROPPED").contains(backup.status))) {
                finalizations.awaitOperation(id, "备份还原期间执行者失联，等待结束并接续还原");
                return;
            }
            if (!ending && "DROP_BACKUPS".equals(checkpoint.get("finalization_phase"))
                    && !"PENDING".equals(checkpoint.get("finalization_state"))
                    && execution.backups.stream().anyMatch(backup -> !"DROPPED".equals(backup.status))) {
                finalizations.awaitOperation(id, "备份删除期间执行者失联，等待结束并接续清理");
                return;
            }
            if ("BUSINESS".equals(checkpoint.get("finalization_phase")) && execution.steps.stream()
                    .noneMatch(step -> !step.caseEnd && (STATUS_RUNNING.equals(step.status) || STATUS_PASSED.equals(step.status) || STATUS_WARN.equals(step.status) || STATUS_FAILED.equals(step.status)))) {
                queue.complete(id, STATUS_FAILED, "执行线程失联，案例尚未开始，未操作业务数据");
                return;
            }
            activeCases.put(id, Thread.currentThread());
            try {
                /*
                 * ========================================================================
                 * 步骤1：核对未完成操作并装载恢复依据
                 * ========================================================================
                 * 数据源：冻结执行输入、持久检查点、步骤结果和 table_backup。
                 * 1) 案例锁排除原执行线程；超时或手动结束只接续备份还原和删除。
                 * 2) 变量与步骤结果同事务保存，只使用本次实际选中的步骤。
                 */
                logger.info("开始核对案例收尾依据，执行：{}", id);
                ExecutionInput input = objectMapper.readValue(crypto.decrypt(queue.snapshot(id)), ExecutionInput.class);
                input.remoteOutcomeUnknown = ((Number) checkpoint.get("remote_outcome_unknown")).intValue() == 1;
                List<PlatformModels.CaseAction> actions = input.testCase.actions.stream()
                        .filter(action -> input.selectedActionIds.isEmpty() || input.selectedActionIds.contains(action.id))
                        .collect(java.util.stream.Collectors.toList());
                for (PlatformModels.StepExecution step : execution.steps) {
                    if (!STATUS_RUNNING.equals(step.status) || ending) continue;
                    // JDBC 操作同样在远端数据库执行，进程退出不能证明未决语句已结束。
                    finalizations.awaitOperation(id, "步骤" + (step.sortNo + 1) + "的操作是否结束尚未确认，保留数据和备份");
                    return;
                }
                Map<String, Object> variables = new HashMap<String, Object>();
                String ciphertext = (String) checkpoint.get("variables_ciphertext");
                if (ciphertext != null) variables = objectMapper.readValue(crypto.decrypt(ciphertext), new TypeReference<Map<String, Object>>() { });
                currentInput.set(input);
                TableSnapshotSession snapshots = new TableSnapshotSession(repository, id, timeoutSeconds);
                for (PlatformModels.TableBackup backup : execution.backups) snapshots.load(backup, executionDataSource(backup.datasourceKey), dataSource(executionDataSource(backup.datasourceKey)));
                if (!ending && "BUSINESS".equals(checkpoint.get("finalization_phase"))) finalizations.recordFailure(id, "案例执行中断，由监督器接续收尾");
                logger.info("案例收尾依据核对完成，执行：{}", id);
                // 1.1 正常结束与监督器接管调用相同执行器，并按阶段检查点继续。
                finishCase(id, input, actions, variables, snapshots);
            } catch (Exception e) {
                if (finalizations.stopRequested(id)) queue.complete(id, STATUS_FAILED, "结束执行时无法装载恢复依据：" + safeMessage(e));
                else finalizations.defer(id, "PENDING", safeMessage(e));
                logger.error("案例恢复依据装载失败，执行：{}", id, e);
            } finally {
                currentInput.remove();
                activeCases.remove(id);
                Thread.interrupted();
            }
        }
    }

    private void finishCase(long id, ExecutionInput input, List<PlatformModels.CaseAction> actions,
                            Map<String, Object> variables, TableSnapshotSession snapshots) {
        /*
         * ========================================================================
         * 步骤2：按持久阶段完成案例收尾
         * ========================================================================
         * 数据源：本次冻结步骤、变量检查点与持久数据备份。
         * 1) 正常执行按清理、恢复、收尾步骤、删除备份推进；清理失败记录后继续。
         * 2) 超时或手动结束只还原成功备份并删除备份表，记录错误后提交终态、释放占用。
         */
        logger.info("开始执行案例收尾，执行：{}", id);
        input.finalizing = true;
        try {
            Map<String, Object> checkpoint = finalizations.load(id);
            boolean ending = "ENDING".equals(checkpoint.get("finalization_state"));
            if (!ending && (input.remoteOutcomeUnknown || ((Number) checkpoint.get("remote_outcome_unknown")).intValue() == 1)) {
                finalizations.awaitOperation(id, "远端请求是否结束尚未确认，保留数据和备份");
                return;
            }
            if (ending) {
                input.remoteOutcomeUnknown = false;
                finalizations.checkpoint(id, variablesCiphertext(variables), false);
            }
            String phase = ending ? "RESTORE" : (String) checkpoint.get("finalization_phase");
            // 接续执行也先登记活动阶段，进程再次中断时不能把未决还原当作可重试失败。
            if (!"BUSINESS".equals(phase)) finalizations.phase(id, phase);
            if ("BUSINESS".equals(phase)) {
                finalizations.phase(id, "CLEANUP");
                phase = "CLEANUP";
            }
            // 2.1 只补做本次选中且尚未执行的清理步骤；已失败的清理不重试。
            if ("CLEANUP".equals(phase)) {
                List<PlatformModels.StepExecution> results = repository.loadExecution(id).steps;
                List<PlatformModels.CaseAction> cleanup = actions.stream().filter(action -> "CLEANUP".equals(action.actionType)
                        && results.stream().anyMatch(step -> !step.caseEnd && step.sortNo.equals(action.sortNo) && STATUS_PENDING.equals(step.status))).collect(java.util.stream.Collectors.toList());
                executeSteps(id, input.environments, cleanup, variables, snapshots);
                if (input.remoteOutcomeUnknown) {
                    finalizations.awaitOperation(id, "清理请求未完成，等待超时结束并还原备份");
                    return;
                }
                finalizations.phase(id, "RESTORE");
                phase = "RESTORE";
            }
            // 2.2 恢复完成后再执行缓存刷新等 runOnCaseEnd 步骤，备份保留至收尾结束。
            if ("RESTORE".equals(phase)) {
                String failure = snapshots.restoreAll();
                if (failure != null) {
                    if (ending) {
                        String dropFailure = snapshots.dropAll();
                        queue.complete(id, STATUS_FAILED, failure + (dropFailure == null ? "" : "\n" + dropFailure));
                        return;
                    }
                    if (snapshots.operationOutcomeUnknown()) {
                        finalizations.awaitOperation(id, failure);
                        return;
                    }
                    finalizations.defer(id, "PENDING", failure);
                    return;
                }
                phase = ending ? "DROP_BACKUPS" : "CASE_END";
                finalizations.phase(id, phase);
            }
            if ("CASE_END".equals(phase)) {
                input.executingEnd = true;
                List<PlatformModels.StepExecution> results = repository.loadExecution(id).steps;
                List<PlatformModels.CaseAction> end = actions.stream().filter(action -> Boolean.TRUE.equals(action.runOnCaseEnd)
                        && results.stream().anyMatch(step -> step.caseEnd && step.sortNo.equals(action.sortNo) && STATUS_PENDING.equals(step.status))).collect(java.util.stream.Collectors.toList());
                executeSteps(id, input.environments, end, variables, snapshots);
                if (input.remoteOutcomeUnknown) {
                    finalizations.awaitOperation(id, "收尾请求是否结束尚未确认，保留备份");
                    return;
                }
                finalizations.phase(id, "DROP_BACKUPS");
                phase = "DROP_BACKUPS";
            }
            // 2.3 数据恢复及收尾步骤结束后，才清理备份并原子提交终态、释放占用。
            if ("DROP_BACKUPS".equals(phase)) {
                String failure = snapshots.dropAll();
                if (failure != null) {
                    if (ending) {
                        queue.complete(id, STATUS_FAILED, failure);
                        return;
                    }
                    if (snapshots.operationOutcomeUnknown()) {
                        finalizations.awaitOperation(id, failure);
                        return;
                    }
                    finalizations.defer(id, "PENDING", failure);
                    return;
                }
                PlatformModels.Execution result = repository.loadExecution(id);
                boolean failed = STATUS_FAILED.equals(result.status) || result.errorMessage != null || result.steps.stream().anyMatch(step -> STATUS_FAILED.equals(step.status));
                boolean warned = result.steps.stream().anyMatch(step -> STATUS_WARN.equals(step.status));
                queue.complete(id, failed ? STATUS_FAILED : warned ? STATUS_WARN : STATUS_PASSED, failed ? (result.errorMessage == null ? "案例步骤或收尾步骤失败，详见步骤结果" : result.errorMessage) : null);
            }
            logger.info("案例收尾执行完成，执行：{}", id);
        } catch (Exception e) {
            if (finalizations.stopRequested(id)) queue.complete(id, STATUS_FAILED, safeMessage(e));
            else finalizations.defer(id, "PENDING", safeMessage(e));
            logger.error("案例收尾出现错误，执行：{}", id, e);
        }
    }

    private String variablesCiphertext(Map<String, Object> variables) throws java.io.IOException {
        return crypto.encrypt(objectMapper.writeValueAsString(variables));
    }

    private PlatformModels.TestDataSource executionDataSource(String key) {
        PlatformModels.TestDataSource source = currentInput.get().datasources.get(key);
        if (source == null) throw new IllegalStateException("冻结执行输入缺少数据源：" + key);
        return source;
    }

    private ExecutionSummary executeSteps(long executionId, Map<String, PlatformModels.Environment> environments,
                                          List<PlatformModels.CaseAction> actions,
                                          Map<String, Object> variables, TableSnapshotSession snapshots) {
        boolean executionFailed = false;
        boolean businessFailed = false;
        boolean cleanupFailed = false;

        for (PlatformModels.CaseAction action : actions) {
            if (currentInput.get().finalizing && finalizations.stopRequested(executionId)) break;
            if (currentInput.get().finalizing && currentInput.get().remoteOutcomeUnknown) break;
            if (!currentInput.get().finalizing && finalizations.stopRequested(executionId)) throw new IllegalStateException("案例已请求中止，转入收尾");
            if (!currentInput.get().executingEnd && (currentInput.get().remoteOutcomeUnknown || (!"CLEANUP".equals(action.actionType) && executionFailed))) {
                // 业务步骤在前置清理或前一业务步骤失败后跳过；后续清理仍按顺序执行。
                updateStepStatus(executionId, action.sortNo, STATUS_SKIPPED, "因前面步骤失败而中断执行");
                continue;
            }

            long actionStarted = System.currentTimeMillis();
            try {
                finalizations.checkpoint(executionId, variablesCiphertext(variables), currentInput.get().remoteOutcomeUnknown);
                updateStepStatus(executionId, action.sortNo, STATUS_RUNNING, null);
                PlatformModels.StepExecution execution = "CLEANUP".equals(action.actionType)
                        ? executeCleanupAction(executionId, action, variables)
                        : "BACKUP".equals(action.actionType) || "RESTORE".equals(action.actionType)
                        ? executeSnapshotAction(action, snapshots, variables)
                        : executeAction(executionId, environments.get(environmentApplication(action.applicationName)), action, variables);
                execution.resolvedVariablesJson = resolvedVariablesJson(variables);
                execution.caseEnd = currentInput.get().executingEnd;
                if (execution.durationMs == null) execution.durationMs = System.currentTimeMillis() - actionStarted;
                finalizations.saveStep(executionId, execution, variablesCiphertext(variables), currentInput.get().remoteOutcomeUnknown);
                if (STATUS_FAILED.equals(execution.status)) {
                    executionFailed = true;
                    if ("CLEANUP".equals(action.actionType)) cleanupFailed = true;
                    else businessFailed = true;
                }
            } catch (Exception e) {
                recordRemoteUncertainty(e);
                executionFailed = true;
                if ("CLEANUP".equals(action.actionType)) cleanupFailed = true;
                else businessFailed = true;
                PlatformModels.StepExecution failed = new PlatformModels.StepExecution();
                failed.caseEnd = currentInput.get().executingEnd;
                failed.sortNo = action.sortNo;
                failed.status = STATUS_FAILED;
                failed.errorMessage = safeMessage(e);
                failed.resolvedVariablesJson = resolvedVariablesJson(variables);
                failed.durationMs = System.currentTimeMillis() - actionStarted;
                try {
                    finalizations.saveStep(executionId, failed, variablesCiphertext(variables), currentInput.get().remoteOutcomeUnknown);
                } catch (Exception persistenceFailure) {
                    throw new IllegalStateException("步骤结果与恢复检查点保存失败", persistenceFailure);
                }
            }
        }
        return new ExecutionSummary(businessFailed, cleanupFailed);
    }

    private PlatformModels.StepExecution executeSnapshotAction(PlatformModels.CaseAction action, TableSnapshotSession snapshots, Map<String, Object> variables) throws Exception {
        PlatformModels.StepExecution execution = new PlatformModels.StepExecution();
        execution.sortNo = action.sortNo;
        if ("BACKUP".equals(action.actionType)) {
            List<PlatformModels.SnapshotItem> items = SnapshotRules.snapshotItems(action);
            List<String> requests = new ArrayList<String>();
            List<String> responses = new ArrayList<String>();
            for (PlatformModels.SnapshotItem template : items) {
                // 备份前冻结变量条件；清空、显式恢复和异常接管均使用快照持久化的同一范围。
                PlatformModels.SnapshotItem item = new PlatformModels.SnapshotItem();
                item.datasourceKey = template.datasourceKey;
                item.datasetName = template.datasetName;
                item.snapshotName = template.snapshotName;
                item.clearAfterBackup = template.clearAfterBackup;
                item.backupCondition = interpolate(template.backupCondition, variables);
                if (template.backupCondition != null && !template.backupCondition.trim().isEmpty()
                        && item.backupCondition.trim().isEmpty()) {
                    throw new IllegalArgumentException("备份条件解析后不能为空");
                }
                SnapshotRules.validateCondition(item.backupCondition);
                PlatformModels.TestDataSource source = executionDataSource(item.datasourceKey);
                requireTestWrite(source);
                requests.add(item.datasourceKey + " / " + item.datasetName + " / " + item.snapshotName
                        + " / 备份条件：" + (item.backupCondition == null || item.backupCondition.trim().isEmpty() ? "整表" : item.backupCondition));
                responses.add(snapshots.backup(item, source, dataSource(source)));
            }
            execution.requestBody = String.join("\n", requests);
            execution.responseBody = String.join("\n", responses);
        } else {
            execution.requestBody = action.datasourceKey + " / " + action.datasetName + " / " + action.snapshotName;
            execution.responseBody = snapshots.restore(action.snapshotName);
        }
        execution.status = STATUS_PASSED;
        return execution;
    }

    private static final class ExecutionSummary {
        private final boolean businessFailed;
        private final boolean cleanupFailed;

        private ExecutionSummary(boolean businessFailed, boolean cleanupFailed) {
            this.businessFailed = businessFailed;
            this.cleanupFailed = cleanupFailed;
        }
    }
    
    private PlatformModels.StepExecution executeAction(long executionId, PlatformModels.Environment environment, PlatformModels.CaseAction action,
                             Map<String, Object> variables) {
        if ("INSERT".equals(action.actionType)) {
            return executeInsertAction(executionId, action, variables);
        } else if ("UPDATE".equals(action.actionType)) {
            return executeUpdateAction(executionId, action, variables);
        } else if ("UPLOAD".equals(action.actionType)) {
            return executeUploadAction(executionId, environment, action, variables);
        } else if ("HTTP".equals(action.actionType)) {
            return executeHttpAction(executionId, environment, action, variables);
        } else if ("ASSERT".equals(action.actionType)) {
            PlatformModels.StepExecution execution = new PlatformModels.StepExecution();
            execution.sortNo = action.sortNo;
            execution.requestBody = interpolate(action.querySql, variables);
            execution.status = executeAssertAction(executionId, action, execution.requestBody, variables).status;
            return execution;
        } else {
            throw new IllegalArgumentException("不支持的案例步骤类型：" + action.actionType);
        }
    }
    
    private void updateStepStatus(long executionId, int sortNo, String status, String errorMessage) {
        repository.updateStepExecutionStatus(executionId, sortNo, status, errorMessage, currentInput.get().executingEnd);
    }

    private PlatformModels.StepExecution executeUploadAction(long executionId, PlatformModels.Environment environment, PlatformModels.CaseAction action,
                                     Map<String, Object> variables) {
        PlatformModels.StepExecution execution = new PlatformModels.StepExecution();
        execution.sortNo = action.sortNo;
        long started = System.currentTimeMillis();
        boolean responseReceived = false;
        try {
            if (action.upload == null || action.upload.assetId == null) {
                throw new IllegalArgumentException("上传步骤缺少文件资产");
            }
            PlatformModels.TestFileAsset asset = repository.findFileAsset(action.upload.assetId);
            Map<String, String> headers = interpolateStrings(readMap(action.upload.headersJson), variables);
            Map<String, String> formFields = interpolateStrings(readMap(action.upload.formFieldsJson), variables);
            String path = interpolate(action.upload.path, variables);
            PlatformModels.CaseUpload upload = objectMapper.convertValue(action.upload, PlatformModels.CaseUpload.class);
            upload.path = path;
            upload.fileField = interpolate(upload.fileField, variables);
            upload.fileName = interpolate(upload.fileName, variables);
            upload.contentType = interpolate(upload.contentType, variables);
            upload.contentBase64 = interpolate(upload.contentBase64, variables);
            /*
             * ========================================================================
             * 步骤1：按冻结连接上传文件
             * ========================================================================
             * 1) 根地址和公共请求头来自本次环境快照，文件来自本次引用的资产。
             * 2) 路径和表单参数按案例变量展开，上传结果交给统一断言处理。
             */
            logger.info("开始上传文件，环境：{}，连接：{}", environment.name, upload.connection);
            // 1.1 只使用案例声明的 HTTP 连接。
            PlatformModels.ProtocolConnection connection = protocolConnection(environment, upload.connection);
            if (!"HTTP".equals(connection.protocol)) throw new IllegalArgumentException("上传操作需要 HTTP 连接");
            for (Map.Entry<String, String> header : connection.headers.entrySet()) {
                headers.keySet().removeIf(name -> name.equalsIgnoreCase(header.getKey()));
                headers.put(header.getKey(), header.getValue());
            }
            // 1.2 记录实际地址并发送文件。
            execution.requestBody = "POST " + HttpProtocolAdapter.resolveUrl(connection.baseUrl, path) + "\n文件：" + asset.fileName;
            HttpUploadClient.UploadResult result = httpUploadClient.upload(connection.baseUrl, upload, asset, headers, formFields);
            logger.info("文件上传完成，环境：{}，连接：{}", environment.name, connection.key);
            responseReceived = true;
            execution.responseBody = limit(result.responseBody);
            assertJson(executionId, action.sortNo, upload.assertionsJson, result.responseBody, variables);
            extractVariables(upload.responseVariablesJson, result.responseBody, variables);
            execution.status = STATUS_PASSED;
        } catch (Exception e) {
            execution.errorMessage = safeMessage(e);
            String responseBody = execution.responseBody == null ? errorResponseBody(e) : execution.responseBody;
            execution.responseBody = limit(responseBody);
            // 已收到响应后的断言或变量提取异常直接失败，只有调用异常参与预期失败匹配。
            execution.status = !responseReceived && !recordRemoteUncertainty(e) && expectedFailure(executionId, action, execution.errorMessage, responseBody, variables) ? STATUS_PASSED : STATUS_FAILED;
        } finally {
            execution.durationMs = System.currentTimeMillis() - started;
        }
        return execution;
    }

    private PlatformModels.StepExecution executeInsertAction(long executionId, PlatformModels.CaseAction action, Map<String, Object> variables) {
        PlatformModels.StepExecution execution = new PlatformModels.StepExecution();
        execution.sortNo = action.sortNo;
        long started = System.currentTimeMillis();
        try {
            CaseWorkbookCodec.validateTableAction(action);
            PlatformModels.TestDataSource source = executionDataSource(action.datasourceKey);
            requireTestWrite(source);
            mergeVariables(action.variablesJson, variables);
            execution.requestBody = limit(interpolate(action.prepareSql, variables));
            executeWrite(source, action.prepareSql, variables, action);
            execution.status = STATUS_PASSED;
            execution.responseBody = "数据准备完成";
        } catch (Exception e) {
            execution.errorMessage = safeMessage(e);
            execution.status = !infrastructureFailure(e) && expectedFailure(executionId, action, execution.errorMessage, variables) ? STATUS_PASSED : STATUS_FAILED;
        } finally {
            execution.durationMs = System.currentTimeMillis() - started;
        }
        return execution;
    }

    private PlatformModels.StepExecution executeUpdateAction(long executionId, PlatformModels.CaseAction action, Map<String, Object> variables) {
        PlatformModels.StepExecution execution = new PlatformModels.StepExecution();
        execution.sortNo = action.sortNo;
        long started = System.currentTimeMillis();
        try {
            CaseWorkbookCodec.validateTableAction(action);
            PlatformModels.TestDataSource source = executionDataSource(action.datasourceKey);
            requireTestWrite(source);
            mergeVariables(action.variablesJson, variables);
            execution.requestBody = limit(interpolate(action.prepareSql, variables));
            executeWrite(source, action.prepareSql, variables);
            execution.status = STATUS_PASSED;
            execution.responseBody = "数据更新完成";
        } catch (Exception e) {
            execution.status = STATUS_FAILED;
            execution.errorMessage = safeMessage(e);
        } finally {
            execution.durationMs = System.currentTimeMillis() - started;
        }
        return execution;
    }

    private PlatformModels.StepExecution executeCleanupAction(long executionId, PlatformModels.CaseAction action, Map<String, Object> variables) {
        PlatformModels.StepExecution execution = new PlatformModels.StepExecution();
        execution.sortNo = action.sortNo;
        long started = System.currentTimeMillis();
        try {
            CaseWorkbookCodec.validateTableAction(action);
            PlatformModels.TestDataSource source = executionDataSource(action.datasourceKey);
            requireTestWrite(source);
            mergeVariables(action.variablesJson, variables);
            execution.requestBody = limit(interpolate(action.cleanupSql, variables));
            executeWrite(source, action.cleanupSql, variables);
            execution.status = STATUS_PASSED;
            execution.responseBody = "数据清理完成";
        } catch (Exception e) {
            execution.status = STATUS_FAILED;
            execution.errorMessage = safeMessage(e);
            // 不再抛出异常，由调用方处理状态更新
        } finally {
            execution.durationMs = System.currentTimeMillis() - started;
        }
        return execution;
    }

    private PlatformModels.StepExecution executeHttpAction(long executionId, PlatformModels.Environment environment, PlatformModels.CaseAction action, Map<String, Object> variables) {
        PlatformModels.StepExecution execution = new PlatformModels.StepExecution();
        execution.sortNo = action.sortNo;
        long started = System.currentTimeMillis();
        ObjectNode request = null;
        try {
            /*
             * ========================================================================
             * 步骤1：发送 HTTP 文本并保留字节证据
             * ========================================================================
             * 1) 按所选环境解析案例连接标识；环境请求头优先于同名案例请求头。
             * 2) 按 Content-Type 的字符集严格编码；原始请求和响应字节保存为 Base64。
             */
            logger.info("开始执行 HTTP 文本步骤，步骤号：{}", action.sortNo);
            HttpCaseRules.validate(action);
            // 1.1 JSON 层替换变量，正文中的空段、空格和尾分隔符保持原值。
            JsonNode config = readJson(interpolateJson(action.requestBody, variables));
            if (!config.isObject()) throw new IllegalArgumentException("HTTP 请求配置必须是 JSON 对象");
            request = (ObjectNode) config;
            PlatformModels.ProtocolConnection connection = protocolConnection(environment, config.get("connection").textValue());
            ProtocolAdapter adapter = protocolAdapters.get(connection.protocol);
            if (adapter == null) throw new IllegalArgumentException("未注册协议适配器：" + connection.protocol);
            // 1.2 适配器按连接配置执行，统一保存请求和响应证据。
            ObjectNode response = adapter.execute(connection, request);
            execution.responseBody = response.toString();
            logger.info("HTTP 文本调用完成，步骤号：{}，HTTP状态：{}", action.sortNo, response.get("status"));

            /*
             * ========================================================================
             * 步骤2：核对响应并提取后续变量
             * ========================================================================
             * 1) HTTP 状态、媒体类型、裸正文按案例期望断言；业务错误也按实际正文核对。
             * 2) 连接、编码和断言异常均失败，不将传输失败算成预期业务失败。
             */
            logger.info("开始核对 HTTP 响应，步骤号：{}", action.sortNo);
            // 2.1 复用统一断言，字符串按完整内容比较。
            assertJson(executionId, action.sortNo, action.assertionsJson, execution.responseBody, variables);
            extractVariables(action.responseVariablesJson, execution.responseBody, variables);
            execution.status = STATUS_PASSED;
            logger.info("HTTP 响应核对完成，步骤号：{}", action.sortNo);
        } catch (Exception e) {
            recordRemoteUncertainty(e);
            execution.status = STATUS_FAILED;
            execution.errorMessage = safeMessage(e);
        } finally {
            if (request != null) {
                JsonNode response = request.remove("response");
                if (execution.responseBody == null && response != null) execution.responseBody = response.toString();
                execution.requestBody = request.toString();
            } else {
                execution.requestBody = action.requestBody;
            }
            execution.durationMs = System.currentTimeMillis() - started;
        }
        return execution;
    }

    private PlatformModels.ProtocolConnection protocolConnection(PlatformModels.Environment environment, String key) {
        if (environment == null) throw new IllegalArgumentException("调用步骤未选择运行环境");
        return environment.connections.stream().filter(item -> item.key.equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("运行环境【" + environment.name + "】未配置连接：" + key));
    }

    private PlatformModels.AssertionExecution executeAssertAction(long executionId, PlatformModels.CaseAction action, String querySql, Map<String, Object> variables) {
        CaseWorkbookCodec.validateTableAction(action);
        if (!isReadSql(querySql)) {
            throw new IllegalArgumentException("数据库断言只允许 SELECT 或 WITH 查询");
        }
        PlatformModels.TestDataSource source = executionDataSource(action.datasourceKey);
        NamedParameterJdbcTemplate jdbc = executionJdbc(dataSource(source));
        MapSqlParameterSource parameters = new MapSqlParameterSource(variables);
        boolean notExists = "NOT_EXISTS".equals(action.compareMode);
        boolean rowCount = "ROW_COUNT".equals(action.compareMode);
        boolean scalar = notExists || rowCount;
        /*
         * ========================================================================
         * 步骤1：读取断言所需结果
         * ========================================================================
         * 1) 行数与存在性由目标数据库计算，只返回一个值。
         * 2) 字段比对或配置了结果变量提取时，读取原查询结果。
         */
        logger.info("开始读取数据库断言结果，步骤：{}，比较方式：{}", action.sortNo, action.compareMode);
        // 1.1 按比较方式和变量声明决定是否读取记录。
        boolean needsRows = !scalar || readJson(action.responseVariablesJson).size() > 0;
        List<Map<String, Object>> actualRows = needsRows
                ? normalizeDatabaseRows(jdbc.queryForList(querySql, parameters)) : Collections.emptyList();
        PlatformModels.AssertionExecution result = new PlatformModels.AssertionExecution();
        Set<String> missingFields = new LinkedHashSet<>();
        boolean passed;
        // 1.2 计算比较结果，标量断言只保存标量。
        if (scalar) {
            long actual = needsRows ? (notExists ? (actualRows.isEmpty() ? 0 : 1) : actualRows.size())
                    : jdbc.queryForObject(scalarAssertionSql(jdbc, source.databaseType, querySql, parameters, notExists), parameters, Long.class);
            if (notExists) {
                passed = actual == 0;
                result.expectedValue = "false";
                result.actualValue = String.valueOf(actual != 0);
            } else {
                passed = action.expectedRowCount == actual;
                result.expectedValue = String.valueOf(action.expectedRowCount);
                result.actualValue = String.valueOf(actual);
            }
            result.expectedTemplate = result.expectedValue;
            result.expectedDisplayValue = result.expectedValue;
        } else {
            JsonNode expectedRows = readJson(interpolateJson(action.expectedRowsJson, variables));
            // SQL 返回记录的列集合一致；未返回的期望字段只警告，行数和已返回值仍参与核对。
            if (!actualRows.isEmpty()) {
                for (JsonNode expectedRow : expectedRows) {
                    expectedRow.fieldNames().forEachRemaining(field -> {
                        if (!actualRows.get(0).containsKey(field)) missingFields.add(field);
                    });
                }
            }
            result.actualValue = limit(canonicalRows(objectMapper.valueToTree(projectRowsToExpectedFields(expectedRows, actualRows))));
            result.expectedValue = limit(canonicalRows(expectedRows));
            result.expectedTemplate = limit(action.expectedRowsJson);
            result.expectedDisplayValue = limit(displayDatabaseExpectedValue(expectedRows, actualRows));
            passed = matchesExpectedRows(expectedRows, actualRows);
        }
        logger.info("读取数据库断言结果完成，步骤：{}，通过：{}", action.sortNo, passed);
        /*
         * ========================================================================
         * 步骤2：保存核对结果并提取变量
         * ========================================================================
         * 1) 保存实际值、期望值和核对状态；未查询字段记警告，核对失败才终止当前步骤。
         * 2) 成功后按配置向后续步骤提供结果变量。
         */
        logger.info("开始保存数据库断言结果，步骤：{}", action.sortNo);
        // 2.1 持久化当前步骤的断言结果。
        result.assertionType = "DATABASE " + action.datasourceKey;
        result.stepSortNo = action.sortNo;
        result.caseEnd = currentInput.get().executingEnd;
        result.status = !passed ? STATUS_FAILED : missingFields.isEmpty() ? STATUS_PASSED : STATUS_WARN;
        result.diffMessage = !passed ? "数据库查询结果与期望不一致"
                : missingFields.isEmpty() ? null : "以下核对字段未查询：" + String.join("、", missingFields);
        result.expectedPath = "$";
        repository.saveAssertionExecution(executionId, result);
        logger.info("保存数据库断言结果完成，步骤：{}，状态：{}", action.sortNo, result.status);
        if (!passed) {
            throw new IllegalStateException(result.diffMessage);
        }
        // 2.2 将声明的查询结果变量传递给后续步骤。
        extractDatabaseVariables(action.responseVariablesJson, actualRows, variables);
        return result;
    }

    private String scalarAssertionSql(NamedParameterJdbcTemplate jdbc, String databaseType, String query,
                                      MapSqlParameterSource parameters, boolean notExists) {
        if ("SQLSERVER".equals(databaseType)) {
            // SQL Server 的子查询排序必须关联 TOP 或 OFFSET；只检查最外层关键字。
            Matcher tokens = Pattern.compile("(?s)'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|\\[(?:\\]\\]|[^\\]])*\\]|--[^\\r\\n]*|/\\*.*?\\*/|[()]|[A-Za-z_][A-Za-z_0-9]*").matcher(query);
            int depth = 0;
            boolean order = false, top = false, offset = false;
            String previous = "";
            while (tokens.find()) {
                String token = tokens.group();
                if ("(".equals(token)) { depth++; continue; }
                if (")".equals(token)) { depth--; continue; }
                if (depth != 0 || !Character.isJavaIdentifierStart(token.charAt(0))) continue;
                if ("BY".equalsIgnoreCase(token) && "ORDER".equalsIgnoreCase(previous)) order = true;
                if ("TOP".equalsIgnoreCase(token)) top = true;
                if ("OFFSET".equalsIgnoreCase(token)) offset = true;
                previous = token;
            }
            String orderedQuery = order && !top && !offset ? query + "\nOFFSET 0 ROWS" : query;
            if (notExists) return "SELECT CASE WHEN EXISTS (\n" + orderedQuery + "\n) THEN 1 ELSE 0 END";
            // 显式列名允许原查询包含常量、表达式和同名输出列，无需读取数据行。
            int columns = jdbc.execute(query, parameters,
                    (PreparedStatementCallback<Integer>) statement -> statement.getMetaData().getColumnCount());
            List<String> names = new ArrayList<>();
            for (int index = 0; index < columns; index++) names.add("c" + index);
            return "WITH platform_assert_rows (" + String.join(",", names) + ") AS (\n" + orderedQuery
                    + "\n) SELECT COUNT_BIG(*) FROM platform_assert_rows";
        }
        return notExists ? "SELECT CASE WHEN EXISTS (\n" + query + "\n) THEN 1 ELSE 0 END"
                : "SELECT COUNT(*) FROM (\n" + query + "\n) platform_assert_rows";
    }

    private void assertJson(long executionId, Integer stepSortNo, String assertionsJson, String responseBody, Map<String, Object> variables) {
        if (assertionsJson == null || assertionsJson.trim().isEmpty()) return;
        JsonNode definition = readInterfaceDefinition(interpolateJson(assertionsJson, variables));
        JsonNode sourceExpected = readInterfaceDefinition(assertionsJson).get("expected");
        JsonNode expected = definition.get("expected");
        if (expected.isObject() && expected.size() == 0) return;
        JsonNode actual = readInterfaceResponse(responseBody);
        boolean passed = sameInterfaceValue(expected, actual, "$");
        saveInterfaceAssertion(executionId, stepSortNo, "接口断言", expected, projectInterfaceValue(expected, actual), passed, sourceExpected, "$");
        saveInterfaceDatasetAssertions(executionId, stepSortNo, expected, actual, "", sourceExpected);
        if (!passed) throw new IllegalStateException("接口断言不一致");
    }

    private JsonNode readInterfaceDefinition(String source) {
        try {
            JsonNode definition = objectMapper.readTree(source);
            if (definition == null || !definition.isObject() || definition.path("version").asInt() != 2
                    || !definition.has("expected") || definition.get("expected") == null) {
                throw new IllegalArgumentException("接口断言必须使用 version=2 的统一 JSON 结构");
            }
            return definition;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("接口断言 JSON 格式不正确：" + safeMessage(e), e);
        }
    }

    private JsonNode readInterfaceResponse(String responseBody) {
        try {
            JsonNode response = objectMapper.readTree(responseBody == null ? "null" : responseBody);
            return response == null ? objectMapper.getNodeFactory().nullNode() : response;
        } catch (Exception e) {
            throw new IllegalArgumentException("接口响应不是有效 JSON：" + safeMessage(e), e);
        }
    }

    private boolean sameInterfaceValue(JsonNode expected, JsonNode actual, String path) {
        if (expected == null || expected.isMissingNode() || expected.isNull()) return actual == null || actual.isNull() || actual.isTextual() && actual.asText().isEmpty();
        if (actual == null || actual.isMissingNode() || actual.isNull()) return expected.isTextual() && expected.asText().isEmpty();
        if (expected.isTextual() && RegexTemplate.contains(expected.asText())) {
            try { return RegexTemplate.matches(expected.asText(), interfaceValueText(actual)); }
            catch (Exception e) { return false; }
        }
        if (expected.isTextual() && expected.asText().isEmpty()) return actual.isTextual() && actual.asText().isEmpty();
        if (expected.isTextual() && actual.isTextual()) {
            JsonNode expectedJson = interfaceJsonText(expected.asText());
            JsonNode actualJson = interfaceJsonText(actual.asText());
            if (expectedJson != null && actualJson != null) return sameInterfaceValue(expectedJson, actualJson, path);
        }
        if (path.endsWith(".errorMessage") && expected.isTextual() && actual.isTextual()) return normalizeErrorText(actual.asText()).contains(normalizeErrorText(expected.asText()));
        if (expected.isNumber() && actual.isNumber()) return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        if (expected.isArray() || actual.isArray()) {
            if (!expected.isArray() || !actual.isArray() || expected.size() != actual.size()) return false;
            boolean[] matched = new boolean[actual.size()];
            for (JsonNode expectedItem : expected) {
                boolean found = false;
                for (int index = 0; index < actual.size(); index++) {
                    if (!matched[index] && sameInterfaceValue(expectedItem, actual.get(index), path + "[]")) {
                        matched[index] = true;
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        }
        if (expected.isObject() || actual.isObject()) {
            if (!expected.isObject() || !actual.isObject()) return false;
            Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!actual.has(field.getKey()) || !sameInterfaceValue(field.getValue(), actual.get(field.getKey()), path + "." + field.getKey())) return false;
            }
            return true;
        }
        if (expected.isTextual() && actual.isTextual() && sameDatePrecision(expected.asText(), actual.asText())) return true;
        if ((expected.isTextual() || expected.isNumber()) && (actual.isTextual() || actual.isNumber())) return interfaceValueText(expected).equals(interfaceValueText(actual));
        return expected.equals(actual);
    }

    private JsonNode interfaceJsonText(String value) {
        String text = value.trim();
        if (!text.startsWith("{") && !text.startsWith("[")) return null;
        try {
            JsonNode parsed;
            try {
                parsed = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            } catch (Exception e) {
                // JSON 文本多转义一层时，按 JSON 字符串解码，保留内容本身的转义字符。
                String decoded = objectMapper.readerFor(String.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue("\"" + text + "\"");
                parsed = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(decoded);
            }
            return parsed.isObject() || parsed.isArray() ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode projectInterfaceValue(JsonNode expected, JsonNode actual) {
        if (actual == null || actual.isMissingNode()) return objectMapper.getNodeFactory().nullNode();
        if (expected == null || expected.isNull()) return actual;
        if (expected.isArray()) {
            if (!actual.isArray()) return actual;
            ArrayNode result = objectMapper.createArrayNode();
            JsonNode itemExpected = expected.size() == 0 ? null : expected.get(0);
            for (JsonNode item : actual) result.add(itemExpected == null ? item : projectInterfaceValue(itemExpected, item));
            return result;
        }
        if (expected.isObject()) {
            if (!actual.isObject()) return actual;
            ObjectNode result = objectMapper.createObjectNode();
            Iterator<String> fields = expected.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                result.set(field, actual.has(field) ? projectInterfaceValue(expected.get(field), actual.get(field)) : objectMapper.getNodeFactory().nullNode());
            }
            return result;
        }
        return actual;
    }

    private void saveInterfaceDatasetAssertions(long executionId, Integer stepSortNo, JsonNode expected, JsonNode actual, String path, JsonNode source) {
        if (expected == null || !expected.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String childPath = path.isEmpty() ? "$." + field.getKey() : path + "." + field.getKey();
            JsonNode actualValue = actual != null && actual.isObject() && actual.has(field.getKey()) ? actual.get(field.getKey()) : objectMapper.getNodeFactory().nullNode();
            JsonNode sourceValue = source != null && source.isObject() && source.has(field.getKey()) ? source.get(field.getKey()) : field.getValue();
            if (field.getValue().isArray()) {
                boolean passed = sameInterfaceValue(field.getValue(), actualValue, childPath);
                saveInterfaceAssertion(executionId, stepSortNo, "数组数据集:" + childPath.substring(2), field.getValue(), projectInterfaceValue(field.getValue(), actualValue), passed, sourceValue, childPath);
                for (int index = 0; index < field.getValue().size(); index++) {
                    JsonNode expectedItem = field.getValue().get(index);
                    JsonNode sourceItem = sourceValue != null && sourceValue.isArray() && index < sourceValue.size() ? sourceValue.get(index) : expectedItem;
                    if (expectedItem.isObject()) saveInterfaceDatasetAssertions(executionId, stepSortNo, expectedItem, actualValue.isArray() && index < actualValue.size() ? actualValue.get(index) : objectMapper.getNodeFactory().nullNode(), childPath + "[]", sourceItem);
                }
            } else if (field.getValue().isObject()) {
                saveInterfaceDatasetAssertions(executionId, stepSortNo, field.getValue(), actualValue, childPath, sourceValue);
            }
        }
    }

    private void saveInterfaceAssertion(long executionId, Integer stepSortNo, String label, JsonNode expected, JsonNode actual, boolean passed, JsonNode source, String path) {
        PlatformModels.AssertionExecution result = new PlatformModels.AssertionExecution();
        result.stepSortNo = stepSortNo;
        result.caseEnd = currentInput.get().executingEnd;
        result.assertionType = "INTERFACE " + label;
        result.status = passed ? STATUS_PASSED : STATUS_FAILED;
        result.expectedValue = expected == null ? "null" : expected.toString();
        result.actualValue = actual == null ? "null" : actual.toString();
        result.diffMessage = passed ? null : "接口断言不一致";
        result.expectedTemplate = source == null ? result.expectedValue : source.toString();
        result.expectedPath = path;
        result.expectedDisplayValue = displayInterfaceExpectedValue(expected, actual);
        repository.saveAssertionExecution(executionId, result);
    }

    private String displayDatabaseExpectedValue(JsonNode expected, List<Map<String, Object>> actualRows) {
        JsonNode actual = objectMapper.valueToTree(actualRows);
        return displayDatabaseExpectedValue(expected, actual).toString();
    }

    private JsonNode displayDatabaseExpectedValue(JsonNode expected, JsonNode actual) {
        if (expected == null || expected.isNull()) return expected;
        if (expected.isTextual() && RegexTemplate.contains(expected.asText())) {
            return actual != null && !actual.isNull() && sameDatabaseValue(expected, actual)
                    ? actual : objectMapper.getNodeFactory().textNode("不匹配");
        }
        if (expected.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            for (int index = 0; index < expected.size(); index++) {
                JsonNode actualItem = actual != null && actual.isArray() && index < actual.size() ? actual.get(index) : objectMapper.getNodeFactory().nullNode();
                result.add(displayDatabaseExpectedValue(expected.get(index), actualItem));
            }
            return result;
        }
        if (expected.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode actualValue = actual != null && actual.isObject() && actual.has(field.getKey()) ? actual.get(field.getKey()) : objectMapper.getNodeFactory().nullNode();
                result.set(field.getKey(), displayDatabaseExpectedValue(field.getValue(), actualValue));
            }
            return result;
        }
        return expected;
    }

    private String displayInterfaceExpectedValue(JsonNode expected, JsonNode actual) {
        return displayInterfaceExpectedValueNode(expected, actual).toString();
    }

    private JsonNode displayInterfaceExpectedValueNode(JsonNode expected, JsonNode actual) {
        if (expected == null || expected.isNull()) return expected;
        if (expected.isTextual() && RegexTemplate.contains(expected.asText())) {
            return actual != null && !actual.isNull() && sameInterfaceValue(expected, actual, "$")
                    ? actual : objectMapper.getNodeFactory().textNode("不匹配");
        }
        if (expected.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            for (int index = 0; index < expected.size(); index++) {
                JsonNode actualItem = actual != null && actual.isArray() && index < actual.size() ? actual.get(index) : objectMapper.getNodeFactory().nullNode();
                result.add(displayInterfaceExpectedValueNode(expected.get(index), actualItem));
            }
            return result;
        }
        if (expected.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode actualValue = actual != null && actual.isObject() && actual.has(field.getKey()) ? actual.get(field.getKey()) : objectMapper.getNodeFactory().nullNode();
                result.set(field.getKey(), displayInterfaceExpectedValueNode(field.getValue(), actualValue));
            }
            return result;
        }
        return expected;
    }

    private String interfaceValueText(JsonNode value) {
        if (value.isNumber()) return value.decimalValue().stripTrailingZeros().toPlainString();
        return value.asText();
    }

    private String normalizeErrorText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private void extractDatabaseVariables(String declarationsJson, List<Map<String, Object>> rows, Map<String, Object> variables) {
        if (declarationsJson == null || declarationsJson.trim().isEmpty()) return;
        try {
            extractVariables(declarationsJson, objectMapper.writeValueAsString(rows), variables);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            throw new IllegalArgumentException("数据库查询结果变量提取失败：" + safeMessage(e), e);
        }
    }

    private void extractVariables(String declarationsJson, String sourceJson, Map<String, Object> variables) {
        if (declarationsJson == null || declarationsJson.trim().isEmpty()) return;
        Map<String, Object> declarations = readMap(interpolateJson(declarationsJson, variables));
        for (Map.Entry<String, Object> declaration : declarations.entrySet()) {
            String path;
            String regex = null;
            boolean count = false;
            if (declaration.getValue() instanceof String) {
                path = (String) declaration.getValue();
            } else if (declaration.getValue() instanceof Map) {
                Map<?, ?> definition = (Map<?, ?>) declaration.getValue();
                path = definition.get("path") instanceof String ? (String) definition.get("path") : "";
                regex = definition.get("regex") instanceof String ? (String) definition.get("regex") : null;
                count = Boolean.TRUE.equals(definition.get("count"));
            } else {
                path = "";
            }
            if (path.trim().isEmpty()) {
                throw new IllegalArgumentException("变量声明 JSONPath 不能为空：" + declaration.getKey());
            }
            Object source;
            try {
                source = objectMapper.readValue(sourceJson, Object.class);
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("变量来源不是有效 JSON", exception);
            }
            Object extracted = JsonPath.read(source, path);
            if (count) {
                if (!(extracted instanceof List)) {
                    throw new IllegalArgumentException("变量计数必须提取数组：" + declaration.getKey());
                }
                extracted = ((List<?>) extracted).size();
            }
            if (extracted == null || extracted instanceof Map || extracted instanceof List) {
                throw new IllegalArgumentException("变量声明必须提取标量值：" + declaration.getKey());
            }
            if (regex != null) {
                Matcher matcher = Pattern.compile(regex).matcher(String.valueOf(extracted));
                if (!matcher.find()) {
                    throw new IllegalArgumentException("变量声明正则未匹配：" + declaration.getKey());
                }
                extracted = matcher.start() == 0 && matcher.end() == String.valueOf(extracted).length()
                        ? matcher.group()
                        : matcher.groupCount() < 1 ? matcher.group() : matcher.group(1);
            }
            variables.put(declaration.getKey(), extracted);
        }
    }

    private boolean isExpectedFailure(PlatformModels.CaseAction action) {
        return "FAILURE".equalsIgnoreCase(action.expectedResult);
    }

    private boolean expectedFailure(long executionId, PlatformModels.CaseAction action, String actualError, Map<String, Object> variables) {
        return expectedFailure(executionId, action, actualError, null, variables);
    }

    private boolean expectedFailure(long executionId, PlatformModels.CaseAction action, String actualError,
                                    String responseBody, Map<String, Object> variables) {
        if (!isExpectedFailure(action)) return false;
        String assertions = action.upload != null ? action.upload.assertionsJson : action.assertionsJson;
        JsonNode definition = readInterfaceDefinition(interpolateJson(assertions, variables));
        JsonNode expected = definition.get("expected");
        JsonNode actual = responseBody == null ? normalizedErrorResponse(actualError) : readInterfaceResponse(responseBody);
        boolean passed = sameInterfaceValue(expected, actual, "$");
        JsonNode sourceExpected = readInterfaceDefinition(assertions).get("expected");
        saveInterfaceAssertion(executionId, action.sortNo, "接口断言", expected, projectInterfaceValue(expected, actual), passed, sourceExpected, "$");
        return passed;
    }

    private JsonNode normalizedErrorResponse(String actualError) {
        return objectMapper.createObjectNode().put("errorMessage", actualError);
    }

    private String errorResponseBody(Exception error) {
        return normalizedErrorResponse(safeMessage(error)).toString();
    }

    private void executeWrite(PlatformModels.TestDataSource source, String sql, Map<String, Object> variables) {
        executeWrite(source, sql, variables, null);
    }

    private void executeWrite(PlatformModels.TestDataSource source, String sql, Map<String, Object> variables, PlatformModels.CaseAction insert) {
        if (sql == null || sql.trim().isEmpty()) return;
        List<String> statements = new ArrayList<String>();
        for (String statement : TableStepSql.split(sql, ';')) {
            if (statement.trim().isEmpty()) continue;
            if (!isDml(statement)) throw new IllegalArgumentException("前置和后置 SQL 只允许 INSERT、UPDATE、DELETE、MERGE");
            statements.add(interpolate(statement, variables));
        }
        /*
         * ========================================================================
         * 步骤1：提交当前步骤的数据库写入
         * ========================================================================
         * 1) 同一步骤共用数据源和事务，任一语句失败由数据库整体回滚。
         * 2) 超时按单次语句设置，不跨接口调用持有连接或事务。
         */
        logger.info("开始执行数据库写入，数据源：{}，语句数：{}", source.resourceKey, statements.size());
        SimpleDriverDataSource datasource = dataSource(source);
        NamedParameterJdbcTemplate jdbc = executionJdbc(datasource);
        org.springframework.transaction.support.TransactionTemplate transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(datasource));
        try {
            transaction.execute(status -> {
                // 1.1 仅在插入字段显式包含数据库自增列时，接管当前连接的编号写入。
                boolean explicitIdentity = false;
                if (insert != null && "SQLSERVER".equals(source.databaseType)) {
                    List<String> fields = TableStepSql.parse(insert).fields;
                    explicitIdentity = jdbc.getJdbcTemplate().query("SELECT TOP 0 * FROM " + insert.datasetName,
                            (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rows -> {
                                java.sql.ResultSetMetaData metadata = rows.getMetaData();
                                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                                    if (metadata.isAutoIncrement(index)) {
                                        for (String field : fields) if (field.equalsIgnoreCase(metadata.getColumnName(index))) return true;
                                    }
                                }
                                return false;
                            });
                }
                if (explicitIdentity) jdbc.getJdbcTemplate().execute("SET IDENTITY_INSERT " + insert.datasetName + " ON");
                // 1.2 开关与数据写入共用事务连接，失败仍关闭开关并由事务回滚。
                try {
                    for (String statement : statements) jdbc.update(statement, new MapSqlParameterSource(variables));
                } finally {
                    if (explicitIdentity) jdbc.getJdbcTemplate().execute("SET IDENTITY_INSERT " + insert.datasetName + " OFF");
                }
                return null;
            });
        } catch (RuntimeException failure) {
            recordRemoteUncertainty(failure);
            throw failure;
        }
        logger.info("数据库写入提交完成，数据源：{}，语句数：{}", source.resourceKey, statements.size());
    }

    private NamedParameterJdbcTemplate executionJdbc(javax.sql.DataSource source) {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(source);
        jdbc.getJdbcTemplate().setQueryTimeout(timeoutSeconds);
        return jdbc;
    }

    SimpleDriverDataSource dataSource(PlatformModels.TestDataSource source) {
        SimpleDriverDataSource result = new SimpleDriverDataSource() {
            @Override public Connection getConnection() throws java.sql.SQLException {
                Connection connection = super.getConnection();
                try {
                    ExecutionDatabase.configure(connection, source.databaseType, timeoutSeconds);
                    return connection;
                } catch (java.sql.SQLException e) {
                    try { connection.close(); } catch (java.sql.SQLException close) { e.addSuppressed(close); }
                    throw e;
                }
            }
        };
        try {
            result.setDriverClass(Class.forName(driverClass(source.databaseType)).asSubclass(Driver.class));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("数据库驱动未加载：" + source.databaseType, e);
        }
        result.setUrl(source.jdbcUrl);
        result.setUsername(source.username);
        result.setPassword(source.password);
        return result;
    }

    private String driverClass(String databaseType) {
        if ("SQLSERVER".equalsIgnoreCase(databaseType)) { return "com.microsoft.sqlserver.jdbc.SQLServerDriver"; }
        if ("POSTGRESQL".equalsIgnoreCase(databaseType)) { return "org.postgresql.Driver"; }
        if ("SQLITE".equalsIgnoreCase(databaseType)) { return "org.sqlite.JDBC"; }
        if ("MYSQL".equalsIgnoreCase(databaseType)) { return "com.mysql.cj.jdbc.Driver"; }
        throw new IllegalArgumentException("不支持的数据库类型：" + databaseType);
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.trim().isEmpty()) { return new LinkedHashMap<String, Object>(); }
        try { return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { }); }
        catch (Exception e) { throw new IllegalArgumentException("JSON 配置格式不正确：" + safeMessage(e), e); }
    }

    private void mergeVariables(String variablesJson, Map<String, Object> variables) {
        for (Map.Entry<String, Object> entry : readMap(variablesJson).entrySet()) {
            variables.put(entry.getKey(), interpolateValue(entry.getValue(), variables));
        }
    }

    private String interpolateJson(String json, Map<String, Object> variables) {
        if (json == null || json.trim().isEmpty()) { return json; }
        try {
            JsonNode resolved = interpolateJsonNode(objectMapper.readTree(json), variables);
            return objectMapper.writeValueAsString(resolved);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 配置变量解析失败：" + safeMessage(e), e);
        }
    }

    private JsonNode interpolateJsonNode(JsonNode node, Map<String, Object> variables) {
        if (node == null || node.isNull()) { return node; }
        if (node.isTextual()) { return objectMapper.valueToTree(interpolateValue(node.asText(), variables)); }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(
                    interpolate(entry.getKey(), variables),
                    interpolateJsonNode(entry.getValue(), variables)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(interpolateJsonNode(value, variables)));
            return result;
        }
        return node;
    }

    private Object interpolateValue(Object value, Map<String, Object> variables) {
        if (value instanceof String) {
            Matcher matcher = VARIABLE.matcher((String) value);
            if (matcher.matches()) {
                Object variable = variables.get(matcher.group(1));
                if (variable == null) { throw new IllegalArgumentException("未定义变量：" + matcher.group(1)); }
                return variable;
            }
            return interpolate((String) value, variables);
        }
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), interpolateValue(entry.getValue(), variables));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) { result.add(interpolateValue(item, variables)); }
            return result;
        }
        return value;
    }

    private Map<String, String> interpolateStrings(Map<String, Object> values, Map<String, Object> variables) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> value : values.entrySet()) {
            if (value.getValue() == null) {
                throw new IllegalArgumentException("上传请求配置不允许 null：" + value.getKey());
            }
            result.put(value.getKey(), String.valueOf(interpolateValue(value.getValue(), variables)));
        }
        return result;
    }

    private JsonNode readJson(String value) {
        try { return objectMapper.readTree(value == null || value.trim().isEmpty() ? "[]" : value); }
        catch (Exception e) { throw new IllegalArgumentException("期望结果必须是 JSON 数组", e); }
    }
    private String canonicalRows(JsonNode rows) {
        if (!rows.isArray()) { throw new IllegalArgumentException("期望结果必须是 JSON 数组"); }
        List<String> values = new ArrayList<String>();
        for (JsonNode row : rows) { values.add(row.toString()); }
        values.sort(Comparator.naturalOrder());
        return values.toString();
    }

    List<Map<String, Object>> normalizeDatabaseRows(List<Map<String, Object>> rows) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalizedRow = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> field : row.entrySet()) {
                Object value = field.getValue();
                normalizedRow.put(field.getKey(), value instanceof java.sql.Timestamp ? dateFormat.format((Date) value)
                        : value instanceof java.sql.Date ? new SimpleDateFormat("yyyy-MM-dd").format((Date) value)
                        : value instanceof java.sql.Time ? new SimpleDateFormat("HH:mm:ss").format((Date) value)
                        : value instanceof Date ? dateFormat.format((Date) value)
                        : value);
            }
            result.add(normalizedRow);
        }
        return result;
    }

    List<Map<String, Object>> projectRowsToExpectedFields(JsonNode expectedRows, List<Map<String, Object>> actualRows) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>(actualRows.size());
        for (Map<String, Object> actualRow : actualRows) {
            Map<String, Object> projected = new LinkedHashMap<String, Object>();
            if (expectedRows.isEmpty()) {
                projected.putAll(actualRow);
                result.add(projected);
                continue;
            }
            for (JsonNode expectedRow : expectedRows) {
                if (!expectedRow.isObject()) continue;
                java.util.Iterator<String> fields = expectedRow.fieldNames();
                while (fields.hasNext()) {
                    String field = fields.next();
                    if (actualRow.containsKey(field)) projected.put(field, actualRow.get(field));
                }
            }
            result.add(projected);
        }
        return result;
    }

    boolean matchesExpectedRows(JsonNode expectedRows, List<Map<String, Object>> actualRows) {
        if (expectedRows.size() != actualRows.size()) return false;
        boolean[] matched = new boolean[actualRows.size()];
        for (JsonNode expectedRow : expectedRows) {
            if (!expectedRow.isObject()) return false;
            boolean found = false;
            for (int index = 0; index < actualRows.size(); index++) {
                if (matched[index]) continue;
                JsonNode actualRow = objectMapper.valueToTree(actualRows.get(index));
                boolean same = true;
                java.util.Iterator<Map.Entry<String, JsonNode>> fields = expectedRow.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (!actualRow.has(field.getKey())) continue;
                    JsonNode actualValue = actualRow.get(field.getKey());
                    if (!sameDatabaseValue(field.getValue(), actualValue)) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private boolean sameDatabaseValue(JsonNode expected, JsonNode actual) {
        if (expected.isTextual() && RegexTemplate.contains(expected.asText())) {
            return !actual.isNull() && RegexTemplate.matches(expected.asText(), databaseValueText(actual));
        }
        boolean expectedEmpty = isNullOrEmptyExpectedValue(expected);
        boolean actualEmpty = isNullOrEmptyDatabaseValue(actual);
        if (expectedEmpty || actualEmpty) return expectedEmpty && actualEmpty;
        if ((expected.isNumber() || expected.isTextual()) && (actual.isNumber() || actual.isTextual())) {
            if (expected.isTextual() && actual.isTextual() && sameDatePrecision(expected.asText(), actual.asText())) return true;
            return databaseValueText(expected).equals(databaseValueText(actual));
        }
        return expected.equals(actual);
    }

    private boolean sameDatePrecision(String expected, String actual) {
        if (expected.matches("\\d{4}") && actual.matches("\\d{4}[-/]\\d{2}[-/]\\d{2}(?:[ T].*)?")) return expected.equals(actual.substring(0, 4));
        if (expected.matches("\\d{4}[-/]\\d{2}[-/]\\d{2}") && actual.matches("\\d{4}[-/]\\d{2}[-/]\\d{2}(?:[ T].*)?")) return expected.equals(actual.substring(0, 10));
        if (expected.matches("\\d{2}:\\d{2}:\\d{2}") && actual.matches("\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}")) return expected.equals(actual.substring(actual.length() - 8));
        return false;
    }

    private boolean isNullOrEmptyExpectedValue(JsonNode value) {
        return value.isNull() || value.isTextual() && value.asText().isEmpty();
    }

    private boolean isNullOrEmptyDatabaseValue(JsonNode value) {
        return value.isNull() || value.isTextual() && value.asText().isEmpty();
    }

    private String databaseValueText(JsonNode value) {
        if (value.isNumber()) {
            BigDecimal number = value.decimalValue().stripTrailingZeros();
            return number.signum() == 0 ? "0" : number.toPlainString();
        }
        String text = value.asText();
        return text;
    }

    private String interpolate(String template, Map<String, Object> variables) {
        if (template == null) { return null; }
        Matcher matcher = VARIABLE.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Object value = variables.get(matcher.group(1));
            if (value == null) { throw new IllegalArgumentException("未定义变量：" + matcher.group(1)); }
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
    private String resolvedVariablesJson(Map<String, Object> variables) {
        Map<String, Object> resolved = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> variable : variables.entrySet()) {
            if (!variable.getKey().startsWith("__platform_")) resolved.put(variable.getKey(), variable.getValue());
        }
        if (resolved.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(resolved); }
        catch (Exception e) { throw new IllegalArgumentException("步骤变量结果序列化失败：" + safeMessage(e), e); }
    }
    boolean isReadSql(String sql) { return sql != null && sql.trim().toUpperCase().matches("(?s)^(SELECT|WITH)\\\\b.*".replace("\\\\", "\\")); }
    boolean isDml(String sql) { return sql.trim().toUpperCase().matches("(?s)^(INSERT|UPDATE|DELETE|MERGE)\\\\b.*".replace("\\\\", "\\")); }
    private void requireTestWrite(PlatformModels.TestDataSource source) { if (!"TEST_WRITE".equalsIgnoreCase(source.accessMode)) { throw new IllegalStateException("只允许 TEST_WRITE 数据源执行固定数据 SQL"); } }
    private boolean recordRemoteUncertainty(Exception error) {
        boolean unknown = ExecutionDatabase.outcomeUnknown(error);
        if (unknown) currentInput.get().remoteOutcomeUnknown = true;
        return unknown;
    }

    private boolean infrastructureFailure(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException) {
                java.sql.SQLException sql = (java.sql.SQLException) cause;
                String state = sql.getSQLState();
                if (sql instanceof java.sql.SQLTimeoutException || state != null && (state.startsWith("08")
                        || state.startsWith("40") || state.startsWith("53") || state.startsWith("57")
                        || state.startsWith("HYT") || "55P03".equals(state))
                        || sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213 || sql.getErrorCode() == 1222) return true;
            }
        }
        return false;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        for (Throwable cause = e; cause != null; cause = cause.getCause()) if (cause instanceof java.sql.SQLException) {
            java.sql.SQLException sql = (java.sql.SQLException) cause;
            return message + " [SQLState=" + sql.getSQLState() + ", errorCode=" + sql.getErrorCode() + "]";
        }
        return message;
    }
    private String limit(String value) { return value == null ? null : value.length() <= 30000 ? value : value.substring(0, 30000); }

    @PreDestroy
    public void shutdown() {
        dispatcher.shutdown();
        try {
            while (!dispatcher.awaitTermination(1, TimeUnit.SECONDS)) { /* 等待认领事务结束。 */ }
            if (workers == null) return;
            workers.shutdown();
            while (!workers.awaitTermination(30, TimeUnit.SECONDS)) logger.info("等待运行案例完成收尾，运行数：{}", running.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待案例收尾时被中断，占用保留", e);
        }
    }
}
