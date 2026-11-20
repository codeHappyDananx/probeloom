package dev.probeloom.model;

import java.util.ArrayList;
import java.util.List;

public final class PlatformModels {
    private PlatformModels() {
    }

    public static class LoginRequest {
        public String username;
        public String password;
    }

    public static class InitializeAdminRequest {
        public String password;
    }

    public static class LoginResult {
        public String token;
        public String username;
    }

    public static class Environment {
        public Long id;
        public String name;
        public String refapp;
        public Boolean defaultEnvironment;
        public List<ProtocolConnection> connections = new ArrayList<ProtocolConnection>();
    }

    public static class ProtocolConnection {
        public String key;
        public String protocol;
        public String baseUrl;
        public java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
    }

    public static class TestDataSource {
        public Long id;
        public String name;
        public String resourceKey;
        public String databaseType;
        public String jdbcUrl;
        public String username;
        public String password;
        public String accessMode;
    }

    public static class FixtureDataset {
        public Long id;
        public String name;
        public String datasourceKey;
        public String variablesJson;
        public String prepareSql;
        public String cleanupSql;
    }

    public static class TestCase {
        public Long id;
        public Long groupId;
        public String groupName;
        public String groupCode;
        public Integer caseNo;
        public String caseCode;
        public String name;
        public String description;
        public Boolean enabled;
        public String deletedAt;
        public List<CaseAction> actions = new ArrayList<CaseAction>();
        public Boolean replaceActions;
        public Long latestExecutionId;
        public String latestExecutionAt;
        public String latestExecutionStatus;
    }

    public enum CaseSort { CASE_CODE, NAME, GROUP, LATEST_EXECUTION, STATUS }
    public enum SortDirection { ASC, DESC }
    public enum CaseExecutionStatus { PASSED, FAILED, INTERRUPTED, RUNNING, QUEUED, UNEXECUTED }

    public static class CaseTableQuery {
        private String caseCode;
        private String caseName;
        private String executedFrom;
        private String executedTo;
        private CaseExecutionStatus latestStatus;
        private CaseSort sortBy = CaseSort.CASE_CODE;
        private SortDirection sortDirection = SortDirection.ASC;

        public String getCaseCode() { return caseCode; }
        public void setCaseCode(String value) { caseCode = value; }
        public String getCaseName() { return caseName; }
        public void setCaseName(String value) { caseName = value; }
        public String getExecutedFrom() { return executedFrom; }
        public void setExecutedFrom(String value) { executedFrom = value; }
        public String getExecutedTo() { return executedTo; }
        public void setExecutedTo(String value) { executedTo = value; }
        public CaseExecutionStatus getLatestStatus() { return latestStatus; }
        public void setLatestStatus(CaseExecutionStatus value) { latestStatus = value; }
        public CaseSort getSortBy() { return sortBy; }
        public void setSortBy(CaseSort value) { sortBy = value; }
        public SortDirection getSortDirection() { return sortDirection; }
        public void setSortDirection(SortDirection value) { sortDirection = value; }
    }

    public static class CasePage {
        public List<TestCase> items = new ArrayList<TestCase>();
        public Integer page;
        public Integer pageSize;
        public Long total;
    }

    public static class CaseAction {
        public Long id;
        public String actionType;
        public String name;
        public String description;
        public Integer sortNo;
        public String datasetName;
        public String datasourceKey;
        public String snapshotName;
        public Boolean clearAfterBackup;
        public String backupCondition;
        public String variablesJson;
        public String prepareSql;
        public String cleanupSql;
        public String applicationName;
        public String requestBody;
        public String assertionsJson;
        public String querySql;
        public String expectedRowsJson;
        public Long expectedRowCount;
        public String compareMode;
        public Boolean coreAssertion;
        public Boolean runOnCaseEnd;
        public String expectedResult;
        public String responseVariablesJson;
        public CaseUpload upload;
        public List<SnapshotItem> snapshotRows = new ArrayList<SnapshotItem>();
    }

    public static class SnapshotItem {
        public String datasourceKey;
        public String datasetName;
        public String snapshotName;
        public Boolean clearAfterBackup;
        public String backupCondition;
    }

    public static class CaseUpload {
        public String connection;
        public Long actionId;
        public Long assetId;
        public String fileName;
        public String contentType;
        public String contentBase64;
        public String attachmentMode;
        public String attachmentSheetName;
        public String path;
        public String fileField;
        public String headersJson;
        public String formFieldsJson;
        public String assertionsJson;
        public String responseVariablesJson;
    }

    public static class TestFileAsset {
        public Long id;
        public String fileName;
        public String contentType;
        public String contentSha256;
        public byte[] content;
    }

    public static class CaseGroup {
        public Long id;
        public Integer groupNo;
        public String groupCode;
        public String name;
        public Long parentId;
        public Integer sortNo;
        public String fullPath;
        public Integer depth;
        public Integer descendantGroupCount;
        public Integer subtreeCaseCount;
        public List<Long> caseIds = new ArrayList<Long>();
    }

    public static class SchedulePlan {
        public Long id;
        public Long groupId;
        public Long environmentId;
        public String name;
        public String cronExpression;
        public Boolean enabled;
    }

    public static class RunRequest {
        public Long environmentId;
        public java.util.Map<String, Long> environmentIds = new java.util.LinkedHashMap<String, Long>();
        public Long actionId;
        public List<Long> actionIds;
    }

    public static class BatchRunRequest {
        public List<Long> caseIds;
        public Integer parallelism;
        public Long environmentId;
        public java.util.Map<String, Long> environmentIds = new java.util.LinkedHashMap<String, Long>();
    }

    public static class GroupRunRequest {
        public Long environmentId;
        public java.util.Map<String, Long> environmentIds = new java.util.LinkedHashMap<String, Long>();
        public Integer parallelism;
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public static class BatchRunItem {
        public Long caseId;
        public Long executionId;
        public String errorMessage;
    }

    public static class ExecutionStatusRequest {
        public List<Long> executionIds;
    }

    public static class Execution {
        public Long id;
        public Long batchId;
        public Integer batchPosition;
        public Integer batchParallelism;
        public Long caseId;
        public Long targetActionId;
        public Long groupId;
        public Long environmentId;
        public String triggerType;
        public String status;
        public String startedAt;
        public String finishedAt;
        public String errorMessage;
        public String message;
        public Long submittedBy;
        public String submittedByName;
        public String queuedAt;
        public String waitReason;
        public String finalizationPhase;
        public String finalizationState;
        public String finalizationError;
        public String operationConfirmation;
        public String finalizationDeadlineAt;
        public boolean remoteOutcomeUnknown;
        public String heartbeatAt;
        public String progressAt;
        public List<Long> blockedByExecutionIds = new ArrayList<Long>();
        public TestCase frozenCase;
        public List<StepExecution> steps = new ArrayList<StepExecution>();
        public List<AssertionExecution> assertions = new ArrayList<AssertionExecution>();
        public List<TableBackup> backups = new ArrayList<TableBackup>();
    }

    public static class ExecutionSubmission {
        public Long caseId;
        public Long batchId;
        public Integer batchPosition;
        public Long groupId;
        public Long environmentId;
        public Long targetActionId;
        public String triggerType;
        public Long submittedBy;
        public String submittedByName;
        public String caseKey;
        public String snapshotCiphertext;
        public java.util.Map<Integer, String> statisticsInterfaceKeys = new java.util.HashMap<Integer, String>();
        public List<Long> assetIds = new ArrayList<Long>();
        public List<StepExecution> steps = new ArrayList<StepExecution>();
    }

    public static class ClaimedExecution {
        public long id;
        public String snapshotCiphertext;
    }

    public static class ExecutionPage {
        public List<Execution> items = new ArrayList<Execution>();
        public Integer page;
        public Integer pageSize;
        public Long total;
    }

    public static class TableBackup {
        public Long id;
        public String datasourceKey;
        public String tableName;
        public String snapshotName;
        public String backupTable;
        public String backupCondition;
        public String identityStateJson;
        public String status;
        public Long rowCount;
        public String errorMessage;
    }

    public static class StepExecution {
        public Long id;
        public boolean caseEnd;
        public Integer sortNo;
        public String status;
        public String requestBody;
        public String responseBody;
        public String resolvedVariablesJson;
        public String errorMessage;
        public Long durationMs;
    }

    public static class AssertionExecution {
        public Long id;
        public boolean caseEnd;
        public Integer stepSortNo;
        public String assertionType;
        public String status;
        public String expectedValue;
        public String actualValue;
        public String diffMessage;
        public String expectedTemplate;
        public String expectedPath;
        public String expectedDisplayValue;
    }

}
