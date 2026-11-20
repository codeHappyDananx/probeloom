package dev.probeloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import dev.probeloom.model.PlatformModels;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellUtil;
import org.apache.poi.ss.util.NumberToTextConverter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CaseWorkbookCodec {
    private static final String INFO_SHEET = "案例信息";
    private static final String GUIDE_SHEET = "填写说明";
    private static final String CONFIG_SHEET = "基础配置目录";
    private static final String INSERT = "INSERT";
    private static final String UPDATE = "UPDATE";
    private static final String UPLOAD = "UPLOAD";
    private static final String HTTP = "HTTP";
    private static final String ASSERT = "ASSERT";
    private static final String CLEANUP = "CLEANUP";
    private static final String BACKUP = "BACKUP";
    private static final String RESTORE = "RESTORE";
    private static final String DATA_SECTION = "数据记录";
    private static final String DATASET_SETTING = "数据集";
    private static final String STEP_VARIABLES_SECTION = "步骤变量";
    private static final String UPDATE_SET_SECTION = "更新字段";
    private static final String DELETE_WHERE_SECTION = "删除条件";
    private static final String JSON_DATA_FIELD = "#JSON#";
    private static final String NULL_CELL = "#NULL#";
    private static final String REQUEST_SECTION = "请求参数";
    private static final String RESPONSE_ASSERTION_SECTION = "接口断言";
    private static final String RESPONSE_VARIABLE_SECTION = "变量声明";
    private static final String UPLOAD_HEADERS_SECTION = "请求头";
    private static final String UPLOAD_FORM_FIELDS_SECTION = "表单字段";
    private static final String UPLOAD_ATTACHMENT_SECTION = "附件";
    private static final String EXPECTED_SECTION = "期望结果";
    private static final String PLACEHOLDER_FIELD = "请替换为字段名";
    private static final String PLACEHOLDER_PARAMETER = "请替换为参数名";
    private static final String PLACEHOLDER_RESPONSE = "请替换为响应字段";
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$.]*");
    private static final Pattern SNAPSHOT_TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?");
    private static final Pattern SNAPSHOT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ASSERT_FROM_TABLE = Pattern.compile("(?i)\\bFROM\\s+([A-Za-z_][A-Za-z0-9_$.]*)");
    private static final Pattern ASSERT_CLAUSE = Pattern.compile("(?i)\\b(?:WHERE|GROUP\\s+BY|HAVING|ORDER\\s+BY|LIMIT|OFFSET)\\b");
    private static final Pattern CLEANUP_DELETE = Pattern.compile("(?is)^\\s*DELETE\\s+FROM\\s+([A-Za-z_][A-Za-z0-9_$.]*)\\s+WHERE\\s+(.+?)\\s*$");
    private static final Pattern CLEANUP_FIELD = Pattern.compile("(?is)^([A-Za-z_][A-Za-z0-9_$]*)\\s*(?:=\\s*('(?:''|[^'])*+'|[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|TRUE|FALSE)|IS\\s+(NULL))$");
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private static final Pattern DATASET_SECTION = Pattern.compile("^数组数据集:(.+)$");
    private static final Pattern DATASET_REFERENCE = Pattern.compile("^#REF#(.+)$");
    private static final Pattern INTERFACE_FIELD = Pattern.compile("^\\$\\.[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*$");
    private static final int INTERFACE_ASSERTION_VERSION = 2;

    private final ObjectMapper objectMapper;
    private final ObjectMapper preciseObjectMapper;
    private final DataFormatter formatter = new DataFormatter();

    public CaseWorkbookCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        this.preciseObjectMapper = objectMapper.copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    }

    public byte[] template(List<PlatformModels.TestDataSource> dataSources, List<PlatformModels.Environment> environments) {
        try (Workbook workbook = new XSSFWorkbook()) {
            PlatformModels.CaseAction backup = new PlatformModels.CaseAction();
            backup.actionType = BACKUP;
            backup.name = "备份示例记录";
            backup.datasourceKey = "default";
            backup.datasetName = "demo_records";
            backup.snapshotName = "initial";
            backup.backupCondition = "record_id='demo-001'";
            backup.clearAfterBackup = false;
            PlatformModels.CaseAction restore = new PlatformModels.CaseAction();
            restore.actionType = RESTORE;
            restore.name = "恢复示例记录";
            restore.datasourceKey = backup.datasourceKey;
            restore.datasetName = backup.datasetName;
            restore.snapshotName = backup.snapshotName;
            List<PlatformModels.CaseAction> actions = java.util.Arrays.asList(backup, templateInsertAction(),
                    templateUpdateAction(), templateHttpAction(), templateUploadAction(), templateAssertAction(),
                    templateCleanupConditionAction(), restore);
            writeInfoSheet(workbook, null, actions);
            writeGuideSheet(workbook);
            writeConfigSheet(workbook, dataSources, environments);
            for (int index = 0; index < actions.size(); index++) {
                writeTemplateStep(workbook, sheetName(index + 1, actions.get(index)), actions.get(index));
            }
            return bytes(workbook);
        } catch (IOException e) {
            throw new IllegalStateException("生成案例模板失败：" + e.getMessage(), e);
        }
    }

    public byte[] write(PlatformModels.TestCase testCase) {
        validate(testCase);
        try (Workbook workbook = new XSSFWorkbook()) {
            List<PlatformModels.CaseAction> actions = actionsOf(testCase);
            Set<String> variableReferences = referencedStepVariables(actions);
            writeInfoSheet(workbook, testCase, actions);
            for (int index = 0; index < actions.size(); index++) {
                PlatformModels.CaseAction action = actions.get(index);
                writeActionSheet(workbook, sheetName(index + 1, action), action, variableReferences);
                if (UPLOAD.equals(action.actionType)
                        && action.upload != null
                        && "SHEET".equals(action.upload.attachmentMode)) {
                    writeAttachmentSheet(workbook, action.upload);
                }
            }
            return bytes(workbook);
        } catch (IOException e) {
            throw new IllegalStateException("导出案例失败：" + e.getMessage(), e);
        }
    }

    public void validate(PlatformModels.TestCase value) {
        if (value == null || blank(value.name) || blank(value.description) || value.name.trim().equals(value.description.trim())) {
            throw new IllegalArgumentException("案例名称和具体业务说明必填，二者不能相同");
        }
        if (value.actions == null || value.actions.isEmpty()) throw new IllegalArgumentException("案例至少需要一个步骤");
        for (PlatformModels.CaseAction action : value.actions) {
            required(action.name, "步骤名称必填");
            if (ASSERT.equals(action.actionType)) {
                if (!assertTable(action.querySql, action.name).equals(action.datasetName)) {
                    throw new IllegalArgumentException("断言查询目标表与步骤表不一致");
                }
            }
        }
        validateCaseActions(value.actions);
    }

    public static void validateTableAction(PlatformModels.CaseAction action) {
        if (INSERT.equals(action.actionType) || UPDATE.equals(action.actionType) || CLEANUP.equals(action.actionType)) TableStepSql.parse(action);
        if (ASSERT.equals(action.actionType) && !assertTable(action.querySql, action.name).equals(action.datasetName)) throw new IllegalArgumentException("断言查询目标表与步骤表不一致：" + action.name);
    }

    public PlatformModels.CaseAction createInsertAction(String datasource, String table, List<Map<String, Object>> rows) {
        validateStructuredRows(table, rows, false);
        return parameterizedInsertAction(required(datasource, "数据源必填"), table, rows);
    }

    public PlatformModels.CaseAction createCleanupRowsAction(String datasource, String table, List<Map<String, Object>> rows) {
        validateStructuredRows(table, rows, true);
        return dataAction(CLEANUP, required(datasource, "数据源必填"), table, rows);
    }

    private void validateStructuredRows(String table, List<Map<String, Object>> rows, boolean cleanup) {
        sqlIdentifier(table, "表名", "案例");
        if (rows.isEmpty()) throw new IllegalArgumentException("数据记录不能为空");
        Set<String> fields = rows.get(0).keySet();
        if (fields.isEmpty()) throw new IllegalArgumentException("数据字段不能为空");
        for (Map<String, Object> row : rows) {
            if (!fields.equals(row.keySet())) throw new IllegalArgumentException("数据记录字段必须一致");
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                sqlIdentifier(entry.getKey(), "字段", table);
                Object value = entry.getValue();
                if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                    throw new IllegalArgumentException("数据库字段只接受 JSON 标量");
                }
                if (cleanup && (value == null || value instanceof String && blank((String) value))) {
                    throw new IllegalArgumentException("清理条件必须是非空具体值");
                }
            }
        }
    }

    public PlatformModels.CaseAction createCleanupSqlAction(String datasource, String table, List<String> statements) {
        sqlIdentifier(table, "表名", "清理");
        if (statements.isEmpty()) throw new IllegalArgumentException("清理语句不能为空");
        StringBuilder sql = new StringBuilder();
        for (String statement : statements) {
            appendStatement(sql, cleanupSqlAction(datasource, table, statement, "案例清理").cleanupSql);
        }
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = CLEANUP; action.datasourceKey = datasource; action.datasetName = table;
        action.cleanupSql = sql.toString();
        return action;
    }

    public PlatformModels.CaseAction createAssertAction(String datasource, String table, Map<String, String> select,
                                                        String where, String mode, JsonNode rows, int count) {
        sqlIdentifier(table, "表名", "断言");
        List<String> projections = new ArrayList<String>();
        for (Map.Entry<String, String> column : select.entrySet()) {
            projections.add(required(column.getValue(), "投影表达式必填") + " AS " + sqlIdentifier(column.getKey(), "输出字段", table));
        }
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = ASSERT; action.datasourceKey = datasource; action.datasetName = table;
        action.querySql = "SELECT " + String.join(",", projections) + " FROM " + table + " WHERE " + required(where, "查询条件必填");
        if (!table.equals(assertTable(action.querySql, "案例断言"))) throw new IllegalArgumentException("断言目标表不一致");
        action.compareMode = mode;
        if ("EXACT_ROWS".equals(mode)) {
            action.expectedRowsJson = writeJson(rows);
        } else if ("ROW_COUNT".equals(mode)) {
            if (count < 0) throw new IllegalArgumentException("期望行数必须是非负整数");
            action.expectedRowCount = (long) count;
        } else if (!"NOT_EXISTS".equals(mode)) {
            throw new IllegalArgumentException("比较方式不支持");
        }
        return action;
    }


    private void writeAttachmentSheet(
            Workbook targetWorkbook,
            PlatformModels.CaseUpload upload) {
        if (targetWorkbook.getSheet(upload.attachmentSheetName) != null) {
            return;
        }

        try (XSSFWorkbook sourceWorkbook = new XSSFWorkbook(
                new java.io.ByteArrayInputStream(
                        java.util.Base64.getDecoder().decode(upload.contentBase64)))) {
            Sheet sourceSheet = sourceWorkbook.getSheetAt(0);
            Sheet targetSheet = targetWorkbook.createSheet(upload.attachmentSheetName);
            java.util.Map<Short, CellStyle> styles =
                    new java.util.HashMap<Short, CellStyle>();

            for (int rowIndex = 0;
                    rowIndex <= sourceSheet.getLastRowNum();
                    rowIndex++) {
                Row sourceRow = sourceSheet.getRow(rowIndex);
                if (sourceRow == null) {
                    continue;
                }

                Row targetRow = targetSheet.createRow(rowIndex);
                targetRow.setHeight(sourceRow.getHeight());
                for (int cellIndex = sourceRow.getFirstCellNum();
                        cellIndex < sourceRow.getLastCellNum();
                        cellIndex++) {
                    Cell sourceCell = sourceRow.getCell(cellIndex);
                    if (sourceCell == null) {
                        continue;
                    }

                    Cell targetCell = targetRow.createCell(cellIndex);
                    copyCellValue(sourceCell, targetCell);
                    if (sourceCell.getCellStyle() != null) {
                        short styleIndex = sourceCell.getCellStyle().getIndex();
                        CellStyle targetStyle = styles.get(styleIndex);
                        if (targetStyle == null) {
                            targetStyle = targetWorkbook.createCellStyle();
                            targetStyle.cloneStyleFrom(sourceCell.getCellStyle());
                            styles.put(styleIndex, targetStyle);
                        }
                        targetCell.setCellStyle(targetStyle);
                    }
                }
            }

            for (int column = 0; column < 256; column++) {
                targetSheet.setColumnWidth(column, sourceSheet.getColumnWidth(column));
                targetSheet.setColumnHidden(column, sourceSheet.isColumnHidden(column));
            }
            for (int index = 0; index < sourceSheet.getNumMergedRegions(); index++) {
                targetSheet.addMergedRegion(sourceSheet.getMergedRegion(index));
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "导出附件 Sheet 失败：" + upload.attachmentSheetName,
                    e);
        }
    }

    private void copyCellValue(Cell source, Cell target) {
        if (source.getCellType() == CellType.FORMULA) {
            target.setCellFormula(source.getCellFormula());
        } else if (source.getCellType() == CellType.STRING) {
            target.setCellValue(source.getStringCellValue());
        } else if (source.getCellType() == CellType.NUMERIC) {
            target.setCellValue(source.getNumericCellValue());
        } else if (source.getCellType() == CellType.BOOLEAN) {
            target.setCellValue(source.getBooleanCellValue());
        } else if (source.getCellType() == CellType.ERROR) {
            target.setCellErrorValue(source.getErrorCellValue());
        }
    }

    public PlatformModels.TestCase parse(InputStream input) {
        try (Workbook workbook = new XSSFWorkbook(input)) {
            Sheet infoSheet = workbook.getSheet(INFO_SHEET);
            Map<String, String> info = readInfo(infoSheet);
            String name = requiredInfo(infoSheet, "案例名称");
            PlatformModels.TestCase value = new PlatformModels.TestCase();
            value.name = name;
            value.description = requiredInfo(infoSheet, "业务说明");
            if (name.equals(value.description)) {
                throw new IllegalArgumentException("业务说明必须描述业务场景，不能只填写案例名称：" + infoValueLocation(infoSheet, "业务说明"));
            }
            value.enabled = parseEnabled(info.get("启用"));
            value.actions = new ArrayList<PlatformModels.CaseAction>();
            value.replaceActions = true;

            java.util.Set<String> attachmentSheetNames = new java.util.HashSet<String>();
            for (int attachmentIndex = 0;
                    attachmentIndex < workbook.getNumberOfSheets();
                    attachmentIndex++) {
                Sheet candidate = workbook.getSheetAt(attachmentIndex);
                if (INFO_SHEET.equals(candidate.getSheetName())
                        || GUIDE_SHEET.equals(candidate.getSheetName())
                        || CONFIG_SHEET.equals(candidate.getSheetName())) {
                    continue;
                }
                if (UPLOAD.equals(actionType(candidate))) {
                    String attachment = attachmentValue(candidate);
                    Sheet attachmentSheet = workbook.getSheet(attachment);
                    if (attachmentSheet != null
                            && !INFO_SHEET.equals(attachment)
                            && !GUIDE_SHEET.equals(attachment)
                            && !CONFIG_SHEET.equals(attachment)
                            && !actionType(attachmentSheet).equals(UPLOAD)
                            && !actionType(attachmentSheet).equals(INSERT)
                            && !actionType(attachmentSheet).equals(HTTP)
                            && !actionType(attachmentSheet).equals(ASSERT)
                            && !actionType(attachmentSheet).equals(CLEANUP)
                            && !actionType(attachmentSheet).equals(UPDATE)
                            && !actionType(attachmentSheet).equals(BACKUP)
                            && !actionType(attachmentSheet).equals(RESTORE)) {
                        attachmentSheetNames.add(attachment);
                    }
                }
            }

            int sortNo = 0;
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                if (INFO_SHEET.equals(sheet.getSheetName()) || GUIDE_SHEET.equals(sheet.getSheetName()) || CONFIG_SHEET.equals(sheet.getSheetName())) {
                    continue;
                }
                if (attachmentSheetNames.contains(sheet.getSheetName())) {
                    continue;
                }

                PlatformModels.CaseAction action = readAction(sheet, workbook);
                String expectedSheetPrefix = sheetName(sortNo + 1, action);
                String actualSheetName = sheet.getSheetName();
                if (!actualSheetName.equals(expectedSheetPrefix)
                        && !actualSheetName.startsWith(expectedSheetPrefix + "_")) {
                    throw new IllegalArgumentException("步骤 Sheet 名称必须以“" + expectedSheetPrefix + "”为固定前缀：" + actualSheetName);
                }
                action.sortNo = sortNo++;
                value.actions.add(action);
            }
            if (value.actions.isEmpty()) {
                throw new IllegalArgumentException("案例至少需要一个步骤 Sheet");
            }
            applyCoreAssertions(infoSheet, value.actions);
            validateCaseActions(value.actions);
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException("读取案例工作簿失败，请确认文件是 .xlsx 模板：" + e.getMessage(), e);
        }
    }

    private PlatformModels.CaseAction readAction(Sheet sheet, Workbook workbook) {
        String actionType = actionType(sheet);
        PlatformModels.CaseAction action;
        if (INSERT.equals(actionType)) {
            action = readDataAction(sheet, actionType);
        } else if (UPDATE.equals(actionType)) {
            action = readUpdateAction(sheet);
        } else if (CLEANUP.equals(actionType)) {
            action = readCleanupAction(sheet);
        } else if (BACKUP.equals(actionType) || RESTORE.equals(actionType)) {
            action = readSnapshotAction(sheet, actionType);
        } else if (UPLOAD.equals(actionType)) {
            action = readUploadAction(sheet, workbook);
        } else if (HTTP.equals(actionType)) {
            action = readHttpAction(sheet);
        } else if (ASSERT.equals(actionType)) {
            action = readAssertAction(sheet);
        } else {
            throw new IllegalArgumentException("步骤类型不支持：" + cellLocation(sheet, 0, 1));
        }
        action.name = required(value(sheet.getRow(1) == null ? null : sheet.getRow(1).getCell(1)), "步骤名称不能为空：" + cellLocation(sheet, 1, 1));
        if ("步骤说明".equals(value(sheet.getRow(1).getCell(2)))) {
            String description = rawValue(sheet.getRow(1).getCell(3));
            if (!description.isEmpty()) action.description = description;
        }
        return action;
    }

    private PlatformModels.CaseAction readSnapshotAction(Sheet sheet, String type) {
        List<String> headers = tableHeaders(sheet, 2, sheet.getSheetName());
        List<String> expected = BACKUP.equals(type)
                ? java.util.Arrays.asList("数据源标识", "表名", "快照标识", "备份后清空", "填写说明")
                : java.util.Arrays.asList("数据源标识", "表名", "快照标识", "填写说明");
        boolean hasCondition = BACKUP.equals(type) && headers.contains("备份条件");
        if (hasCondition) expected = java.util.Arrays.asList("数据源标识", "表名", "快照标识", "备份后清空", "备份条件", "填写说明");
        if (!expected.equals(headers)) {
            throw new IllegalArgumentException("备份/恢复步骤设置表头必须为 " + expected + "：" + rowLocation(sheet, 2));
        }
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = type;
        if (BACKUP.equals(type)) {
            List<PlatformModels.SnapshotItem> items = new ArrayList<PlatformModels.SnapshotItem>();
            for (int rowIndex = 3; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (snapshotRowBlank(row, hasCondition ? 5 : 4)) continue;
                PlatformModels.SnapshotItem item = new PlatformModels.SnapshotItem();
                item.datasourceKey = required(value(row.getCell(0)), "数据源标识不能为空：" + cellLocation(sheet, rowIndex, 0));
                item.datasetName = required(value(row.getCell(1)), "表名不能为空：" + cellLocation(sheet, rowIndex, 1));
                item.snapshotName = required(value(row.getCell(2)), "快照标识不能为空：" + cellLocation(sheet, rowIndex, 2));
                item.clearAfterBackup = yesNo(value(row.getCell(3)), "备份后清空", cellLocation(sheet, rowIndex, 3));
                item.backupCondition = hasCondition ? value(row.getCell(4)) : null;
                if (!SNAPSHOT_TABLE.matcher(item.datasetName).matches() || !SNAPSHOT_NAME.matcher(item.snapshotName).matches()) {
                    throw new IllegalArgumentException("表名或快照标识格式错误：" + cellLocation(sheet, rowIndex, 1));
                }
                items.add(item);
            }
            if (items.isEmpty()) throw new IllegalArgumentException("备份步骤至少需要一行备份表：" + sheet.getSheetName());
            action.snapshotRows = items;
            applySnapshotItem(action, items.get(0));
        } else {
            Map<String, String> settings = sectionSettings(sheet, 2, sheet.getSheetName());
            action.datasourceKey = required(settings.get("数据源标识"), "数据源标识不能为空：" + sheet.getSheetName());
            action.datasetName = required(settings.get("表名"), "表名不能为空：" + sheet.getSheetName());
            action.snapshotName = required(settings.get("快照标识"), "快照标识不能为空：" + sheet.getSheetName());
            if (!SNAPSHOT_TABLE.matcher(action.datasetName).matches() || !SNAPSHOT_NAME.matcher(action.snapshotName).matches()) {
                throw new IllegalArgumentException("表名或快照标识格式错误：" + sheet.getSheetName());
            }
        }
        return action;
    }

    private void applySnapshotItem(PlatformModels.CaseAction action, PlatformModels.SnapshotItem item) {
        action.datasourceKey = item.datasourceKey;
        action.datasetName = item.datasetName;
        action.snapshotName = item.snapshotName;
        action.clearAfterBackup = item.clearAfterBackup;
        action.backupCondition = item.backupCondition;
    }

    private boolean snapshotRowBlank(Row row, int columns) {
        if (row == null) return true;
        for (int index = 0; index < columns; index++) {
            if (!blank(value(row.getCell(index)))) return false;
        }
        return true;
    }

    private PlatformModels.CaseAction readUploadAction(Sheet sheet, Workbook workbook) {
        validateSettings(sheet);
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = UPLOAD;
        action.applicationName = requiredSetting(sheet, "应用");
        applyExpectedOutcome(action, sectionSettings(sheet, 2, sheet.getSheetName()), sheet);
        PlatformModels.CaseUpload upload = new PlatformModels.CaseUpload();
        upload.connection = requiredSetting(sheet, "连接标识");
        upload.path = requiredSetting(sheet, "接口路径");
        upload.fileField = requiredSetting(sheet, "文件字段");
        upload.fileName = requiredSetting(sheet, "文件名");
        upload.contentType = requiredSetting(sheet, "文件类型");
        upload.headersJson = sectionJsonOrFields(sheet, UPLOAD_HEADERS_SECTION);
        upload.formFieldsJson = sectionJsonOrFields(sheet, UPLOAD_FORM_FIELDS_SECTION);
        upload.assertionsJson = assertionJsonOrFailure(sheet, action);
        upload.responseVariablesJson = responseVariablesJson(sheet);
        resolveAttachment(sheet, workbook, upload);
        action.upload = upload;
        action.datasetName = uploadTarget(upload.path);
        return action;
    }

    private String attachmentValue(Sheet sheet) {
        int attachmentSection = requiredSection(sheet, UPLOAD_ATTACHMENT_SECTION);
        StringBuilder content = new StringBuilder();

        for (int rowIndex = attachmentSection + 2;
                rowIndex <= sheet.getLastRowNum();
                rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            String value = value(row.getCell(0)).trim();
            if (!value.isEmpty()) {
                content.append(value);
            }
        }

        return required(
                content.toString(),
                "上传步骤缺少附件："
                        + cellLocation(sheet, attachmentSection + 2, 0));
    }

    private void resolveAttachment(
            Sheet stepSheet,
            Workbook workbook,
            PlatformModels.CaseUpload upload) {

        String attachment = attachmentValue(stepSheet);
        if (attachment.indexOf(',') >= 0) {
            throw new IllegalArgumentException("一个上传步骤只能填写一个附件，不能使用逗号分隔多个附件");
        }
        Sheet sourceSheet = workbook.getSheet(attachment);

        if (sourceSheet != null) {
            if (INFO_SHEET.equals(attachment)
                    || GUIDE_SHEET.equals(attachment)
                    || CONFIG_SHEET.equals(attachment)
                    || !actionType(sourceSheet).isEmpty()) {
                throw new IllegalArgumentException(
                        "附件必须引用非步骤 Sheet：" + attachment);
            }

            upload.attachmentMode = "SHEET";
            upload.attachmentSheetName = attachment;
            upload.contentBase64 = attachmentWorkbookBase64(workbook, attachment);
            return;
        }

        final byte[] content;
        try {
            content = java.util.Base64.getDecoder().decode(attachment);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "附件必须填写有效的 Sheet 名或 Base64 内容："
                            + cellLocation(stepSheet,
                                    requiredSection(stepSheet, UPLOAD_ATTACHMENT_SECTION) + 2,
                                    0),
                    e);
        }

        if (content.length == 0) {
            throw new IllegalArgumentException("上传步骤附件不能为空");
        }

        upload.attachmentMode = "BASE64";
        upload.attachmentSheetName = null;
        upload.contentBase64 = java.util.Base64.getEncoder().encodeToString(content);
    }

    private String attachmentWorkbookBase64(Workbook workbook, String sheetName) {
        try {
            byte[] source = bytes(workbook);
            try (XSSFWorkbook copy = new XSSFWorkbook(new java.io.ByteArrayInputStream(source))) {
                for (int index = copy.getNumberOfSheets() - 1; index >= 0; index--) {
                    if (!sheetName.equals(copy.getSheetAt(index).getSheetName())) {
                        copy.removeSheetAt(index);
                    }
                }

                if (copy.getNumberOfSheets() != 1) {
                    throw new IllegalArgumentException(
                            "附件 Sheet 不存在：" + sheetName);
                }

                copy.setActiveSheet(0);
                return java.util.Base64.getEncoder().encodeToString(bytes(copy));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "生成附件工作簿失败：" + sheetName,
                    e);
        }
    }

    private PlatformModels.CaseAction readDataAction(Sheet sheet, String actionType) {
        return readFieldAction(sheet, actionType);
    }

    private PlatformModels.CaseAction readFieldAction(Sheet sheet, String type) {
        validateSettings(sheet);
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = type;
        action.name = sheet.getSheetName();
        action.datasourceKey = requiredSetting(sheet, "数据源标识");
        action.datasetName = sqlIdentifier(requiredSetting(sheet, "表名"), "表名", settingLocation(sheet, "表名"));
        if (!CLEANUP.equals(type) && sectionExists(sheet, DATA_SECTION)) {
            int section = requiredSection(sheet, DATA_SECTION);
            if (Collections.singletonList("#SQL#").equals(tableHeaders(sheet, section + 1, sheet.getSheetName()))) {
                if (sectionExists(sheet, UPDATE_SET_SECTION) || sectionExists(sheet, "条件")) {
                    throw new IllegalArgumentException("#SQL# 与字段表格、条件区域只能填写一种：" + sheet.getSheetName());
                }
                StringBuilder statements = new StringBuilder();
                int end = sectionExists(sheet, STEP_VARIABLES_SECTION) ? requiredSection(sheet, STEP_VARIABLES_SECTION) - 1 : sheet.getLastRowNum();
                for (int index = section + 2; index <= end; index++) {
                    Row row = sheet.getRow(index);
                    if (blankRow(row)) continue;
                    for (int column = 1; column < row.getLastCellNum(); column++) {
                        if (!blank(value(row.getCell(column)))) throw new IllegalArgumentException("#SQL# 记录只能填写第一列：" + cellLocation(sheet, index, column));
                    }
                    String statement = required(value(row.getCell(0)), "#SQL# 不能为空：" + cellLocation(sheet, index, 0));
                    if (statement.endsWith(";")) statement = statement.substring(0, statement.length() - 1).trim();
                    if (TableStepSql.split(statement, ';').size() > 1) throw new IllegalArgumentException("#SQL# 每行只能填写一条语句：" + cellLocation(sheet, index, 0));
                    appendStatement(statements, statement);
                }
                action.prepareSql = statements.toString();
                readStepVariables(sheet, action);
                TableStepSql.parse(action);
                return action;
            }
        }
        List<String> fields = Collections.emptyList();
        List<Map<String, String>> rows = new ArrayList<>();
        if (!CLEANUP.equals(type)) {
            int section = requiredSection(sheet, INSERT.equals(type) ? DATA_SECTION : UPDATE_SET_SECTION);
            fields = tableHeaders(sheet, section + 1, sheet.getSheetName());
            if (INSERT.equals(type) && fields.equals(Collections.singletonList(JSON_DATA_FIELD))) {
                Cell cell = requiredJsonDataCell(sheet, section + 2, "插入数据集");
                List<Map<String, Object>> values = databaseDataset(sheet, cell, "插入数据集");
                validateDatabaseFields(sheet, cell, values, true);
                return parameterizedInsertAction(action.datasourceKey, action.datasetName, values);
            }
            for (String field : fields) sqlIdentifier(field, "字段", sheet.getSheetName());
            int end = sectionExists(sheet, "条件") ? requiredSection(sheet, "条件") - 1 : sheet.getLastRowNum();
            if (sectionExists(sheet, STEP_VARIABLES_SECTION)) end = Math.min(end, requiredSection(sheet, STEP_VARIABLES_SECTION) - 1);
            rows = sqlValueRows(sheet, section + 2, end, fields);
            if (rows.isEmpty()) throw new IllegalArgumentException("数据步骤至少需要一条字段记录：" + sheet.getSheetName());
        }
        List<String> conditions = new ArrayList<>();
        if (sectionExists(sheet, "条件")) {
            int start = requiredSection(sheet, "条件") + 1;
            int end = sectionExists(sheet, STEP_VARIABLES_SECTION) ? requiredSection(sheet, STEP_VARIABLES_SECTION) - 2 : sheet.getLastRowNum();
            int count = CLEANUP.equals(type) ? end - start + 1 : rows.size();
            for (int index = 0; index < count; index++) {
                Row row = sheet.getRow(start + index);
                String condition = row == null ? "" : value(row.getCell(0));
                if (!condition.isEmpty() && !condition.matches("(?is)^WHERE\\s+.+")) throw new IllegalArgumentException("条件必须以 WHERE 开始：" + rowLocation(sheet, start + index));
                if (row != null) for (int column = 1; column < row.getLastCellNum(); column++) {
                    if (!blank(value(row.getCell(column)))) throw new IllegalArgumentException("条件只填写第一列：" + cellLocation(sheet, start + index, column));
                }
                conditions.add(condition);
            }
            for (int index = start + count; index <= end; index++) {
                if (!blankRow(sheet.getRow(index))) throw new IllegalArgumentException("条件行必须与上方数据记录逐行对应：" + rowLocation(sheet, index));
            }
        } else if (INSERT.equals(type)) conditions.addAll(Collections.nCopies(rows.size(), ""));
        if (conditions.isEmpty() || !INSERT.equals(type) && conditions.contains("")) throw new IllegalArgumentException("更新和清理步骤必须填写 WHERE 条件：" + sheet.getSheetName());
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < conditions.size(); index++) {
            String condition = conditions.get(index);
            if (INSERT.equals(type)) {
                String values = String.join(",", rows.get(index).values());
                appendStatement(sql, "INSERT INTO " + action.datasetName + " (" + String.join(",", fields) + ") "
                        + (condition.isEmpty() ? "VALUES (" + values + ")" : "SELECT " + values + " " + condition));
            } else if (UPDATE.equals(type)) {
                List<String> assignments = new ArrayList<>();
                for (String field : fields) assignments.add(field + "=" + rows.get(index).get(field));
                appendStatement(sql, "UPDATE " + action.datasetName + " SET " + String.join(",", assignments) + " " + condition);
            } else appendStatement(sql, "DELETE FROM " + action.datasetName + " " + condition);
        }
        if (CLEANUP.equals(type)) action.cleanupSql = sql.toString(); else action.prepareSql = sql.toString();
        readStepVariables(sheet, action);
        TableStepSql.parse(action);
        return action;
    }

    private void readStepVariables(Sheet sheet, PlatformModels.CaseAction action) {
        if (sectionExists(sheet, STEP_VARIABLES_SECTION)) {
            int section = requiredSection(sheet, STEP_VARIABLES_SECTION);
            Map<String, Object> variables = new LinkedHashMap<>();
            for (int index = section + 2; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (blankRow(row)) continue;
                String name = required(value(row.getCell(0)), "步骤变量名称必填");
                if (variables.containsKey(name)) throw new IllegalArgumentException("步骤变量重复：" + name);
                variables.put(name, preciseObjectMapper.convertValue(json(required(value(row.getCell(1)), "步骤变量值必须填写 JSON"), "步骤变量"), Object.class));
            }
            action.variablesJson = writeJson(variables);
        }
    }

    private PlatformModels.CaseAction readUpdateAction(Sheet sheet) {
        return readFieldAction(sheet, UPDATE);
    }

    private static boolean containsTopLevelKeyword(String sql, String keyword) {
        int parentheses = 0;
        boolean quoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;
            if (current == '\'') {
                if (quoted && next == '\'') {
                    index++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }
            if (quoted) {
                continue;
            }
            if (current == '(') {
                parentheses++;
                continue;
            }
            if (current == ')') {
                parentheses--;
                continue;
            }
            if (parentheses != 0 || !sql.regionMatches(true, index, keyword, 0, keyword.length())) {
                continue;
            }
            int before = index - 1;
            int after = index + keyword.length();
            if ((before < 0 || !Character.isJavaIdentifierPart(sql.charAt(before)))
                    && (after >= sql.length() || !Character.isJavaIdentifierPart(sql.charAt(after)))) {
                return true;
            }
        }
        return false;
    }

    private PlatformModels.CaseAction readCleanupAction(Sheet sheet) {
        boolean fields = sectionExists(sheet, DELETE_WHERE_SECTION);
        boolean records = sectionExists(sheet, DATA_SECTION);
        if ((fields && records) || ((fields || records) && sectionExists(sheet, "条件"))) {
            throw new IllegalArgumentException("清理步骤只能填写一种删除条件区域：" + sheet.getSheetName());
        }
        if (sectionExists(sheet, "条件")) return readFieldAction(sheet, CLEANUP);
        validateSettings(sheet);
        String datasource = requiredSetting(sheet, "数据源标识");
        String table = sqlIdentifier(requiredSetting(sheet, "表名"), "表名", settingLocation(sheet, "表名"));
        int section = requiredSection(sheet, fields ? DELETE_WHERE_SECTION : DATA_SECTION);
        List<String> names = tableHeaders(sheet, section + 1, sheet.getSheetName());
        boolean sqlRecords = !fields && Collections.singletonList("#SQL#").equals(names);
        if (!sqlRecords) for (String name : names) sqlIdentifier(name, "字段", sheet.getSheetName());
        StringBuilder sql = new StringBuilder();
        int end = sectionExists(sheet, STEP_VARIABLES_SECTION) ? requiredSection(sheet, STEP_VARIABLES_SECTION) - 1 : sheet.getLastRowNum();
        for (int index = section + 2; index <= end; index++) {
            Row row = sheet.getRow(index);
            if (blankRow(row)) continue;
            for (int column = names.size(); column < row.getLastCellNum(); column++) {
                if (!blank(value(row.getCell(column)))) {
                    throw new IllegalArgumentException("删除条件存在没有对应字段的值：" + cellLocation(sheet, index, column));
                }
            }
            if (sqlRecords) {
                String statement = required(value(row.getCell(0)), "清理 SQL 不能为空：" + cellLocation(sheet, index, 0));
                appendStatement(sql, cleanupSqlAction(datasource, table, statement, cellLocation(sheet, index, 0)).cleanupSql);
            } else {
                List<String> conditions = new ArrayList<>();
                for (int column = 0; column < names.size(); column++) {
                    String location = cellLocation(sheet, index, column);
                    Cell cell = row.getCell(column);
                    if (cell == null || cell.getCellType() == CellType.BLANK
                            || rawValue(cell).isEmpty() && !cell.getCellStyle().getQuotePrefixed()) {
                        throw new IllegalArgumentException("删除条件值不能为空：" + location);
                    }
                    String expression = sqlValue(cell);
                    validateSqlValue(expression, names.get(column), location);
                    if (TableStepSql.keyword(expression, "AND") >= 0 || TableStepSql.keyword(expression, "OR") >= 0) {
                        throw new IllegalArgumentException("字段单元格只填写值，复杂删除条件请填写完整 WHERE：" + location);
                    }
                    conditions.add(names.get(column) + ("NULL".equalsIgnoreCase(expression) ? " IS NULL" : "=" + expression));
                }
                appendStatement(sql, "DELETE FROM " + table + " WHERE " + String.join(" AND ", conditions));
            }
        }
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = CLEANUP;
        action.name = sheet.getSheetName();
        action.datasourceKey = datasource;
        action.datasetName = table;
        action.cleanupSql = sql.toString();
        readStepVariables(sheet, action);
        TableStepSql.parse(action);
        return action;
    }

    private PlatformModels.CaseAction cleanupSqlAction(String datasourceKey, String table, String cleanupSql, String sheetName) {
        Matcher matcher = CLEANUP_DELETE.matcher(cleanupSql);
        if (TableStepSql.split(cleanupSql, ';').size() > 1 || !matcher.matches()
                || !table.equalsIgnoreCase(matcher.group(1)) || blank(matcher.group(2))) {
            throw new IllegalArgumentException("清理 SQL 必须只删除“" + table + "”且必须包含 WHERE 条件：" + sheetName);
        }
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = CLEANUP;
        action.datasourceKey = datasourceKey;
        action.datasetName = table;
        action.cleanupSql = cleanupSql.trim();
        return action;
    }

    private PlatformModels.CaseAction readHttpAction(Sheet sheet) {
        validateSettings(sheet);
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = HTTP;
        action.applicationName = requiredSetting(sheet, "应用");
        int section = requiredSection(sheet, REQUEST_SECTION);
        if (!JSON_DATA_FIELD.equals(value(cell(sheet, section + 1, 0)))) {
            throw new IllegalArgumentException("HTTP 请求参数必须使用 #JSON#");
        }
        JsonNode request = requestJson(sheet, cell(sheet, section + 2, 0));
        if (!requiredSetting(sheet, "请求方式").equals(request.path("method").asText("POST"))) {
            throw new IllegalArgumentException("请求方式必须与 JSON 中 method 一致");
        }
        action.requestBody = request.toString();
        action.assertionsJson = interfaceAssertionJson(sheet);
        action.responseVariablesJson = responseVariablesJson(sheet);
        return action;
    }

    private void writeHttpSheet(Sheet sheet, PlatformModels.CaseAction action) {
        writeHeader(sheet, 2, new Object[] {"应用", "请求方式", "填写说明"});
        writeRow(sheet, 3, new Object[] {blank(action.applicationName) ? "default" : action.applicationName, json(action.requestBody, "HTTP 请求").path("method").asText("POST"), "请求参数配置 connection、method、path、headers、body；断言核对 status、contentType、body。"});
        addListValidation(sheet, 3, 3, 1, 1, new String[] {"GET", "POST"}, "请求方式", "请选择 GET 或 POST");
        writeSection(sheet, 5, REQUEST_SECTION);
        writeHeader(sheet, 6, new Object[] {JSON_DATA_FIELD});
        writeRow(sheet, 7, new Object[] {action.requestBody});
        writeSection(sheet, 9, RESPONSE_ASSERTION_SECTION);
        writeHeader(sheet, 10, new Object[] {JSON_DATA_FIELD});
        writeRow(sheet, 11, new Object[] {interfaceExpectedJson(action.assertionsJson)});
        writeResponseVariablesSection(sheet, 13, action.responseVariablesJson);
        finishSheet(sheet, 4);
    }


    private PlatformModels.CaseAction templateHttpAction() {
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = HTTP;
        action.name = "HTTP POST 并核对响应";
        action.description = "连接标识取基础配置中的当前运行环境；示例接口按原文返回请求正文。";
        action.requestBody = "{\"connection\":\"demo\",\"method\":\"POST\",\"path\":\"/echo\",\"headers\":{\"Content-Type\":\"text/plain; charset=UTF-8\"},\"body\":\"hello\"}";
        action.assertionsJson = "{\"version\":2,\"expected\":{\"status\":200,\"contentType\":\"text/plain; charset=UTF-8\",\"body\":\"hello\"}}";
        return action;
    }


    private String responseVariablesJson(Sheet sheet) {
        if (!sectionExists(sheet, RESPONSE_VARIABLE_SECTION)) return null;
        int section = requiredSection(sheet, RESPONSE_VARIABLE_SECTION);
        List<String> fields = tableHeadersOrEmpty(sheet, section + 1, sheet.getSheetName());
        if (fields.isEmpty()) return null;
        if (sheet.getRow(section + 2) == null || blankRow(sheet.getRow(section + 2))) return null;
        Row row = requiredRow(sheet, section + 2, "变量声明缺少配置行");
        Map<String, Object> variables = new LinkedHashMap<String, Object>();
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index).trim();
            if (!field.matches("\\[[a-zA-Z0-9_.-]+\\]")) {
                throw new IllegalArgumentException("变量声明名称必须使用 [变量名] 格式：" + cellLocation(sheet, section + 1, index));
            }
            String name = field.substring(1, field.length() - 1);
            String declaration = required(value(row.getCell(index)), "变量声明不能为空：" + cellLocation(sheet, section + 2, index));
            boolean count = declaration.endsWith("#COUNT#");
            if (count) declaration = declaration.substring(0, declaration.length() - "#COUNT#".length());
            if (declaration.contains(RegexTemplate.END) && !declaration.contains(RegexTemplate.START)) {
                throw new IllegalArgumentException("变量声明存在未配对的 #ENDREGEX#：" + cellLocation(sheet, section + 2, index));
            }
            int ruleIndex = declaration.indexOf(RegexTemplate.START);
            String path = ruleIndex < 0 ? declaration : declaration.substring(0, ruleIndex).trim();
            String rule = ruleIndex < 0 ? "" : declaration.substring(ruleIndex).trim();
            if (path.isEmpty()) throw new IllegalArgumentException("变量声明 JSONPath 不能为空：" + cellLocation(sheet, section + 2, index));
            if (rule.isEmpty() && count) {
                Map<String, Object> declarationValue = new LinkedHashMap<String, Object>();
                declarationValue.put("path", path);
                declarationValue.put("count", true);
                variables.put(name, declarationValue);
            } else if (rule.isEmpty()) {
                variables.put(name, path);
            } else {
                if (!rule.startsWith(RegexTemplate.START) || !rule.endsWith(RegexTemplate.END)) {
                    throw new IllegalArgumentException("变量声明正则必须使用 #REGEX#...#ENDREGEX#：" + cellLocation(sheet, section + 2, index));
                }
                try {
                    String regex = RegexTemplate.singleExpression(rule);
                    Map<String, Object> declarationValue = new LinkedHashMap<String, Object>();
                    declarationValue.put("path", path);
                    declarationValue.put("regex", regex);
                    if (count) declarationValue.put("count", true);
                    variables.put(name, declarationValue);
                }
                catch (Exception e) { throw new IllegalArgumentException("变量声明正则表达式不合法：" + rule + "；" + e.getMessage(), e); }
            }
        }
        return writeJson(variables);
    }

    private String assertionJsonOrFailure(Sheet sheet, PlatformModels.CaseAction action) {
        return interfaceAssertionJson(sheet);
    }

    private JsonNode jsonNode(Sheet sheet, Cell sourceCell, String source, String label) {
        try {
            JsonNode root = preciseObjectMapper.readTree(escapeRegexZones(source));
            if (root == null) {
                throw datasetError(sheet, sourceCell, label + " JSON 根节点不能为空");
            }
            return root;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw datasetError(sheet, sourceCell, label + " JSON 格式不正确：" + e.getMessage());
        }
    }

    private String interfaceAssertionJson(Sheet sheet) {
        int section = requiredSection(sheet, RESPONSE_ASSERTION_SECTION);
        Map<String, Integer> datasetSections = interfaceDatasetSections(sheet, section);
        Set<String> referenced = new LinkedHashSet<String>();
        JsonNode expected = interfaceRoot(sheet, section, datasetSections, referenced, new LinkedHashSet<String>());
        validateRegexPatterns(expected, "接口断言");
        for (String regionId : datasetSections.keySet()) {
            if (!referenced.contains(regionId)) {
                throw new IllegalArgumentException("数组数据集未被接口断言引用：数组数据集:" + regionId);
            }
        }
        ObjectNode result = preciseObjectMapper.createObjectNode();
        result.put("version", INTERFACE_ASSERTION_VERSION);
        result.set("expected", expected);
        return result.toString();
    }

    private String interfaceAssertionJson(Map<String, Object> expected) {
        ObjectNode result = preciseObjectMapper.createObjectNode();
        result.put("version", INTERFACE_ASSERTION_VERSION);
        result.set("expected", preciseObjectMapper.valueToTree(expected));
        return result.toString();
    }

    private String interfaceAssertionJson(JsonNode expected) {
        ObjectNode result = preciseObjectMapper.createObjectNode();
        result.put("version", INTERFACE_ASSERTION_VERSION);
        result.set("expected", expected);
        return result.toString();
    }

    private String interfaceExpectedJson(String source) {
        JsonNode root = json(source, "接口断言");
        if (root != null && root.isObject() && root.path("version").asInt() == INTERFACE_ASSERTION_VERSION && root.has("expected")) {
            return unescapeRegexZones(root.get("expected").toString());
        }
        return root == null ? "{}" : unescapeRegexZones(root.toString());
    }

    private JsonNode interfaceRoot(
            Sheet sheet,
            int section,
            Map<String, Integer> datasetSections,
            Set<String> referenced,
            Set<String> visiting) {
        int endRow = nextInterfaceSectionRow(sheet, section);
        List<String> fields = tableHeadersOrEmpty(sheet, section + 1, sheet.getSheetName());
        if (fields.isEmpty()) {
            requireBlankInterfaceRows(sheet, section + 1, endRow, "接口断言");
            return preciseObjectMapper.createObjectNode();
        }
        if (Collections.singletonList(JSON_DATA_FIELD).equals(fields)) {
            Cell source = cell(sheet, section + 2, 0);
            JsonNode root = jsonNode(sheet, source, requiredInterfaceJson(sheet, section + 2, endRow, "接口断言"), "接口断言");
            validateAssertionObjectKeys(root, sheet, source);
            return root;
        }
        if (fields.contains(JSON_DATA_FIELD)) {
            throw new IllegalArgumentException("接口断言的 #JSON# 列必须是唯一字段：" + cellLocation(sheet, section + 1, fields.indexOf(JSON_DATA_FIELD)));
        }
        rejectPlaceholders(fields, sheet, section + 1);
        validateInterfaceFields(fields, sheet, section + 1);
        Row row = requiredRow(sheet, section + 2, "已填写接口断言表头，但期望值行为空");
        ObjectNode root = preciseObjectMapper.createObjectNode();
        interfaceRecord(sheet, row, fields, root, datasetSections, referenced, visiting, "接口断言");
        requireBlankInterfaceRows(sheet, section + 3, endRow, "接口断言");
        return root;
    }

    private Map<String, Integer> interfaceDatasetSections(Sheet sheet, int rootSection) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (int rowIndex = rootSection + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            String title = value(cell(sheet, rowIndex, 0));
            if (!title.startsWith("数组数据集:")) continue;
            Matcher matcher = DATASET_SECTION.matcher(title);
            if (!matcher.matches() || blank(matcher.group(1))) {
                throw new IllegalArgumentException("数组数据集区域标识不正确：" + cellLocation(sheet, rowIndex, 0));
            }
            String regionId = matcher.group(1).trim();
            if (result.containsKey(regionId)) {
                throw new IllegalArgumentException("数组数据集区域标识重复：" + regionId);
            }
            result.put(regionId, rowIndex);
        }
        return result;
    }

    private JsonNode interfaceDataset(
            Sheet sheet,
            String regionId,
            Map<String, Integer> datasetSections,
            Set<String> referenced,
            Set<String> visiting) {
        Integer section = datasetSections.get(regionId);
        if (section == null) {
            throw new IllegalArgumentException("接口断言引用的数组数据集不存在：" + regionId);
        }
        if (!visiting.add(regionId)) {
            throw new IllegalArgumentException("接口断言数组数据集存在循环引用：" + regionId);
        }
        referenced.add(regionId);
        int endRow = nextInterfaceSectionRow(sheet, section);
        List<String> fields = tableHeadersOrEmpty(sheet, section + 1, sheet.getSheetName());
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("数组数据集缺少字段列头：数组数据集:" + regionId);
        }
        if (fields.contains(JSON_DATA_FIELD)) {
            throw new IllegalArgumentException("数组数据集不允许使用 #JSON#：" + cellLocation(sheet, section + 1, fields.indexOf(JSON_DATA_FIELD)));
        }
        rejectPlaceholders(fields, sheet, section + 1);
        validateInterfaceFields(fields, sheet, section + 1);
        ArrayNode rows = preciseObjectMapper.createArrayNode();
        for (int rowIndex = section + 2; rowIndex < endRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (blankRow(row)) continue;
            rejectExtraInterfaceCells(sheet, row, fields.size(), rowIndex, "数组数据集:" + regionId);
            ObjectNode item = preciseObjectMapper.createObjectNode();
            interfaceRecord(sheet, row, fields, item, datasetSections, referenced, visiting, "数组数据集:" + regionId);
            rows.add(item);
        }
        visiting.remove(regionId);
        return rows;
    }

    private void interfaceRecord(
            Sheet sheet,
            Row row,
            List<String> fields,
            ObjectNode target,
            Map<String, Integer> datasetSections,
            Set<String> referenced,
            Set<String> visiting,
            String label) {
        rejectExtraInterfaceCells(sheet, row, fields.size(), row.getRowNum(), label);
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index);
            JsonNode fieldValue = interfaceCellValue(sheet, row.getCell(index), datasetSections, referenced, visiting, label);
            setInterfacePath(target, field, fieldValue, sheet, row.getRowNum(), index);
        }
    }

    private JsonNode interfaceCellValue(
            Sheet sheet,
            Cell cell,
            Map<String, Integer> datasetSections,
            Set<String> referenced,
            Set<String> visiting,
            String label) {
        String text = value(cell);
        Matcher matcher = DATASET_REFERENCE.matcher(text);
        if (matcher.matches()) {
            String regionId = matcher.group(1).trim();
            if (blank(regionId)) {
                throw new IllegalArgumentException(label + "数组引用区域标识不能为空：" + cellLocation(sheet, cell.getRowIndex(), cell.getColumnIndex()));
            }
            return interfaceDataset(sheet, regionId, datasetSections, referenced, visiting);
        }
        if (text.startsWith("#REF#")) {
            throw new IllegalArgumentException(label + "数组引用必须使用 #REF#区域标识：" + cellLocation(sheet, cell.getRowIndex(), cell.getColumnIndex()));
        }
        if (cell == null || cell.getCellType() == CellType.BLANK) return NullNode.getInstance();
        Object typed = typedValue(cell);
        if (typed instanceof String && ((String) typed).isEmpty()) return NullNode.getInstance();
        if (NULL_CELL.equals(typed)) return NullNode.getInstance();
        return preciseObjectMapper.valueToTree(typed);
    }

    private void setInterfacePath(ObjectNode target, String field, JsonNode fieldValue, Sheet sheet, int rowIndex, int columnIndex) {
        String[] segments = field.substring(2).split("\\.");
        ObjectNode current = target;
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            boolean last = index == segments.length - 1;
            if (last) {
                if (current.has(segment)) {
                    throw new IllegalArgumentException("接口断言字段路径重复：" + cellLocation(sheet, rowIndex, columnIndex));
                }
                current.set(segment, fieldValue);
                continue;
            }
            JsonNode child = current.get(segment);
            if (child == null) {
                ObjectNode next = preciseObjectMapper.createObjectNode();
                current.set(segment, next);
                current = next;
            } else if (child.isObject()) {
                current = (ObjectNode) child;
            } else {
                throw new IllegalArgumentException("接口断言字段路径存在嵌套冲突：" + cellLocation(sheet, rowIndex, columnIndex));
            }
        }
    }

    private void validateInterfaceFields(List<String> fields, Sheet sheet, int rowIndex) {
        for (int index = 0; index < fields.size(); index++) {
            if (!INTERFACE_FIELD.matcher(fields.get(index)).matches()) {
                throw new IllegalArgumentException("接口断言字段列头必须使用 $.field JSONPath：" + cellLocation(sheet, rowIndex, index));
            }
        }
    }

    private void rejectExtraInterfaceCells(Sheet sheet, Row row, int fieldCount, int rowIndex, String label) {
        for (int index = fieldCount; index < row.getLastCellNum(); index++) {
            if (!blank(value(row.getCell(index)))) {
                throw new IllegalArgumentException(label + "数据行超出字段列头：" + cellLocation(sheet, rowIndex, index));
            }
        }
    }

    private String requiredInterfaceJson(Sheet sheet, int rowIndex, int endRow, String label) {
        StringBuilder source = new StringBuilder();
        boolean found = false;
        if (blankRow(sheet.getRow(rowIndex))) {
            throw new IllegalArgumentException(label + "的 #JSON# 内容不能为空：" + rowLocation(sheet, rowIndex));
        }
        for (int currentRow = rowIndex; currentRow < endRow; currentRow++) {
            Row row = sheet.getRow(currentRow);
            if (blankRow(row)) continue;
            for (int column = 1; column < row.getLastCellNum(); column++) {
                if (!blank(value(row.getCell(column)))) {
                    throw new IllegalArgumentException(label + "的 #JSON# 列不能填写其他列：" + cellLocation(sheet, currentRow, column));
                }
            }
            Cell cell = row.getCell(0);
            if (blank(value(cell))) {
                throw new IllegalArgumentException(label + "的 #JSON# 内容不能为空：" + cellLocation(sheet, currentRow, 0));
            }
            if (found) source.append('\n');
            source.append(rawValue(cell));
            found = true;
        }
        if (!found) {
            throw new IllegalArgumentException(label + "的 #JSON# 内容不能为空：" + rowLocation(sheet, rowIndex));
        }
        return source.toString();
    }

    private void requireBlankInterfaceRows(Sheet sheet, int startRow, int endRow, String label) {
        for (int rowIndex = startRow; rowIndex < endRow; rowIndex++) {
            if (!blankRow(sheet.getRow(rowIndex))) {
                throw new IllegalArgumentException(label + "区域外存在字段列头或数据行：" + rowLocation(sheet, rowIndex));
            }
        }
    }

    private void validateAssertionObjectKeys(JsonNode root, Sheet sheet, Cell source) {
        if (root == null || !root.isObject()) return;
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (field.startsWith("$.")) {
                throw new IllegalArgumentException("接口断言 #JSON# 填写响应对象；JSONPath 仅用于字段表列头：" + cellLocation(sheet, source.getRowIndex(), source.getColumnIndex()));
            }
        }
    }

    private int nextInterfaceSectionRow(Sheet sheet, int section) {
        for (int rowIndex = section + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            String title = value(cell(sheet, rowIndex, 0));
            if ("接口断言".equals(title) || "变量声明".equals(title) || "附件".equals(title) || title.startsWith("数组数据集:")) {
                return rowIndex;
            }
        }
        return sheet.getLastRowNum() + 1;
    }

    private PlatformModels.CaseAction readAssertAction(Sheet sheet) {
        validateSettings(sheet);
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = ASSERT;
        action.datasourceKey = requiredSetting(sheet, "数据源标识");
        action.querySql = requiredSetting(sheet, "查询 SQL");
        action.datasetName = assertTable(action.querySql, settingLocation(sheet, "查询 SQL"));
        action.responseVariablesJson = responseVariablesJson(sheet);
        int sectionRow = requiredSection(sheet, EXPECTED_SECTION);
        Map<String, String> expectedSettings = sectionSettings(sheet, sectionRow + 1, sheet.getSheetName());
        action.compareMode = compareMode(expectedSettings.get("比较方式"), sectionSettingLocation(sheet, sectionRow + 1, "比较方式"));
        if ("NOT_EXISTS".equals(action.compareMode)) {
            return action;
        }
        if ("ROW_COUNT".equals(action.compareMode)) {
            action.expectedRowCount = nonNegativeInteger(requiredSectionSetting(sheet, sectionRow + 1, "期望行数"), "期望行数", sectionSettingLocation(sheet, sectionRow + 1, "期望行数"));
            return action;
        }
        int dataSection = requiredSection(sheet, DATA_SECTION);
        List<String> fields = tableHeaders(sheet, dataSection + 1, sheet.getSheetName());
        if (Collections.singletonList(JSON_DATA_FIELD).equals(fields)) {
            Cell jsonCell = requiredJsonDataCell(sheet, dataSection + 2, "数据记录");
            List<Map<String, Object>> rows = databaseDataset(sheet, jsonCell, "数据记录");
            validateDatabaseFields(sheet, jsonCell, rows, false);
            validateRegexPatterns(preciseObjectMapper.valueToTree(rows), "数据库断言");
            action.expectedRowsJson = writeJson(rows);
            return action;
        }
        if (fields.contains(JSON_DATA_FIELD)) {
            throw new IllegalArgumentException("#JSON# 只能作为比对数据记录的唯一字段：" + cellLocation(sheet, dataSection + 1, fields.indexOf(JSON_DATA_FIELD)));
        }
        rejectPlaceholders(fields, sheet, dataSection + 1);
        int dataEndRow = sectionExists(sheet, RESPONSE_VARIABLE_SECTION)
                ? requiredSection(sheet, RESPONSE_VARIABLE_SECTION) - 1
                : sheet.getLastRowNum();
        List<Map<String, Object>> rows = tableRows(sheet, dataSection + 2, dataEndRow, fields);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("数据库断言没有期望结果：" + rowLocation(sheet, sectionRow + 5));
        }
        validateRegexPatterns(preciseObjectMapper.valueToTree(rows), "数据库断言");
        action.expectedRowsJson = writeJson(rows);
        return action;
    }

    private void validateRegexPatterns(JsonNode node, String label) {
        if (node == null) return;
        if (node.isTextual() && RegexTemplate.contains(node.asText())) {
            try {
                RegexTemplate.compile(node.asText());
            } catch (Exception e) {
                throw new IllegalArgumentException(label + "正则模板不合法：" + node.asText() + "；" + e.getMessage(), e);
            }
            return;
        }
        if (node.isArray() || node.isObject()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) validateRegexPatterns(values.next(), label);
        }
    }

    // #REGEX#...#ENDREGEX# 标记内的正则统一按字面书写（单反斜杠）。#JSON# 单元格是 JSON 文本，
    // 导入时先把正则标记区内的 \ 和 " 转成 JSON 合法形式再解析；导出时反向还原，保证两种布局写法一致。
    private static String escapeRegexZones(String text) {
        if (text == null || text.indexOf(RegexTemplate.START) < 0) return text;
        StringBuilder result = new StringBuilder(text.length() + 16);
        int offset = 0;
        while (offset < text.length()) {
            int start = text.indexOf(RegexTemplate.START, offset);
            if (start < 0) {
                result.append(text, offset, text.length());
                break;
            }
            int end = text.indexOf(RegexTemplate.END, start + RegexTemplate.START.length());
            if (end < 0) {
                result.append(text, offset, text.length());
                break;
            }
            result.append(text, offset, start);
            for (int index = start; index < end + RegexTemplate.END.length(); index++) {
                char ch = text.charAt(index);
                if (ch == '\\') {
                    result.append("\\\\");
                } else if (ch == '"') {
                    result.append("\\\"");
                } else {
                    result.append(ch);
                }
            }
            offset = end + RegexTemplate.END.length();
        }
        return result.toString();
    }

    private static String unescapeRegexZones(String text) {
        if (text == null || text.indexOf(RegexTemplate.START) < 0) return text;
        StringBuilder result = new StringBuilder(text.length());
        int offset = 0;
        while (offset < text.length()) {
            int start = text.indexOf(RegexTemplate.START, offset);
            if (start < 0) {
                result.append(text, offset, text.length());
                break;
            }
            int end = text.indexOf(RegexTemplate.END, start + RegexTemplate.START.length());
            if (end < 0) {
                result.append(text, offset, text.length());
                break;
            }
            result.append(text, offset, start);
            int zoneEnd = end + RegexTemplate.END.length();
            for (int index = start; index < zoneEnd; index++) {
                char ch = text.charAt(index);
                if (ch == '\\' && index + 1 < zoneEnd) {
                    result.append(text.charAt(index + 1));
                    index++;
                } else {
                    result.append(ch);
                }
            }
            offset = zoneEnd;
        }
        return result.toString();
    }

    private JsonNode requestJson(Sheet sheet, Cell sourceCell) {
        try {
            JsonNode root = preciseObjectMapper.readTree(escapeRegexZones(value(sourceCell)));
            if (root == null || !root.isObject()) {
                throw datasetError(sheet, sourceCell, "请求参数 JSON 根节点必须是对象");
            }
            return root;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw datasetError(sheet, sourceCell, "请求参数 JSON 格式不正确：" + e.getMessage());
        }
    }

    private List<Map<String, Object>> databaseDataset(Sheet sheet, Cell sourceCell, String label) {
        String source = value(sourceCell).trim();
        if (JSON_DATA_FIELD.equals(source)) {
            sourceCell = cell(sheet, sourceCell.getRowIndex() + 1, sourceCell.getColumnIndex());
            source = value(sourceCell).trim();
        }
        List<Map<String, Object>> rows = source.startsWith("{")
                ? jsonDataset(sheet, sourceCell, source, label)
                : csvDataset(sheet, sourceCell, source, label);
        if (rows.isEmpty()) {
            throw datasetError(sheet, sourceCell, label + "至少需要一条数据记录");
        }
        return rows;
    }

    private List<Map<String, Object>> jsonDataset(Sheet sheet, Cell sourceCell, String source, String label) {
        try {
            JsonNode root = preciseObjectMapper.readTree(escapeRegexZones(source));
            if (root == null || !root.isObject() || root.size() != 1) {
                throw datasetError(sheet, sourceCell, label + " JSON 必须是仅包含一个 DBeaver SQL 属性的对象");
            }
            JsonNode records = root.fields().next().getValue();
            if (!records.isArray()) {
                throw datasetError(sheet, sourceCell, label + " JSON 的 SQL 属性值必须是数组");
            }
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            List<String> fields = null;
            int recordNo = 0;
            for (JsonNode record : records) {
                recordNo++;
                if (!record.isObject() || record.size() == 0) {
                    throw datasetError(sheet, sourceCell, label + "第" + recordNo + "条记录必须是非空对象");
                }
                List<String> currentFields = new ArrayList<String>();
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                Iterator<Map.Entry<String, JsonNode>> iterator = record.fields();
                while (iterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = iterator.next();
                    currentFields.add(entry.getKey());
                    if (!entry.getValue().isValueNode()) {
                        throw datasetError(sheet, sourceCell, label + "第" + recordNo + "条记录字段“" + entry.getKey() + "”必须是数据库标量值");
                    }
                    row.put(entry.getKey(), datasetJsonValue(entry.getValue()));
                }
                if (fields == null) {
                    fields = currentFields;
                } else if (!new LinkedHashSet<String>(fields).equals(new LinkedHashSet<String>(currentFields))) {
                    throw datasetError(sheet, sourceCell, label + "第" + recordNo + "条记录字段与第一条记录不一致");
                }
                rows.add(row);
            }
            return rows;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw datasetError(sheet, sourceCell, label + " JSON 格式不正确：" + e.getMessage());
        }
    }

    private Object datasetJsonValue(JsonNode value) {
        if (value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.decimalValue();
        return value.textValue();
    }

    private List<Map<String, Object>> csvDataset(Sheet sheet, Cell sourceCell, String source, String label) {
        try {
            CsvParserSettings settings = new CsvParserSettings();
            settings.setHeaderExtractionEnabled(false);
            settings.setSkipEmptyLines(true);
            settings.setLineSeparatorDetectionEnabled(true);
            settings.setKeepQuotes(true);
            settings.setMaxCharsPerColumn(32767);
            settings.setMaxColumns(1024);
            List<String[]> records = new CsvParser(settings).parseAll(new StringReader(source));
            if (records.isEmpty()) {
                return Collections.emptyList();
            }
            String[] header = records.get(0);
            List<String> fields = new ArrayList<String>();
            for (int index = 0; index < header.length; index++) {
                String field = csvText(header[index]).trim();
                if (index == 0 && field.startsWith("\uFEFF")) field = field.substring(1);
                if (blank(field)) {
                    throw datasetError(sheet, sourceCell, label + " CSV 第1行第" + (index + 1) + "列字段名不能为空");
                }
                if (fields.contains(field)) {
                    throw datasetError(sheet, sourceCell, label + " CSV 第1行第" + (index + 1) + "列字段“" + field + "”重复");
                }
                fields.add(field);
            }
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (int recordIndex = 1; recordIndex < records.size(); recordIndex++) {
                String[] record = records.get(recordIndex);
                if (record.length != fields.size()) {
                    throw datasetError(sheet, sourceCell, label + " CSV 第" + (recordIndex + 1) + "行应有" + fields.size() + "列，实际为" + record.length + "列");
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                for (int index = 0; index < fields.size(); index++) {
                    row.put(fields.get(index), csvValue(record[index]));
                }
                rows.add(row);
            }
            return rows;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw datasetError(sheet, sourceCell, label + " CSV 格式不正确：" + e.getMessage());
        }
    }

    private Object csvValue(String value) {
        if (csvQuoted(value)) {
            return csvText(value);
        }
        if (JSON_NUMBER.matcher(value).matches()) {
            return new BigDecimal(value);
        }
        return value;
    }

    private String csvText(String value) {
        if (!csvQuoted(value)) return value;
        return value.substring(1, value.length() - 1).replace("\"\"", "\"");
    }

    private boolean csvQuoted(String value) {
        return value != null && value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"';
    }

    private void validateDatabaseFields(Sheet sheet, Cell sourceCell, List<Map<String, Object>> rows, boolean sqlFields) {
        if (!sqlFields) return;
        for (String field : rows.get(0).keySet()) {
            sqlIdentifier(field, "字段", cellLocation(sheet, sourceCell.getRowIndex(), sourceCell.getColumnIndex()));
        }
    }

    private PlatformModels.CaseAction parameterizedInsertAction(String datasourceKey, String table, List<Map<String, Object>> rows) {
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = INSERT;
        action.datasourceKey = datasourceKey;
        action.datasetName = table;
        Map<String, Object> variables = new LinkedHashMap<String, Object>();
        StringBuilder prepare = new StringBuilder();
        int variableIndex = 0;
        for (Map<String, Object> row : rows) {
            List<String> fields = new ArrayList<String>(row.keySet());
            List<String> parameters = new ArrayList<String>();
            for (String field : fields) {
                String variable = "data_" + variableIndex++ + "_" + field;
                Object fieldValue = row.get(field);
                variables.put(variable, fieldValue);
                parameters.add(":" + variable);
            }
            appendStatement(prepare, "INSERT INTO " + table + " (" + String.join(",", fields) + ") VALUES (" + String.join(",", parameters) + ")");
        }
        action.variablesJson = writeJson(variables);
        action.prepareSql = prepare.toString();
        return action;
    }

    private IllegalArgumentException datasetError(Sheet sheet, Cell sourceCell, String message) {
        return new IllegalArgumentException(message + "：" + cellLocation(sheet, sourceCell.getRowIndex(), sourceCell.getColumnIndex()));
    }

    private PlatformModels.CaseAction dataAction(String actionType, String datasourceKey, String table, List<Map<String, Object>> rows) {
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = actionType;
        action.datasourceKey = datasourceKey;
        action.datasetName = table;
        Map<String, Object> variables = new LinkedHashMap<String, Object>();
        StringBuilder cleanup = new StringBuilder();
        int variableIndex = 0;
        for (Map<String, Object> row : rows) {
            List<String> conditions = new ArrayList<String>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String field = entry.getKey();
                String variable = "data_" + variableIndex++ + "_" + field;
                Object value = entry.getValue();
                if (value == null) {
                    conditions.add(field + " IS NULL");
                } else {
                    variables.put(variable, value);
                    conditions.add(field + "=:" + variable);
                }
            }
            appendStatement(cleanup, "DELETE FROM " + table + " WHERE " + String.join(" AND ", conditions));
        }
        action.variablesJson = writeJson(variables);
        action.cleanupSql = cleanup.toString();
        return action;
    }

    private void appendStatement(StringBuilder statements, String statement) {
        if (statements.length() > 0) {
            statements.append(';');
        }
        statements.append(statement);
    }

    private void writeInfoSheet(Workbook workbook, PlatformModels.TestCase value, List<PlatformModels.CaseAction> actions) {
        Sheet sheet = workbook.createSheet(INFO_SHEET);
        writeHeader(sheet, 0, new Object[] {"字段", "示例值", "填写说明"});
        writeRow(sheet, 1, new Object[] {"案例名称", value == null ? "HTTP 与数据库示例" : value.name, "必填。同一分组且名称相同时，案例信息和步骤全部以导入文件为准。"});
        writeRow(sheet, 2, new Object[] {"业务说明", value == null ? "前置建立 demo_records 表及 demo HTTP 连接，验证调用、上传、数据断言与恢复。" : value.description, "必填。描述业务场景、前置条件和验证目标，用于案例列表和详情展示。"});
        writeRow(sheet, 3, new Object[] {"启用", value == null || Boolean.TRUE.equals(value.enabled) ? "是" : "否", "填“是”或“否”。空白按启用处理。"});
        writeRow(sheet, 4, new Object[] {"说明", "分组可在导入窗口选择；运行环境在执行时选择。", "这行不需要修改。步骤 Sheet 从左到右依次执行。"});
        writeSection(sheet, 6, "步骤总览");
        writeHeader(sheet, 7, new Object[] {"步骤号", "步骤类型", "目标表/接口", "核心核对", "执行目的", "runOnCaseEnd"});
        for (int index = 0; index < actions.size(); index++) {
            PlatformModels.CaseAction action = actions.get(index);
            writeRow(sheet, index + 8, new Object[] {
                    String.format("%02d", index + 1), label(action.actionType), actionTarget(action), Boolean.TRUE.equals(action.coreAssertion) ? "是" : "", action.name, Boolean.TRUE.equals(action.runOnCaseEnd) ? "是" : ""
            });
        }
        if (!actions.isEmpty()) {
            addListValidation(sheet, 8, 7 + actions.size(), 3, 3, new String[] {"是", "否"}, "核心核对", "可标记一个或多个步骤；空白表示否");
        }
        if (!actions.isEmpty()) {
            addListValidation(sheet, 8, 7 + actions.size(), 5, 5, new String[] {"是", "否"}, "runOnCaseEnd", "原位置照常执行，案例收尾时按原序号再执行一次；空白表示否");
        }
        finishSheet(sheet, 6);
        sheet.setColumnWidth(5, 22 * 256);
        sheet.setColumnWidth(0, 12 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        sheet.setColumnWidth(2, 38 * 256);
        sheet.setColumnWidth(3, 12 * 256);
        sheet.setColumnWidth(4, 48 * 256);
        addListValidation(sheet, 3, 3, 1, 1, new String[] {"是", "否"}, "启用状态", "请选择：是或否");
    }

    private void writeGuideSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet(GUIDE_SHEET);
        writeHeader(sheet, 0, new Object[] {"项目", "填写规则", "示例"});
        Object[][] rows = {
                {"案例信息", "案例名称和业务说明必填；说明场景、前置条件和验证目标。删除不需要的示例步骤后，按执行顺序重排步骤编号和总览。", "基础配置目录和填写说明不参与执行"},
                {"步骤 Sheet", "Sheet 名以前缀 步骤<两位序号>_<动作> 命名，允许追加后缀。步骤内容必须与动作一致。", "步骤01_插入_示例"},
                {"示例数据", "示例使用 demo_records；使用前替换为自己的表和字段，或按示例建表。", "record_id VARCHAR(64), record_name VARCHAR(128), amount INTEGER"},
                {"基础配置", "数据步骤引用数据源标识；HTTP 和上传步骤引用所选环境中的连接标识。", "default / demo"},
                {"HTTP 调用", "请求参数采用 #JSON#，包含 connection、path、headers、body；method 支持 GET/POST，GET 的 body 为空。地址和环境头由连接配置提供。", "请求方式与 JSON 中 method 保持一致"},
                {"HTTP 断言", "同时维护 status、contentType、body；字符集在请求 Content-Type 中明确。", "200 / text/plain; charset=UTF-8 / hello"},
                {"上传文件", "填写应用、连接标识、相对接口路径、文件字段、文件名、文件类型。附件可填 Base64 或当前工作簿内的附件 Sheet 名。", "demo / upload / example.txt"},
                {"插入和更新", "一步一表，字段横向、记录纵向；更新必须填写条件。#SQL# 唯一列可逐行填写当前表的 INSERT/UPDATE，不与字段表混填。", "UPDATE demo_records SET amount=2 WHERE record_id='demo-001'"},
                {"SQL 值", "数值按数值读取，普通文本转成 SQL 字符串；Excel 前导单引号强制文本。SQL 引号、命名参数、模板变量和表达式保留原意。", "输入 '01 得到 SQL '01'"},
                {"条件", "字段表下的条件区域逐行填写完整 WHERE，与记录按顺序对应；条件插入仅写入当前目标表。", "WHERE record_id='demo-001'"},
                {"数据库断言", "主查询只查询当前表，禁止 JOIN、UNION、多表 FROM 和 FROM 子查询；WHERE 子查询只限定当前表范围。", "SELECT record_id FROM demo_records WHERE ..."},
                {"比较方式", "在期望结果区域填写 EXACT_ROWS、ROW_COUNT 或 NOT_EXISTS；EXACT_ROWS 维护字段记录，ROW_COUNT 只填期望行数，NOT_EXISTS 不填字段记录。", "固定枚举使用下拉列表"},
                {"清理", "删除条件按字段横向、记录纵向填写，同一行各字段 AND 连接；或在条件区域填写完整 WHERE。不能无条件删除。", "record_id = demo-001"},
                {"清理顺序", "最终清理之后不再配置数据库断言；清理 SQL 异常直接失败。", "准备 → 调用 → 断言 → 清理"},
                {"备份恢复", "备份和恢复引用同一数据源、表名和快照标识；案例结束时恢复最早有效快照。", "initial"},
                {"核心核对", "可标记 HTTP、上传或数据库断言步骤；配置多个时均参与核对。", "是 / 否"},
                {"收尾步骤", "runOnCaseEnd 标记为是时，选中步骤正常执行后，在清理和快照恢复完成后再按原序号执行一次；收尾失败仍继续其他收尾。", "是 / 否，空白为否"},
                {"变量声明", "接口响应和数据库查询结果可通过 JSONPath 提取变量；后续步骤使用双花括号引用。", "[recordId] → $[0].record_id；{{recordId}}"},
                {"正则", "#REGEX# 与 #ENDREGEX# 之间按字面正则书写，反斜杠只写一个。#JSON# 区域的 JSON 转义由平台处理。", "连续双反斜杠会拒绝导入"},
                {"空值", "数据库字段中的 SQL NULL 和强制文本 NULL 含义不同；断言字段使用 #NULL# 表示空值。", "NULL / #NULL#"}
        };
        for (int index = 0; index < rows.length; index++) writeRow(sheet, index + 1, rows[index]);
        finishSheet(sheet, 3);
        sheet.createFreezePane(0, 1);
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 88 * 256);
        sheet.setColumnWidth(2, 48 * 256);
        for (int row = 1; row <= rows.length; row++) {
            sheet.getRow(row).setHeightInPoints(48);
            for (Cell cell : sheet.getRow(row)) CellUtil.setCellStyleProperty(cell, CellUtil.WRAP_TEXT, true);
        }
    }

    private void writeConfigSheet(Workbook workbook, List<PlatformModels.TestDataSource> dataSources, List<PlatformModels.Environment> environments) {
        Sheet sheet = workbook.createSheet(CONFIG_SHEET);
        writeHeader(sheet, 0, new Object[] {"配置类型", "标识", "名称/环境", "协议/数据库类型", "说明"});
        int rowIndex = 1;
        for (PlatformModels.TestDataSource dataSource : dataSources) {
            writeRow(sheet, rowIndex++, new Object[] {"数据源", dataSource.resourceKey, dataSource.name, dataSource.databaseType, dataSource.accessMode});
        }
        for (PlatformModels.Environment environment : environments) {
            for (PlatformModels.ProtocolConnection connection : environment.connections) {
                writeRow(sheet, rowIndex++, new Object[] {"连接", connection.key, environment.name, connection.protocol, connection.baseUrl});
            }
        }
        if (rowIndex == 1) writeRow(sheet, rowIndex++, new Object[] {"说明", "当前没有基础配置", "请先维护数据源和环境连接", "", ""});
        sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, 4));
        sheet.createFreezePane(0, 1);
        for (int column = 0; column < 5; column++) sheet.setColumnWidth(column, (column == 2 || column == 4 ? 58 : 26) * 256);
    }

    private void addListValidation(Sheet sheet, int firstRow, int lastRow, int firstColumn, int lastColumn,
                                   String[] values, String title, String prompt) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(values);
        DataValidation validation = helper.createValidation(constraint,
                new CellRangeAddressList(firstRow, lastRow, firstColumn, lastColumn));
        validation.setSuppressDropDownArrow(false);
        validation.setShowErrorBox(true);
        validation.createErrorBox(title, "只能选择下拉列表中的值");
        validation.createPromptBox(title, prompt);
        sheet.addValidationData(validation);
    }

    private void writeTemplateStep(Workbook workbook, String sheetName, PlatformModels.CaseAction action) {
        writeActionSheet(workbook, sheetName, action, Collections.emptySet());
    }






    private PlatformModels.CaseAction templateUpdateAction() {
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = UPDATE;
        action.name = "更新指定示例记录";
        action.datasourceKey = "default";
        action.datasetName = "demo_records";
        action.prepareSql = "UPDATE demo_records SET record_name='updated',amount=2 WHERE record_id='demo-001'";
        return action;
    }



    private PlatformModels.CaseAction templateInsertAction() {
        PlatformModels.CaseAction action = parameterizedInsertAction("default", "demo_records", Collections.singletonList(templateRecord()));
        action.name = "插入示例记录";
        return action;
    }

    private PlatformModels.CaseAction templateCleanupConditionAction() {
        Map<String, Object> conditions = new LinkedHashMap<String, Object>();
        conditions.put("record_id", "demo-001");
        PlatformModels.CaseAction action = dataAction(CLEANUP, "default", "demo_records", Collections.singletonList(conditions));
        action.name = "清理示例记录";
        return action;
    }


    private Map<String, Object> templateRecord() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("record_id", "demo-001");
        row.put("record_name", "example");
        row.put("amount", 1);
        return row;
    }


    private PlatformModels.CaseAction templateUploadAction() {
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = UPLOAD;
        action.name = "上传示例文本文件";
        PlatformModels.CaseUpload upload = new PlatformModels.CaseUpload();
        upload.connection = "demo";
        upload.path = "/upload";
        upload.fileField = "file";
        upload.fileName = "example.txt";
        upload.contentType = "text/plain";
        upload.headersJson = "{}";
        upload.formFieldsJson = "{}";
        upload.assertionsJson = "{\"version\":2,\"expected\":{\"received\":true}}";
        upload.contentBase64 = "aGVsbG8=";
        action.upload = upload;
        action.datasetName = uploadTarget(upload.path);
        return action;
    }

    private PlatformModels.CaseAction templateAssertAction() {
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = ASSERT;
        action.name = "核对示例记录";
        action.datasourceKey = "default";
        action.querySql = "SELECT record_id,record_name,amount FROM demo_records WHERE record_id='demo-001'";
        action.datasetName = "demo_records";
        action.compareMode = "EXACT_ROWS";
        action.coreAssertion = true;
        Map<String, Object> expected = templateRecord();
        expected.put("record_name", "updated");
        expected.put("amount", 2);
        action.expectedRowsJson = writeJson(Collections.singletonList(expected));
        return action;
    }



    private void writeActionSheet(Workbook workbook, String sheetName, PlatformModels.CaseAction action, Set<String> variableReferences) {
        Sheet sheet = workbook.createSheet(sheetName);
        writeHeader(sheet, 0, new Object[] {"步骤类型", label(action.actionType)});
        writeHeader(sheet, 1, new Object[] {"步骤名称", action.name});
        if (INSERT.equals(action.actionType) || CLEANUP.equals(action.actionType)) {
            writeDataSheet(sheet, action, variableReferences);
        } else if (BACKUP.equals(action.actionType) || RESTORE.equals(action.actionType)) {
            boolean backup = BACKUP.equals(action.actionType);
            writeHeader(sheet, 2, backup
                    ? new Object[] {"数据源标识", "表名", "快照标识", "备份后清空", "备份条件", "填写说明"}
                    : new Object[] {"数据源标识", "表名", "快照标识", "填写说明"});
            if (backup) {
                List<PlatformModels.SnapshotItem> items = SnapshotRules.snapshotItems(action);
                for (int index = 0; index < items.size(); index++) {
                    PlatformModels.SnapshotItem item = items.get(index);
                    int rowIndex = 3 + index;
                    writeRow(sheet, rowIndex, new Object[] {item.datasourceKey, item.datasetName, item.snapshotName,
                            Boolean.TRUE.equals(item.clearAfterBackup) ? "是" : "否", item.backupCondition,
                            "条件不含 WHERE，留空表示整表；备份、清空、还原使用同一条件。条件使用不会被修改的业务键。"});
                    writeMergedWrappedNote(sheet, rowIndex, 5, 6);
                }
                addListValidation(sheet, 3, 2 + items.size(), 3, 3, new String[] {"是", "否"}, "备份后清空", "请选择：是或否");
                finishSheet(sheet, 7);
                sheet.setColumnWidth(4, 48 * 256);
            } else {
                writeRow(sheet, 3, new Object[] {action.datasourceKey, action.datasetName, action.snapshotName, "按指定快照的备份条件恢复，无需重复填写条件；案例结束时自动恢复同表同条件的最早快照。"});
                finishSheet(sheet, 4);
                writeMergedWrappedNote(sheet, 3, 3, 4);
            }
        } else if (UPDATE.equals(action.actionType)) {
            writeUpdateSheet(sheet, action, variableReferences);
        } else if (HTTP.equals(action.actionType)) {
            writeHttpSheet(sheet, action);
        } else if (UPLOAD.equals(action.actionType)) {
            writeUploadSheet(sheet, action);
        } else if (ASSERT.equals(action.actionType)) {
            writeAssertSheet(sheet, action);
        } else {
            throw new IllegalArgumentException("不支持的步骤类型：" + action.actionType);
        }
        writeStepDescription(sheet, action);
    }

    private void writeStepDescription(Sheet sheet, PlatformModels.CaseAction action) {
        writeHeader(sheet, 1, new Object[] {"步骤名称", action.name, "步骤说明", action.description});
        writeMergedWrappedNote(sheet, 1, 3, 5);
        if (!blank(action.description)) {
            int width = (sheet.getColumnWidth(3) + sheet.getColumnWidth(4) + sheet.getColumnWidth(5)) / 256;
            int lines = 0;
            for (String line : action.description.split("\n", -1)) {
                int length = line.codePoints().map(character -> character > 255 ? 2 : 1).sum();
                lines += Math.max(1, (length + width - 1) / width);
            }
            sheet.getRow(1).setHeightInPoints(Math.min(409, Math.max(54, lines * 18)));
        }
    }

    private void writeDataSheet(Sheet sheet, PlatformModels.CaseAction action, Set<String> variableReferences) {
        writeFieldSheet(sheet, action, variableReferences);
    }

    private void writeFieldSheet(Sheet sheet, PlatformModels.CaseAction action, Set<String> variableReferences) {
        TableStepSql.Data data = TableStepSql.parse(action);
        JsonNode variables = json(action.variablesJson, "步骤变量");
        List<Map<String, String>> cleanupRows = CLEANUP.equals(action.actionType) ? cleanupFieldRows(data, variables) : Collections.emptyList();
        List<String> fields = cleanupRows.isEmpty() ? data.fields : new ArrayList<>(cleanupRows.get(0).keySet());
        writeHeader(sheet, 2, new Object[] {"数据源标识", "表名", "填写说明"});
        writeRow(sheet, 3, new Object[] {action.datasourceKey, data.table,
                CLEANUP.equals(action.actionType)
                        ? "删除条件按字段横向、记录纵向填写；同一行各字段用 AND 连接，每行分别删除。普通文本按字符串读取，Excel 前导单引号强制文本。复杂条件填写完整 WHERE，两种区域只填一种，条件值不得留空。"
                        : "一个步骤只操作一张表。数字按数值读取，普通文本按字符串读取；Excel 前导单引号强制文本。参数和表达式直接填写；条件区域填写完整 WHERE，与上方记录按顺序对应。"});
        int conditionRow = 5;
        if (!cleanupRows.isEmpty()) {
            writeSection(sheet, 5, DELETE_WHERE_SECTION);
            writeHeader(sheet, 6, fields.toArray());
            for (int index = 0; index < cleanupRows.size(); index++) writeFieldRow(sheet, 7 + index, cleanupRows.get(index).values().toArray());
        } else if (!CLEANUP.equals(action.actionType)) {
            writeSection(sheet, 5, INSERT.equals(action.actionType) ? DATA_SECTION : UPDATE_SET_SECTION);
            writeHeader(sheet, 6, data.fields.toArray());
            for (int index = 0; index < data.rows.size(); index++) {
                Object[] values = new Object[data.fields.size()];
                for (int column = 0; column < values.length; column++) values[column] = fieldSql(data.rows.get(index).get(column), variables);
                writeFieldRow(sheet, 7 + index, values);
            }
            conditionRow = 8 + data.rows.size();
        }
        if (cleanupRows.isEmpty() && data.conditions.stream().anyMatch(condition -> !condition.isEmpty())) {
            writeSection(sheet, conditionRow, "条件");
            for (int index = 0; index < data.conditions.size(); index++) {
                int row = conditionRow + 1 + index;
                writeRow(sheet, row, new Object[] {fieldSql(data.conditions.get(index), variables)});
                writeSqlCell(sheet, row, 0);
                if (data.fields.size() > 1) sheet.addMergedRegion(new CellRangeAddress(row, row, 0, data.fields.size() - 1));
            }
        }
        if (variables != null) {
            List<String> names = new ArrayList<>();
            variables.fieldNames().forEachRemaining(name -> {
                if (!name.startsWith("__platform_") && (!name.matches("data_[0-9]+_[A-Za-z_][A-Za-z0-9_$]*") || variableReferences.contains(name))) names.add(name);
            });
            if (!names.isEmpty()) {
                int section = sheet.getLastRowNum() + 2;
                writeSection(sheet, section, STEP_VARIABLES_SECTION);
                writeHeader(sheet, section + 1, new Object[] {"变量名", "值（JSON）"});
                for (int index = 0; index < names.size(); index++) {
                    String name = names.get(index);
                    writeRow(sheet, section + 2 + index, new Object[] {name, writeJson(variables.get(name))});
                }
            }
        }
        finishSheet(sheet, Math.max(4, fields.size()));
        if (cleanupRows.isEmpty() && fields.size() < 2 && data.conditions.stream().anyMatch(condition -> !condition.isEmpty())) sheet.setColumnWidth(0, 96 * 256);
        writeMergedWrappedNote(sheet, 3, 2, Math.max(4, fields.size()) - 1);
    }

    private void writeFieldRow(Sheet sheet, int rowIndex, Object[] values) {
        Row row = writeRow(sheet, rowIndex, values);
        for (Cell cell : row) {
            String expression = cell.getStringCellValue();
            // 占有量词按字符推进，长文本及成对单引号不累积递归匹配栈。
            if (expression.matches("(?s)'(?:''|[^'])*+'")) {
                cell.setCellValue(expression.substring(1, expression.length() - 1).replace("''", "'"));
                CellUtil.setCellStyleProperty(cell, CellUtil.QUOTE_PREFIXED, true);
            } else if (!rawSqlValue(expression)) {
                // 单独的列名等表达式与普通文本同形，用 SQL 括号保留其表达式含义。
                cell.setCellValue("(" + expression + ")");
            }
        }
    }

    private List<Map<String, String>> cleanupFieldRows(TableStepSql.Data data, JsonNode variables) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String condition : data.conditions) {
            String remaining = fieldSql(condition, variables).substring(5).trim();
            if (TableStepSql.keyword(remaining, "OR") >= 0) return Collections.emptyList();
            Map<String, String> row = new LinkedHashMap<>();
            while (!remaining.isEmpty()) {
                int and = TableStepSql.keyword(remaining, "AND");
                Matcher matcher = CLEANUP_FIELD.matcher((and < 0 ? remaining : remaining.substring(0, and)).trim());
                if (!matcher.matches() || row.containsKey(matcher.group(1))) return Collections.emptyList();
                row.put(matcher.group(1), matcher.group(2) == null ? "NULL" : matcher.group(2));
                remaining = and < 0 ? "" : remaining.substring(and + 3).trim();
                if (and >= 0 && remaining.isEmpty()) return Collections.emptyList();
            }
            if (row.isEmpty() || !rows.isEmpty() && !new ArrayList<>(rows.get(0).keySet()).equals(new ArrayList<>(row.keySet()))) return Collections.emptyList();
            rows.add(row);
        }
        return rows;
    }

    private Set<String> referencedStepVariables(List<PlatformModels.CaseAction> actions) {
        Set<String> names = new LinkedHashSet<>();
        Pattern templates = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
        Pattern parameters = Pattern.compile("'(?:''|[^'])*+'|(?<!:):([A-Za-z_][A-Za-z0-9_]*)");
        for (PlatformModels.CaseAction action : actions) {
            Matcher template = templates.matcher(writeJson(action));
            while (template.find()) names.add(template.group(1));
            JsonNode local = json(action.variablesJson, "步骤变量");
            for (String sql : new String[] {action.prepareSql, action.cleanupSql, action.querySql}) {
                if (sql == null) continue;
                Matcher parameter = parameters.matcher(sql);
                while (parameter.find()) {
                    String name = parameter.group(1);
                    if (name != null && (local == null || !local.has(name))) names.add(name);
                }
            }
        }
        return names;
    }

    private String fieldSql(String expression, JsonNode variables) {
        Matcher matcher = Pattern.compile("'(?:''|[^'])*+'|(?<!:):([A-Za-z_][A-Za-z0-9_]*)").matcher(expression);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = matcher.group();
            if (name != null && variables != null && variables.has(name)) {
                JsonNode node = variables.get(name);
                replacement = node.isNull() ? "NULL" : sqlLiteral(datasetJsonValue(node));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void writeUpdateSheet(Sheet sheet, PlatformModels.CaseAction action, Set<String> variableReferences) {
        writeFieldSheet(sheet, action, variableReferences);
    }


    private void writeUploadSheet(Sheet sheet, PlatformModels.CaseAction action) {
        if (action.upload == null) throw new IllegalStateException("上传步骤缺少文件配置");
        PlatformModels.CaseUpload upload = action.upload;
        writeHeader(sheet, 2, new Object[] {"应用", "连接标识", "接口路径", "文件字段", "文件名", "文件类型", "期望结果"});
        writeRow(sheet, 3, new Object[] {blank(action.applicationName) ? "default" : action.applicationName, upload.connection, upload.path, upload.fileField, upload.fileName, upload.contentType,
                expectedResultLabel(action)});
        addListValidation(sheet, 3, 3, 6, 6, new String[] {"成功", "失败"}, "期望结果", "请选择接口预期结果");
        writeFieldSection(sheet, 5, UPLOAD_HEADERS_SECTION, objectFields(upload.headersJson, "请求头"));
        writeFieldSection(sheet, 9, UPLOAD_FORM_FIELDS_SECTION, objectFields(upload.formFieldsJson, "表单字段"));
        InterfaceSectionLayout assertion = writeInterfaceAssertionSection(sheet, 13, upload.assertionsJson);
        writeResponseVariablesSection(sheet, assertion.nextRow + 1, upload.responseVariablesJson);
        int attachmentSection = assertion.nextRow + 5;
        writeSection(sheet, attachmentSection, UPLOAD_ATTACHMENT_SECTION);
        writeHeader(sheet, attachmentSection + 1, new Object[] {"Sheet名称或Base64"});
        String content = "SHEET".equals(upload.attachmentMode)
                ? required(upload.attachmentSheetName, "上传步骤缺少附件 Sheet 名称")
                : required(upload.contentBase64, "上传步骤缺少附件");
        int rowIndex = attachmentSection + 2;
        for (int begin = 0; begin < content.length(); begin += 30000) {
            writeRow(sheet, rowIndex++, new Object[] {content.substring(begin, Math.min(content.length(), begin + 30000))});
        }
        finishSheet(sheet, Math.max(7, assertion.width));
    }

    private InterfaceSectionLayout writeInterfaceAssertionSection(Sheet sheet, int sectionRow, String source) {
        writeSection(sheet, sectionRow, RESPONSE_ASSERTION_SECTION);
        JsonNode expected = interfaceExpectedNode(source);
        if (expected == null || !expected.isObject() || requiresJsonEncoding(expected)) {
            writeHeader(sheet, sectionRow + 1, new Object[] {JSON_DATA_FIELD});
            writeRow(sheet, sectionRow + 2, new Object[] {expected == null ? "null" : unescapeRegexZones(expected.toString())});
            return new InterfaceSectionLayout(sectionRow + 4, 1);
        }
        List<InterfaceRegion> regions = new ArrayList<InterfaceRegion>();
        InterfaceRecord root = interfaceRecordForExport(expected, "$", "root", regions);
        if (!root.values.isEmpty()) {
            writeHeader(sheet, sectionRow + 1, root.fields.toArray());
            writeInterfaceRow(sheet, sectionRow + 2, root.fields, root.values);
        }
        int nextRow = sectionRow + (root.values.isEmpty() ? 2 : 4);
        int width = Math.max(1, root.fields.size());
        for (InterfaceRegion region : regions) {
            writeSection(sheet, nextRow, "数组数据集:" + region.id);
            if (region.fields.isEmpty()) region.fields.add("$.value");
            writeHeader(sheet, nextRow + 1, region.fields.toArray());
            for (int index = 0; index < region.rows.size(); index++) {
                writeInterfaceRow(sheet, nextRow + 2 + index, region.fields, region.rows.get(index));
            }
            nextRow += Math.max(3, region.rows.size() + 2);
            width = Math.max(width, region.fields.size());
        }
        return new InterfaceSectionLayout(nextRow, width);
    }

    private JsonNode interfaceExpectedNode(String source) {
        if (blank(source)) return preciseObjectMapper.createObjectNode();
        JsonNode root = json(source, "接口断言");
        if (root != null && root.isObject() && root.path("version").asInt() == INTERFACE_ASSERTION_VERSION && root.has("expected")) {
            return root.get("expected");
        }
        return root == null ? preciseObjectMapper.createObjectNode() : root;
    }

    private InterfaceRecord interfaceRecordForExport(JsonNode value, String prefix, String regionSeed, List<InterfaceRegion> regions) {
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("接口断言期望值必须是对象：" + prefix);
        }
        InterfaceRecord record = new InterfaceRecord();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String path = prefix + "." + entry.getKey();
            JsonNode item = entry.getValue();
            if (item.isArray()) {
                String regionId = uniqueInterfaceRegionId(regionSeed + "_" + entry.getKey(), regions);
                record.fields.add(path);
                record.values.put(path, preciseObjectMapper.getNodeFactory().textNode("#REF#" + regionId));
                interfaceRegionForExport(item, regionId, regions);
            } else if (item.isObject()) {
                InterfaceRecord nested = interfaceRecordForExport(item, path, regionSeed + "_" + entry.getKey(), regions);
                record.fields.addAll(nested.fields);
                record.values.putAll(nested.values);
            } else {
                record.fields.add(path);
                record.values.put(path, item);
            }
        }
        return record;
    }

    private void interfaceRegionForExport(JsonNode value, String regionId, List<InterfaceRegion> regions) {
        if (!value.isArray()) throw new IllegalStateException("接口断言数组区域不是数组：" + regionId);
        InterfaceRegion region = new InterfaceRegion(regionId);
        regions.add(region);
        for (int index = 0; index < value.size(); index++) {
            JsonNode item = value.get(index);
            if (!item.isObject()) throw new IllegalStateException("接口断言数组只支持对象数据：" + regionId);
            InterfaceRecord record = interfaceRecordForExport(item, "$", regionId + "_" + (index + 1), regions);
            region.rows.add(record.values);
            for (String field : record.fields) if (!region.fields.contains(field)) region.fields.add(field);
        }
    }

    private String uniqueInterfaceRegionId(String candidate, List<InterfaceRegion> regions) {
        String base = candidate.replaceAll("[^A-Za-z0-9_.-]", "_");
        String result = base;
        int suffix = 2;
        while (containsInterfaceRegion(regions, result)) result = base + "_" + suffix++;
        return result;
    }

    private boolean containsInterfaceRegion(List<InterfaceRegion> regions, String id) {
        for (InterfaceRegion region : regions) if (region.id.equals(id)) return true;
        return false;
    }

    private void writeInterfaceRow(Sheet sheet, int rowIndex, List<String> fields, Map<String, JsonNode> values) {
        Object[] row = new Object[fields.size()];
        for (int index = 0; index < fields.size(); index++) {
            JsonNode value = values.get(fields.get(index));
            row[index] = value == null || value.isNull() ? "" : value.isTextual() ? value.asText() : jsonValue(value);
        }
        writeRow(sheet, rowIndex, row);
    }

    private void writeFieldSection(Sheet sheet, int rowIndex, String title, Map<String, JsonNode> fields) {
        writeSection(sheet, rowIndex, title);
        if (!fields.isEmpty()) {
            List<String> names = new ArrayList<String>(fields.keySet());
            writeHeader(sheet, rowIndex + 1, names.toArray());
            writeRow(sheet, rowIndex + 2, values(fields, names));
        }
    }

    private void writeResponseVariablesSection(Sheet sheet, int rowIndex, String source) {
        writeSection(sheet, rowIndex, RESPONSE_VARIABLE_SECTION);
        Map<String, JsonNode> values = objectFields(source, RESPONSE_VARIABLE_SECTION);
        if (values.isEmpty()) return;
        List<String> headers = new ArrayList<String>();
        Object[] declarations = new Object[values.size()];
        int index = 0;
        for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
            headers.add("[" + entry.getKey() + "]");
            JsonNode declaration = entry.getValue();
            String path = declaration.isObject() ? declaration.path("path").asText() : declaration.asText();
            declarations[index++] = path + (declaration.isObject() && declaration.has("regex")
                    ? RegexTemplate.START + declaration.path("regex").asText() + RegexTemplate.END : "")
                    + (declaration.path("count").asBoolean(false) ? "#COUNT#" : "");
        }
        writeHeader(sheet, rowIndex + 1, headers.toArray());
        writeRow(sheet, rowIndex + 2, declarations);
    }

    private void writeAssertSheet(Sheet sheet, PlatformModels.CaseAction action) {
        String compareMode = blank(action.compareMode) ? "EXACT_ROWS" : action.compareMode;
        List<Map<String, JsonNode>> rows = "EXACT_ROWS".equals(compareMode)
                ? jsonRows(action.expectedRowsJson, "数据库断言") : Collections.emptyList();
        Object expectedCount = "ROW_COUNT".equals(compareMode) ? action.expectedRowCount : "";
        writeHeader(sheet, 2, new Object[] {"数据源标识", "查询 SQL", "填写说明"});
        writeRow(sheet, 3, new Object[] {action.datasourceKey, action.querySql, "只能查询一张目标表；禁止 JOIN、UNION、多表 FROM 或 FROM 子查询；WHERE 子查询仅可用于限定当前目标表范围。EXACT_ROWS 请在下方“数据记录”区域填写字段表或 #JSON#。"});
        writeSection(sheet, 5, EXPECTED_SECTION);
        Object[] expectedSettingHeaders = "ROW_COUNT".equals(compareMode)
                ? new Object[] {"比较方式", "期望行数"}
                : new Object[] {"比较方式"};
        Object[] expectedSettingValues = "ROW_COUNT".equals(compareMode)
                ? new Object[] {compareMode, expectedCount}
                : new Object[] {compareMode};
        writeHeader(sheet, 6, expectedSettingHeaders);
        writeRow(sheet, 7, expectedSettingValues);
        addListValidation(sheet, 7, 7, 0, 0, new String[] {"EXACT_ROWS", "ROW_COUNT", "NOT_EXISTS"}, "比较方式", "请选择数据库断言比较方式");
        List<String> fields = fields(rows);
        if (fields.isEmpty() && "EXACT_ROWS".equals(compareMode)) {
            fields = Collections.singletonList(PLACEHOLDER_FIELD);
        }
        if ("EXACT_ROWS".equals(compareMode)) {
            writeSection(sheet, 9, DATA_SECTION);
            // 标量结果按字段展开；空值用 #NULL#，空格与正则保持原值，避免整份结果超过单元格长度。
            boolean jsonDataset = rows.isEmpty() || rows.stream().flatMap(row -> row.values().stream()).anyMatch(value ->
                    value.isContainerNode() || value.isNumber() && requiresJsonEncoding(value)
                            || value.isTextual() && NULL_CELL.equals(value.textValue()));
            if (jsonDataset) {
                writeHeader(sheet, 10, new Object[] {JSON_DATA_FIELD});
                writeRow(sheet, 11, new Object[] {unescapeRegexZones(writeJson(Collections.singletonMap(action.querySql, json(action.expectedRowsJson, "数据库断言"))))});
            } else if (!fields.isEmpty()) {
                writeHeader(sheet, 10, fields.toArray());
                writeJsonRows(sheet, 11, rows, fields);
            }
        }
        int variableSectionRow = "EXACT_ROWS".equals(compareMode) ? 12 + rows.size() : 9;
        if (!blank(action.responseVariablesJson)) {
            writeResponseVariablesSection(sheet, variableSectionRow, action.responseVariablesJson);
        }
        finishSheet(sheet, Math.max(6, fields.size()));
        sheet.setColumnWidth(0, 18 * 256);
        sheet.setColumnWidth(1, 48 * 256);
        sheet.setColumnWidth(2, 12 * 256);
        writeMergedWrappedNote(sheet, 3, 2, 5);
        writeSqlCell(sheet, 3, 1);
    }

    private Map<String, String> readInfo(Sheet sheet) {
        if (sheet == null) {
            throw new IllegalArgumentException("工作簿缺少“案例信息”Sheet");
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null) {
                continue;
            }
            String key = value(row.getCell(0));
            if (!blank(key)) {
                values.put(key, value(row.getCell(1)));
            }
        }
        return values;
    }

    private void applyCoreAssertions(Sheet sheet, List<PlatformModels.CaseAction> actions) {
        int overviewRow = requiredSection(sheet, "步骤总览");
        int stepNoColumn = headerColumn(sheet, overviewRow + 1, "步骤号");
        int coreColumn = headerColumn(sheet, overviewRow + 1, "核心核对");
        int endColumn = headerColumn(sheet, overviewRow + 1, "runOnCaseEnd");
        if (stepNoColumn < 0 || coreColumn < 0) {
            throw new IllegalArgumentException("案例信息步骤总览缺少“步骤号”或“核心核对”列：" + rowLocation(sheet, overviewRow + 1));
        }
        for (int index = 0; index < actions.size(); index++) {
            int rowIndex = overviewRow + 2 + index;
            String expectedStepNo = String.format("%02d", index + 1);
            String actualStepNo = value(cell(sheet, rowIndex, stepNoColumn));
            if (!expectedStepNo.equals(actualStepNo)) {
                throw new IllegalArgumentException("案例信息步骤总览步骤号应为“" + expectedStepNo + "”：" + cellLocation(sheet, rowIndex, stepNoColumn));
            }
            String coreValue = value(cell(sheet, rowIndex, coreColumn));
            actions.get(index).coreAssertion = blank(coreValue) ? false : yesNo(coreValue, "核心核对", cellLocation(sheet, rowIndex, coreColumn));
            if (endColumn >= 0) {
                String endValue = value(cell(sheet, rowIndex, endColumn));
                actions.get(index).runOnCaseEnd = !blank(endValue) && yesNo(endValue, "runOnCaseEnd", cellLocation(sheet, rowIndex, endColumn));
            }
        }
    }

    private void validateSettings(Sheet sheet) {
        List<String> headers = tableHeaders(sheet, 2, sheet.getSheetName());
        if (UPLOAD.equals(actionType(sheet)) && !java.util.Arrays.asList("应用", "连接标识", "接口路径", "文件字段", "文件名", "文件类型", "期望结果").equals(headers)) {
            throw new IllegalArgumentException("上传步骤设置表头必须为：应用、连接标识、接口路径、文件字段、文件名、文件类型、期望结果：" + rowLocation(sheet, 2));
        }
        sectionSettings(sheet, 2, sheet.getSheetName());
    }

    private Map<String, String> sectionSettings(Sheet sheet, int headerRowIndex, String sheetName) {
        List<String> headers = tableHeaders(sheet, headerRowIndex, sheetName);
        Row row = requiredRow(sheet, headerRowIndex + 1, "步骤设置值为空");
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (int index = 0; index < headers.size(); index++) {
            values.put(headers.get(index), value(row.getCell(index)));
        }
        return values;
    }

    private void applyExpectedOutcome(PlatformModels.CaseAction action, Map<String, String> settings, Sheet sheet) {
        String result = settings.get("期望结果");
        action.expectedResult = blank(result) ? "SUCCESS" : ("成功".equals(result.trim()) ? "SUCCESS" : "失败".equals(result.trim()) ? "FAILURE" : result.trim());
        String normalized = action.expectedResult.toUpperCase();
        if (!"SUCCESS".equals(normalized) && !"FAILURE".equals(normalized)) {
            throw new IllegalArgumentException("期望结果只能填写“成功”或“失败”：" + settingLocation(sheet, "期望结果"));
        }
        action.expectedResult = normalized;
    }

    private String expectedResultLabel(PlatformModels.CaseAction action) {
        return "FAILURE".equalsIgnoreCase(action.expectedResult) ? "失败" : "成功";
    }

    private String actionType(Sheet sheet) {
        String type = value(sheet.getRow(0) == null ? null : sheet.getRow(0).getCell(1));
        if ("备份数据表".equals(type) || BACKUP.equalsIgnoreCase(type)) return BACKUP;
        if ("恢复数据表".equals(type) || RESTORE.equalsIgnoreCase(type)) return RESTORE;
        if (type.contains("插入") || INSERT.equalsIgnoreCase(type)) {
            return INSERT;
        }
        if (type.contains("更新") || UPDATE.equalsIgnoreCase(type)) {
            return UPDATE;
        }
        if (type.contains("上传") || UPLOAD.equalsIgnoreCase(type)) {
            return UPLOAD;
        }
        if ("HTTP调用".equals(type) || HTTP.equalsIgnoreCase(type)) return HTTP;
        if (type.contains("断言") || ASSERT.equalsIgnoreCase(type)) {
            return ASSERT;
        }
        if (type.contains("清理") || CLEANUP.equalsIgnoreCase(type)) {
            return CLEANUP;
        }
        return "";
    }

    private String sheetName(int stepNo, PlatformModels.CaseAction action) {
        return String.format("步骤%02d_%s", stepNo, shortActionLabel(action.actionType));
    }

    private String shortActionLabel(String type) {
        return BACKUP.equals(type) ? "备份" : RESTORE.equals(type) ? "恢复" : INSERT.equals(type) ? "插入" : UPDATE.equals(type) ? "更新" : UPLOAD.equals(type) ? "上传" : HTTP.equals(type) ? "HTTP" : ASSERT.equals(type) ? "断言" : "清理";
    }

    private String actionTarget(PlatformModels.CaseAction action) {
        if (BACKUP.equals(action.actionType) || RESTORE.equals(action.actionType)) return action.datasetName;
        if (INSERT.equals(action.actionType) || UPDATE.equals(action.actionType) || CLEANUP.equals(action.actionType)) {
            if (!blank(action.datasetName)) return action.datasetName;
            return required(action.datasetName, "数据步骤缺少目标表");
        }
        if (ASSERT.equals(action.actionType)) {
            if (!blank(action.datasetName)) return action.datasetName;
            return assertTable(action.querySql, "案例步骤");
        }
        if (HTTP.equals(action.actionType)) return "HTTP " + json(action.requestBody, "HTTP 请求").path("method").asText("POST");
        if (UPLOAD.equals(action.actionType)) {
            return uploadTarget(action.upload == null ? null : action.upload.path);
        }
        throw new IllegalArgumentException("不支持的步骤类型：" + action.actionType);
    }

    private String uploadTarget(String path) {
        String value = required(path, "上传步骤缺少接口路径");
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash < value.length() - 1 ? value.substring(slash + 1) : value;
    }

    private static String assertTable(String sql, String sheetName) {
        String query = required(sql, "数据库断言缺少查询 SQL：" + sheetName);
        String upper = query.toUpperCase();
        if (containsTopLevelKeyword(query, "INTO")) {
            throw new IllegalArgumentException("数据库断言不能使用 SELECT INTO 写入表或文件：" + sheetName);
        }
        if (!upper.matches("(?s)^\\s*SELECT\\b.*") || query.indexOf(';') >= 0
                || upper.matches("(?s).*\\b(JOIN|UNION)\\b.*")
                || upper.matches("(?s).*\\bFROM\\s*\\(.*")) {
            throw new IllegalArgumentException("数据库断言只能查询一张目标表，禁止 JOIN、UNION、多表 FROM 或 FROM 子查询：" + sheetName);
        }
        int from = TableStepSql.keyword(query, "FROM");
        Matcher matcher = ASSERT_FROM_TABLE.matcher(query);
        if (from < 0 || !matcher.find(from) || matcher.start() != from) {
            throw new IllegalArgumentException("数据库断言必须有一个 FROM 目标表：" + sheetName);
        }
        String projection = query.substring(0, from).replaceAll("'(?:''|[^'])*+'", "");
        if (projection.matches("(?is).*\\(\\s*SELECT\\b.*")) throw new IllegalArgumentException("数据库断言子查询只能填写在 WHERE 条件中：" + sheetName);
        String table = matcher.group(1);
        int fromEnd = matcher.end();
        String tail = query.substring(fromEnd);
        int clause = firstClauseIndex(tail.toUpperCase());
        if (tail.substring(0, clause < 0 ? tail.length() : clause).indexOf(',') >= 0) {
            throw new IllegalArgumentException("数据库断言只能查询一张表，FROM 后不能有多个表：" + sheetName);
        }
        return sqlIdentifier(table, "断言表名", sheetName);
    }

    private static int firstClauseIndex(String value) {
        Matcher matcher = ASSERT_CLAUSE.matcher(value);
        return matcher.find() ? matcher.start() : -1;
    }

    private List<String> tableHeaders(Sheet sheet, int rowIndex, String sheetName) {
        List<String> headers = tableHeadersOrEmpty(sheet, rowIndex, sheetName);
        if (headers.isEmpty()) {
            throw new IllegalArgumentException("步骤 Sheet 缺少表头：" + rowLocation(sheet, rowIndex));
        }
        return headers;
    }

    private List<String> tableHeadersOrEmpty(Sheet sheet, int rowIndex, String sheetName) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            return Collections.emptyList();
        }
        List<String> headers = new ArrayList<String>();
        boolean foundBlank = false;
        for (int index = 0; index < row.getLastCellNum(); index++) {
            String header = value(row.getCell(index));
            if (blank(header)) {
                foundBlank = true;
                continue;
            }
            if (foundBlank) {
                throw new IllegalArgumentException("表头必须从第一列连续填写：" + cellLocation(sheet, rowIndex, index));
            }
            if (headers.contains(header)) {
                throw new IllegalArgumentException("表头字段重复“" + header + "”：" + cellLocation(sheet, rowIndex, index));
            }
            headers.add(header);
        }
        return headers;
    }

    private List<Map<String, Object>> tableRows(Sheet sheet, int startRow, int endRow, List<String> fields) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int rowIndex = startRow; rowIndex <= endRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (blankRow(row)) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            for (int index = 0; index < fields.size(); index++) {
                Object fieldValue = typedValue(row.getCell(index));
                values.put(fields.get(index), NULL_CELL.equals(fieldValue) ? null : fieldValue);
            }
            rows.add(values);
        }
        return rows;
    }

    private Cell requiredJsonDataCell(Sheet sheet, int startRow, String label) {
        Cell result = null;
        int endRow = sectionExists(sheet, RESPONSE_VARIABLE_SECTION)
                ? requiredSection(sheet, RESPONSE_VARIABLE_SECTION) - 1 : sheet.getLastRowNum();
        for (int rowIndex = startRow; rowIndex <= endRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (blankRow(row)) continue;
            if (result != null) {
                throw new IllegalArgumentException(label + "的 #JSON# 列只能填写一条 JSON：" + rowLocation(sheet, rowIndex));
            }
            for (int columnIndex = 1; row != null && columnIndex < row.getLastCellNum(); columnIndex++) {
                if (!blank(value(row.getCell(columnIndex)))) {
                    throw new IllegalArgumentException(label + "的 #JSON# 列不能填写其他列：" + cellLocation(sheet, rowIndex, columnIndex));
                }
            }
            Cell cell = row.getCell(0);
            if (blank(value(cell))) {
                throw new IllegalArgumentException(label + "的 #JSON# 内容不能为空：" + cellLocation(sheet, rowIndex, 0));
            }
            result = cell;
        }
        if (result == null) {
            throw new IllegalArgumentException(label + "的 #JSON# 内容不能为空：" + rowLocation(sheet, startRow));
        }
        return result;
    }

    private List<Map<String, String>> sqlValueRows(Sheet sheet, int startRow, int endRow, List<String> fields) {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        for (int rowIndex = startRow; rowIndex <= endRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (blankRow(row)) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<String, String>();
            for (int index = 0; index < fields.size(); index++) {
                String sqlValue = sqlValue(row.getCell(index));
                validateSqlValue(sqlValue, fields.get(index), cellLocation(sheet, rowIndex, index));
                values.put(fields.get(index), sqlValue);
            }
            rows.add(values);
        }
        return rows;
    }

    private Map<String, Object> singleRow(Sheet sheet, int rowIndex, List<String> fields) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        if (fields.isEmpty()) {
            return values;
        }
        Row row = requiredRow(sheet, rowIndex, "已填写接口断言表头，但期望值行为空");
        for (int index = 0; index < fields.size(); index++) {
            values.put(fields.get(index), typedValue(row.getCell(index)));
        }
        return values;
    }

    private Map<String, Object> specifiedRow(Sheet sheet, int rowIndex, List<String> fields) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        if (fields.isEmpty()) {
            return values;
        }
        Row row = requiredRow(sheet, rowIndex, "已填写字段表头，但数据行为空");
        for (int index = 0; index < fields.size(); index++) {
            String cellValue = value(row.getCell(index));
            if (!blank(cellValue)) {
                values.put(fields.get(index), typedValue(row.getCell(index)));
            }
        }
        return values;
    }

    private Map<String, Object> sectionFields(Sheet sheet, String section) {
        int rowIndex = requiredSection(sheet, section);
        List<String> fields = tableHeadersOrEmpty(sheet, rowIndex + 1, sheet.getSheetName());
        rejectPlaceholders(fields, sheet, rowIndex + 1);
        return specifiedRow(sheet, rowIndex + 2, fields);
    }

    private String sectionJsonOrFields(Sheet sheet, String section) {
        int rowIndex = requiredSection(sheet, section);
        String source = value(cell(sheet, rowIndex + 1, 0)).trim();
        if (JSON_DATA_FIELD.equals(source)) {
            return objectJson(sheet, cell(sheet, rowIndex + 2, 0), section);
        }
        if (source.startsWith("{") || source.startsWith("[")) {
            return objectJson(sheet, cell(sheet, rowIndex + 1, 0), section);
        }
        return writeJson(sectionFields(sheet, section));
    }

    private String objectJson(Sheet sheet, Cell sourceCell, String label) {
        try {
            JsonNode root = preciseObjectMapper.readTree(escapeRegexZones(value(sourceCell)));
            if (root == null || !root.isObject()) {
                throw datasetError(sheet, sourceCell, label + " JSON 根节点必须是对象");
            }
            return root.toString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw datasetError(sheet, sourceCell, label + " JSON 格式不正确：" + e.getMessage());
        }
    }

    private int requiredSection(Sheet sheet, String section) {
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (section.equals(value(row == null ? null : row.getCell(0)))) {
                return rowIndex;
            }
        }
        throw new IllegalArgumentException("步骤 Sheet 缺少“" + section + "”区域：Sheet“" + sheet.getSheetName() + "”");
    }

    private boolean sectionExists(Sheet sheet, String section) {
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (section.equals(value(row == null ? null : row.getCell(0)))) {
                return true;
            }
        }
        return false;
    }

    private void rejectPlaceholders(List<String> fields, Sheet sheet, int rowIndex) {
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index);
            if (PLACEHOLDER_FIELD.equals(field) || PLACEHOLDER_PARAMETER.equals(field) || PLACEHOLDER_RESPONSE.equals(field)) {
                throw new IllegalArgumentException("请替换模板中的字段表头：" + cellLocation(sheet, rowIndex, index));
            }
        }
    }

    private String compareMode(String value, String location) {
        String result = blank(value) ? "EXACT_ROWS" : value.trim().toUpperCase();
        if (!"EXACT_ROWS".equals(result) && !"ROW_COUNT".equals(result) && !"NOT_EXISTS".equals(result)) {
            throw new IllegalArgumentException("比较方式只支持 EXACT_ROWS、ROW_COUNT、NOT_EXISTS：" + location);
        }
        return result;
    }

    private long nonNegativeInteger(String value, String field, String location) {
        try {
            long result = Long.parseLong(value);
            if (result < 0) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + "必须是非负整数：" + location);
        }
    }

    private List<PlatformModels.CaseAction> actionsOf(PlatformModels.TestCase value) {
        List<PlatformModels.CaseAction> actions = new ArrayList<PlatformModels.CaseAction>(value.actions);
        Collections.sort(actions, Comparator.comparingInt(action -> action.sortNo == null ? 0 : action.sortNo));
        return actions;
    }

    private Map<String, JsonNode> objectFields(String source, String label) {
        JsonNode root = json(source, label);
        if (root == null || root.isNull()) {
            return Collections.emptyMap();
        }
        if (!root.isObject()) {
            throw new IllegalStateException(label + "必须是对象");
        }
        Map<String, JsonNode> fields = new LinkedHashMap<String, JsonNode>();
        Iterator<Map.Entry<String, JsonNode>> iterator = root.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            fields.put(entry.getKey(), entry.getValue());
        }
        return fields;
    }

    private List<Map<String, JsonNode>> jsonRows(String source, String label) {
        JsonNode values = json(source, label);
        if (values == null || values.isNull()) {
            return Collections.emptyList();
        }
        if (!values.isArray()) {
            throw new IllegalStateException(label + "必须是数组");
        }
        List<Map<String, JsonNode>> rows = new ArrayList<Map<String, JsonNode>>();
        for (JsonNode item : values) {
            if (!item.isObject()) {
                throw new IllegalStateException(label + "包含非对象记录");
            }
            Map<String, JsonNode> row = new LinkedHashMap<String, JsonNode>();
            Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                row.put(entry.getKey(), entry.getValue());
            }
            rows.add(row);
        }
        return rows;
    }

    private JsonNode json(String source, String label) {
        if (blank(source)) {
            return null;
        }
        try {
            return objectMapper.readTree(source);
        } catch (Exception e) {
            throw new IllegalStateException(label + "格式不正确：" + e.getMessage(), e);
        }
    }

    private List<String> fields(List<? extends Map<String, ?>> rows) {
        Set<String> fields = new LinkedHashSet<String>();
        for (Map<String, ?> row : rows) {
            fields.addAll(row.keySet());
        }
        return new ArrayList<String>(fields);
    }

    private Object[] values(Map<String, JsonNode> values, List<String> fields) {
        Object[] result = new Object[fields.size()];
        for (int index = 0; index < fields.size(); index++) {
            result[index] = jsonValue(values.get(fields.get(index)));
        }
        return result;
    }

    private void writeJsonRow(Sheet sheet, int rowIndex, Map<String, JsonNode> values, List<String> fields) {
        Row row = writeRow(sheet, rowIndex, values(values, fields));
        for (int index = 0; index < fields.size(); index++) {
            JsonNode value = values.get(fields.get(index));
            if (value != null && value.isNumber()) {
                CellUtil.setCellStyleProperty(row.getCell(index), CellUtil.DATA_FORMAT,
                        sheet.getWorkbook().createDataFormat().getFormat(value.isIntegralNumber() ? "0" : "0.################"));
            }
        }
    }

    private void writeJsonRows(Sheet sheet, int startRow, List<Map<String, JsonNode>> rows, List<String> fields) {
        for (int index = 0; index < rows.size(); index++) {
            writeJsonRow(sheet, startRow + index, rows.get(index), fields);
        }
    }

    private void writeSqlCell(Sheet sheet, int rowIndex, int columnIndex) {
        Row row = sheet.getRow(rowIndex);
        Cell cell = row.getCell(columnIndex);
        CellUtil.setCellStyleProperty(cell, CellUtil.WRAP_TEXT, true);
        String sql = value(cell);
        int lines = Math.max(1, sql.split("\\r?\\n").length + sql.length() / 72);
        row.setHeightInPoints(Math.min(240, Math.max(48, lines * 16)));
        sheet.setColumnWidth(columnIndex, 72 * 256);
    }

    private boolean requiresJsonEncoding(JsonNode value) {
        if (value == null) return false;
        if (value.isNull()) return true;
        if (value.isNumber()) {
            if (value.isIntegralNumber() && !value.canConvertToLong()) return true;
            double number = value.doubleValue();
            return Double.isInfinite(number) || BigDecimal.valueOf(number).compareTo(value.decimalValue()) != 0;
        }
        if (value.isTextual()) {
            String text = value.textValue();
            return text.trim().isEmpty() || text.startsWith("#") || (text.startsWith("{") && !text.startsWith("{{")) || text.startsWith("[") || !text.equals(text.trim());
        }
        if (value.isContainerNode()) {
            if (value.size() == 0) return true;
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                if (!fields.next().getKey().matches("[A-Za-z_][A-Za-z0-9_]*")) return true;
            }
            for (JsonNode child : value) if (requiresJsonEncoding(child)) return true;
        }
        return false;
    }

    private Object jsonValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return NULL_CELL;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isFloatingPointNumber()) {
            return value.decimalValue();
        }
        if (value.isContainerNode()) {
            return value.toString();
        }
        return value.asText();
    }

    private Object typedValue(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.BOOLEAN) {
            return cell.getBooleanCellValue();
        }
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(DateUtil.getJavaDate(cell.getNumericCellValue()));
            }
            BigDecimal number = BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
            return number.scale() <= 0 ? number.longValue() : number;
        }
        return rawValue(cell);
    }

    private void validateSqlValue(String value, String field, String location) {
        String suffix = location == null ? "：" + field : "，字段“" + field + "”：" + location;
        int parentheses = 0;
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            char next = index + 1 < value.length() ? value.charAt(index + 1) : 0;
            if (current == '\'') {
                if (quoted && next == '\'') {
                    index++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }
            if (quoted) {
                continue;
            }
            if (current == ';' || (current == '-' && next == '-') || (current == '/' && next == '*')) {
                throw new IllegalArgumentException("插入字段 SQL 值不能包含分号或注释" + suffix);
            }
            if (current == '(') {
                parentheses++;
            } else if (current == ')') {
                parentheses--;
                if (parentheses < 0) {
                    throw new IllegalArgumentException("插入字段 SQL 值括号不匹配" + suffix);
                }
            } else if (current == ',' && parentheses == 0) {
                throw new IllegalArgumentException("插入字段 SQL 值不能包含顶层逗号" + suffix);
            }
        }
        if (quoted || parentheses != 0) {
            throw new IllegalArgumentException("插入字段 SQL 值引号或括号不匹配" + suffix);
        }
    }

    private String sqlLiteral(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private String sqlLiteral(JsonNode value) {
        if (value == null || value.isNull()) {
            return "NULL";
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return "'" + value.asText().replace("'", "''") + "'";
    }

    private static String sqlIdentifier(String value, String label, String location) {
        String result = required(value, "数据步骤缺少" + label + "：" + location);
        if (!SQL_IDENTIFIER.matcher(result).matches()) {
            throw new IllegalArgumentException("数据步骤" + label + "格式不正确“" + result + "”：" + location);
        }
        return result;
    }

    private Row requiredRow(Sheet sheet, int rowIndex, String reason) {
        Row row = sheet.getRow(rowIndex);
        if (blankRow(row)) {
            throw new IllegalArgumentException(reason + "：" + rowLocation(sheet, rowIndex));
        }
        return row;
    }

    private String requiredSetting(Sheet sheet, String key) {
        return requiredSectionSetting(sheet, 2, key);
    }

    private String requiredSectionSetting(Sheet sheet, int headerRowIndex, String key) {
        int columnIndex = headerColumn(sheet, headerRowIndex, key);
        if (columnIndex < 0) {
            throw new IllegalArgumentException("步骤设置缺少字段“" + key + "”：" + rowLocation(sheet, headerRowIndex));
        }
        Row valueRow = sheet.getRow(headerRowIndex + 1);
        return required(value(valueRow == null ? null : valueRow.getCell(columnIndex)),
                "步骤设置“" + key + "”不能为空：" + cellLocation(sheet, headerRowIndex + 1, columnIndex));
    }

    private String settingLocation(Sheet sheet, String key) {
        return sectionSettingLocation(sheet, 2, key);
    }

    private String sectionSettingLocation(Sheet sheet, int headerRowIndex, String key) {
        int columnIndex = headerColumn(sheet, headerRowIndex, key);
        return columnIndex < 0 ? rowLocation(sheet, headerRowIndex) : cellLocation(sheet, headerRowIndex + 1, columnIndex);
    }

    private Cell cell(Sheet sheet, int rowIndex, int columnIndex) {
        Row row = sheet.getRow(rowIndex);
        return row == null ? null : row.getCell(columnIndex);
    }

    private int headerColumn(Sheet sheet, int rowIndex, String key) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) return -1;
        for (int index = 0; index < row.getLastCellNum(); index++) {
            if (key.equals(value(row.getCell(index)))) return index;
        }
        return -1;
    }

    private String requiredInfo(Sheet sheet, String key) {
        int rowIndex = infoRow(sheet, key);
        if (rowIndex < 0) {
            throw new IllegalArgumentException("案例信息缺少字段“" + key + "”：Sheet“" + INFO_SHEET + "”");
        }
        return required(value(sheet.getRow(rowIndex).getCell(1)), "案例信息“" + key + "”不能为空：" + cellLocation(sheet, rowIndex, 1));
    }

    private String infoValueLocation(Sheet sheet, String key) {
        int rowIndex = infoRow(sheet, key);
        return rowIndex < 0 ? "Sheet“" + INFO_SHEET + "”" : cellLocation(sheet, rowIndex, 1);
    }

    private int infoRow(Sheet sheet, String key) {
        if (sheet == null) return -1;
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null && key.equals(value(row.getCell(0)))) return rowIndex;
        }
        return -1;
    }

    private String rowLocation(Sheet sheet, int rowIndex) {
        return "Sheet“" + sheet.getSheetName() + "”第" + (rowIndex + 1) + "行";
    }

    private String cellLocation(Sheet sheet, int rowIndex, int columnIndex) {
        return rowLocation(sheet, rowIndex) + "（" + org.apache.poi.ss.util.CellReference.convertNumToColString(columnIndex) + (rowIndex + 1) + "）";
    }

    private boolean blankRow(Row row) {
        if (row == null) {
            return true;
        }
        for (int index = 0; index < row.getLastCellNum(); index++) {
            if (!rawValue(row.getCell(index)).isEmpty()
                    || row.getCell(index) != null && row.getCell(index).getCellType() == CellType.STRING
                    && row.getCell(index).getCellStyle().getQuotePrefixed()) {
                return false;
            }
        }
        return true;
    }

    private String value(Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private String rawValue(Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    private String sqlValue(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "NULL";
        }
        String result;
        if (cell.getCellType() == CellType.NUMERIC) {
            result = NumberToTextConverter.toText(cell.getNumericCellValue());
        } else if (cell.getCellType() == CellType.STRING) {
            result = cell.getStringCellValue();
        } else {
            result = formatter.formatCellValue(cell);
        }
        if (cell.getCellType() == CellType.STRING && cell.getCellStyle().getQuotePrefixed()) {
            return sqlLiteral(result);
        }
        if (result.isEmpty()) {
            return "NULL";
        }
        return cell.getCellType() != CellType.STRING || rawSqlValue(result) ? result : sqlLiteral(result);
    }

    private boolean rawSqlValue(String value) {
        String text = value.trim();
        return text.matches("(?is)'(?:''|[^'])*+'|[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:E[+-]?\\d+)?"
                + "|NULL|TRUE|FALSE|DEFAULT|CURRENT_DATE|CURRENT_TIME|CURRENT_TIMESTAMP|LOCALTIME|LOCALTIMESTAMP"
                + "|:[A-Z_][A-Z0-9_]*|\\{\\{\\s*[A-Z0-9_.-]+\\s*}}"
                + "|[A-Z_][A-Z0-9_$.]*\\s*\\(.*\\)|\\(.*\\)|CASE\\s+.*\\s+END"
                + "|(?:DATE|TIME|TIMESTAMP|INTERVAL)\\s+'.*'|\"(?:\"\"|[^\"])+\"")
                || text.matches("(?s).*(?:[+*/%]|\\|\\||::|[A-Za-z0-9_)]\\s*-\\s*[A-Za-z0-9_:(]).*");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("工作簿数据格式化失败：" + e.getMessage(), e);
        }
    }

    private static String required(String value, String message) {
        if (blank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private boolean parseEnabled(String value) {
        return blank(value) || "是".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private boolean yesNo(String value, String field, String location) {
        if ("是".equals(value)) return true;
        if ("否".equals(value)) return false;
        throw new IllegalArgumentException(field + "只能填写“是”或“否”：" + location);
    }

    private void validateCaseActions(List<PlatformModels.CaseAction> actions) {
        SnapshotRules.validate(actions);
        int lastBusinessAction = -1;
        for (int index = 0; index < actions.size(); index++) {
            PlatformModels.CaseAction action = actions.get(index);
            if (!java.util.Arrays.asList(INSERT, UPDATE, CLEANUP, BACKUP, RESTORE, HTTP, UPLOAD, ASSERT).contains(action.actionType)) {
                throw new IllegalArgumentException("不支持的步骤类型：" + action.actionType);
            }
            if (UPLOAD.equals(action.actionType)) HttpCaseRules.validateUpload(action);
            validateTableAction(action);
            HttpCaseRules.validate(action);
            if (ASSERT.equals(action.actionType) && "ROW_COUNT".equals(action.compareMode)
                    && (action.expectedRowCount == null || action.expectedRowCount < 0)) {
                throw new IllegalArgumentException("期望行数必须是非负整数：" + action.name);
            }
            validateExpectedOutcome(action);
            if (HTTP.equals(action.actionType) || UPLOAD.equals(action.actionType)) {
                lastBusinessAction = index;
            }
            if (!Boolean.TRUE.equals(action.coreAssertion)) continue;
            if (ASSERT.equals(action.actionType)) {
                continue;
            } else if (HTTP.equals(action.actionType)) {
                if (!hasTemplate(action.assertionsJson)) {
                    throw new IllegalArgumentException("HTTP 步骤配置为核心核对，但未配置接口断言：" + action.name);
                }
            } else if (UPLOAD.equals(action.actionType)) {
                if (action.upload == null || !hasTemplate(action.upload.assertionsJson)) {
                    throw new IllegalArgumentException("上传文件步骤配置为核心核对，但未配置接口断言：" + action.name);
                }
            } else {
                throw new IllegalArgumentException("核心核对只能配置在 HTTP、上传文件或数据库断言步骤：" + action.name);
            }
        }
        boolean cleanupStarted = false;
        for (int index = lastBusinessAction + 1; index < actions.size(); index++) {
            String actionType = actions.get(index).actionType;
            if (CLEANUP.equals(actionType)) {
                cleanupStarted = true;
            } else if (cleanupStarted && ASSERT.equals(actionType)) {
                throw new IllegalArgumentException("最终清理步骤之后不允许配置数据库断言");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean hasTemplate(String value) {
        if (blank(value) || "{}".equals(value.trim())) return false;
        try {
            JsonNode root = preciseObjectMapper.readTree(value);
            if (root != null && root.isObject() && root.path("version").asInt() == INTERFACE_ASSERTION_VERSION && root.has("expected")) {
                JsonNode expected = root.get("expected");
                return expected != null && (!expected.isObject() || expected.size() > 0);
            }
        } catch (Exception ignored) {
            return true;
        }
        return true;
    }

    private void validateExpectedOutcome(PlatformModels.CaseAction action) {
        boolean interfaceAction = UPLOAD.equals(action.actionType);
        if (!interfaceAction && !blank(action.expectedResult)) {
            throw new IllegalArgumentException("只有上传文件步骤可以配置预期结果：" + action.name);
        }
        if (!interfaceAction) {
            return;
        }
        String result = blank(action.expectedResult) ? "SUCCESS" : action.expectedResult.toUpperCase();
        if (!"SUCCESS".equals(result) && !"FAILURE".equals(result)) {
            throw new IllegalArgumentException("期望结果只能填写“成功”或“失败”：" + action.name);
        }
        action.expectedResult = result;
    }

    private String label(String type) {
        return BACKUP.equals(type) ? "备份数据表" : RESTORE.equals(type) ? "恢复数据表" : INSERT.equals(type) ? "插入数据" : UPDATE.equals(type) ? "更新数据" : UPLOAD.equals(type) ? "上传文件" : HTTP.equals(type) ? "HTTP调用" : ASSERT.equals(type) ? "断言" : "清理数据";
    }

    private void writeSection(Sheet sheet, int rowIndex, String title) {
        writeHeader(sheet, rowIndex, new Object[] {title});
    }

    private void writeHeader(Sheet sheet, int rowIndex, Object[] values) {
        Row row = writeRow(sheet, rowIndex, values);
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put(CellUtil.FILL_FOREGROUND_COLOR, IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        properties.put(CellUtil.FILL_PATTERN, FillPatternType.SOLID_FOREGROUND);
        properties.put(CellUtil.WRAP_TEXT, true);
        for (Cell cell : row) {
            CellUtil.setCellStyleProperties(cell, properties);
        }
    }

    private Row writeRow(Sheet sheet, int rowIndex, Object[] values) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        for (int index = 0; index < values.length; index++) {
            Cell cell = row.getCell(index);
            if (cell == null) {
                cell = row.createCell(index);
            }
            writeCell(cell, values[index]);
        }
        return row;
    }

    private void writeCell(Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof Boolean) {
            cell.setCellValue((Boolean) value);
        } else if (value instanceof Number) {
            cell.setCellValue(((Number) value).doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private void writeMergedWrappedNote(Sheet sheet, int rowIndex, int firstColumn, int lastColumn) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put(CellUtil.WRAP_TEXT, true);
        properties.put(CellUtil.VERTICAL_ALIGNMENT, VerticalAlignment.TOP);
        CellUtil.setCellStyleProperties(sheet.getRow(rowIndex).getCell(firstColumn), properties);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, firstColumn, lastColumn));
        sheet.getRow(rowIndex).setHeightInPoints(54);
    }

    private void finishSheet(Sheet sheet, int columns) {
        sheet.createFreezePane(0, 3);
        for (int index = 0; index < columns; index++) {
            sheet.setColumnWidth(index, 22 * 256);
        }
    }

    private byte[] bytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }

    private static final class InterfaceSectionLayout {
        private final int nextRow;
        private final int width;

        private InterfaceSectionLayout(int nextRow, int width) {
            this.nextRow = nextRow;
            this.width = width;
        }
    }

    private static final class InterfaceRecord {
        private final List<String> fields = new ArrayList<String>();
        private final Map<String, JsonNode> values = new LinkedHashMap<String, JsonNode>();
    }

    private static final class InterfaceRegion {
        private final String id;
        private final List<String> fields = new ArrayList<String>();
        private final List<Map<String, JsonNode>> rows = new ArrayList<Map<String, JsonNode>>();

        private InterfaceRegion(String id) {
            this.id = id;
        }
    }
}
