package dev.probeloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.probeloom.model.PlatformModels;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class WorkbookContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CaseWorkbookCodec codec = new CaseWorkbookCodec(mapper);

    @Test
    public void templateImportsAndRoundTripsWithConnectionCatalog() throws Exception {
        PlatformModels.Environment environment = new PlatformModels.Environment();
        environment.name = "Local";
        PlatformModels.ProtocolConnection connection = new PlatformModels.ProtocolConnection();
        connection.key = "demo";
        connection.protocol = "HTTP";
        connection.baseUrl = "http://127.0.0.1:18091";
        environment.connections.add(connection);
        byte[] template = codec.template(Collections.emptyList(), Collections.singletonList(environment));
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            assertNotNull(workbook.getSheet("填写说明"));
            assertEquals("demo", workbook.getSheet("基础配置目录").getRow(1).getCell(1).getStringCellValue());
            assertFalse(workbook.getSheet("案例信息").getDataValidations().isEmpty());
        }
        PlatformModels.TestCase testCase = codec.parse(new ByteArrayInputStream(template));
        assertEquals(8, testCase.actions.size());
        assertEquals("demo_records", testCase.actions.get(1).datasetName);
        assertEquals("HTTP", testCase.actions.get(3).actionType);
        assertEquals("demo", testCase.actions.get(4).upload.connection);
        PlatformModels.TestCase restored = codec.parse(new ByteArrayInputStream(codec.write(testCase)));
        assertEquals(testCase.actions.size(), restored.actions.size());
        assertEquals(testCase.actions.get(3).requestBody, restored.actions.get(3).requestBody);
        assertEquals("demo", restored.actions.get(4).upload.connection);
        assertEquals("aGVsbG8=", restored.actions.get(4).upload.contentBase64);
    }

    @Test
    public void getRequestKeepsMethodAndEncodedQuery() throws Exception {
        PlatformModels.CaseAction action = http();
        action.applicationName = "inventory";
        action.requestBody = "{\"connection\":\"demo\",\"method\":\"GET\",\"path\":\"/items?name=a%20b\",\"headers\":{},\"body\":\"\"}";
        PlatformModels.TestCase parsed = codec.parse(new ByteArrayInputStream(codec.write(testCase(action))));
        assertEquals("inventory", parsed.actions.get(0).applicationName);
        assertEquals("GET", mapper.readTree(parsed.actions.get(0).requestBody).get("method").asText());
        assertEquals("/items?name=a%20b", mapper.readTree(parsed.actions.get(0).requestBody).get("path").asText());
    }

    @Test
    public void databaseStepCannotWriteAnotherTableOrUpdateWithoutWhere() {
        PlatformModels.CaseAction update = new PlatformModels.CaseAction();
        update.name = "Update record";
        update.actionType = "UPDATE";
        update.datasourceKey = "default";
        update.datasetName = "demo_records";
        update.prepareSql = "UPDATE other_records SET amount=2 WHERE record_id='one'";
        assertThrows(IllegalArgumentException.class, () -> codec.validate(testCase(update)));
        update.prepareSql = "UPDATE demo_records SET amount=2";
        assertThrows(IllegalArgumentException.class, () -> codec.validate(testCase(update)));
    }

    @Test
    public void cleanupRequiresConditionAndAssertionRejectsJoin() {
        PlatformModels.CaseAction cleanup = new PlatformModels.CaseAction();
        cleanup.name = "Cleanup record";
        cleanup.actionType = "CLEANUP";
        cleanup.datasourceKey = "default";
        cleanup.datasetName = "demo_records";
        cleanup.cleanupSql = "DELETE FROM demo_records";
        assertThrows(IllegalArgumentException.class, () -> codec.validate(testCase(cleanup)));
        PlatformModels.CaseAction assertion = new PlatformModels.CaseAction();
        assertion.name = "Check record";
        assertion.actionType = "ASSERT";
        assertion.datasetName = "demo_records";
        assertion.querySql = "SELECT a.record_id FROM demo_records a JOIN other_records b ON a.record_id=b.record_id";
        assertThrows(IllegalArgumentException.class, () -> codec.validate(testCase(assertion)));
    }

    @Test
    public void protocolAndUploadPathFollowExplicitContract() {
        PlatformModels.ProtocolConnection connection = new PlatformModels.ProtocolConnection();
        connection.key = "demo";
        connection.protocol = "HTTP";
        connection.baseUrl = "http://127.0.0.1:18091";
        HttpCaseRules.validateConnections(Collections.singletonList(connection));
        connection.protocol = "UNKNOWN";
        assertThrows(IllegalArgumentException.class, () -> HttpCaseRules.validateConnections(Collections.singletonList(connection)));
        PlatformModels.CaseAction upload = new PlatformModels.CaseAction();
        upload.upload = new PlatformModels.CaseUpload();
        upload.upload.connection = "demo";
        upload.upload.path = "//another-host/upload";
        assertThrows(IllegalArgumentException.class, () -> HttpCaseRules.validateUpload(upload));
    }

    private PlatformModels.CaseAction http() {
        PlatformModels.CaseAction action = new PlatformModels.CaseAction();
        action.actionType = "HTTP";
        action.name = "Call local service";
        action.requestBody = "{\"connection\":\"demo\",\"method\":\"POST\",\"path\":\"/echo\",\"headers\":{},\"body\":\"hello\"}";
        action.assertionsJson = "{\"version\":2,\"expected\":{\"status\":200,\"contentType\":\"text/plain; charset=UTF-8\",\"body\":\"hello\"}}";
        return action;
    }

    private PlatformModels.TestCase testCase(PlatformModels.CaseAction... actions) {
        PlatformModels.TestCase testCase = new PlatformModels.TestCase();
        testCase.name = "Local integration";
        testCase.description = "Verify local service response and isolate database changes.";
        testCase.enabled = true;
        testCase.actions.addAll(Arrays.asList(actions));
        return testCase;
    }
}
