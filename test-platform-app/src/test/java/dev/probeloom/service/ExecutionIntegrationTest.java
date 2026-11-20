package dev.probeloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.probeloom.config.PlatformDatabaseConfig;
import dev.probeloom.config.PlatformStorage;
import dev.probeloom.model.PlatformModels;
import dev.probeloom.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionIntegrationTest {
    @TempDir Path directory;

    @Configuration
    @Import({PlatformStorage.class, PlatformDatabaseConfig.class, CryptoService.class, PlatformRepository.class,
            ExecutionQueueRepository.class, ExecutionFinalizationRepository.class,
            ExecutionService.class, HttpProtocolAdapter.class})
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean PlatformStartupCheck platformStartupCheck(PlatformRepository repository, ExecutionQueueRepository queue) {
            return new PlatformStartupCheck(repository, queue, "");
        }
    }

    @Test
    void freezesConnectionAndRestoresSqlDataAfterHttpFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            requests.incrementAndGet();
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            Map<String, Object> properties = new HashMap<String, Object>();
            properties.put("platform.storage.path", directory.resolve("platform.db").toString());
            properties.put("platform.storage.mode", "sqlite");
            properties.put("platform.supervisor.only", "true");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("integration", properties));
            context.register(TestConfiguration.class);
            context.refresh();
            PlatformRepository repository = context.getBean(PlatformRepository.class);
            ExecutionService executor = context.getBean(ExecutionService.class);
            ExecutionQueueRepository queue = context.getBean(ExecutionQueueRepository.class);
            JdbcTemplate platform = context.getBean(JdbcTemplate.class);
            PlatformModels.Environment environment = new PlatformModels.Environment();
            environment.name = "local"; environment.refapp = "default"; environment.defaultEnvironment = true;
            PlatformModels.ProtocolConnection connection = new PlatformModels.ProtocolConnection();
            connection.key = "demo"; connection.protocol = "HTTP";
            connection.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            environment.connections.add(connection);
            environment = repository.saveEnvironment(environment);
            PlatformModels.TestCase httpCase = save(repository, "frozen connection", http(1));
            long first = executor.queueCase(httpCase.id, null, environment.id, null, "MANUAL");
            environment.connections.get(0).baseUrl = "http://127.0.0.1:1";
            repository.saveEnvironment(environment);
            execute(executor, queue, first);
            assertEquals("PASSED", repository.loadExecution(first).status);
            assertEquals(1, requests.get());

            SimpleDriverDataSource businessSource = new SimpleDriverDataSource(new org.sqlite.JDBC(),
                    "jdbc:sqlite:" + directory.resolve("business.db"));
            JdbcTemplate business = new JdbcTemplate(businessSource);
            business.execute("CREATE TABLE demo_records(id INTEGER PRIMARY KEY, value TEXT)");
            business.update("INSERT INTO demo_records VALUES (1, 'before')");
            PlatformModels.TestDataSource source = new PlatformModels.TestDataSource();
            source.name = "demo"; source.resourceKey = "demo"; source.databaseType = "SQLITE";
            source.jdbcUrl = businessSource.getUrl(); source.username = ""; source.accessMode = "TEST_WRITE";
            repository.saveDataSource(source);
            PlatformModels.CaseAction backup = action("BACKUP", 1);
            backup.datasourceKey = "demo"; backup.datasetName = "demo_records";
            backup.snapshotName = "baseline"; backup.clearAfterBackup = false;
            PlatformModels.CaseAction insert = action("INSERT", 2);
            insert.datasourceKey = "demo"; insert.datasetName = "demo_records";
            insert.prepareSql = "INSERT INTO demo_records(id,value) VALUES (2,'during')";
            PlatformModels.CaseAction assertion = rowCount(3, 2L);
            PlatformModels.CaseAction cleanup = action("CLEANUP", 5);
            cleanup.datasourceKey = "demo"; cleanup.datasetName = "demo_records";
            cleanup.cleanupSql = "DELETE FROM demo_records WHERE id=2";
            PlatformModels.TestCase failureCase = save(repository, "restore on failure", backup, insert, assertion, http(4), cleanup);
            long second = executor.queueCase(failureCase.id, null, environment.id, null, "MANUAL");
            execute(executor, queue, second);
            assertEquals("FAILED", repository.loadExecution(second).status);
            assertEquals("PASSED", platform.queryForObject("SELECT status FROM step_execution WHERE execution_id=? AND sort_no=3", String.class, second));
            assertEquals("PASSED", platform.queryForObject("SELECT status FROM step_execution WHERE execution_id=? AND sort_no=5", String.class, second));
            assertEquals(1, business.queryForObject("SELECT COUNT(*) FROM demo_records", Integer.class));
            assertEquals("before", business.queryForObject("SELECT value FROM demo_records WHERE id=1", String.class));
            assertEquals(0, platform.queryForObject("SELECT COUNT(*) FROM table_backup WHERE execution_id=? AND status<>'DROPPED'", Integer.class, second));
            assertEquals(0, platform.queryForObject("SELECT COUNT(*) FROM execution_resource_owner", Integer.class));

            PlatformModels.TestCase sqlCase = save(repository, "sql without environment", rowCount(1, 1L));
            long third = executor.queueCase(sqlCase.id, null, null, null, "MANUAL");
            execute(executor, queue, third);
            assertEquals("PASSED", repository.loadExecution(third).status);
            assertNull(platform.queryForObject("SELECT environment_id FROM test_execution WHERE id=?", Long.class, third));
            PlatformModels.CaseAction unselectedHttp = http(2);
            unselectedHttp.applicationName = "unconfigured";
            PlatformModels.TestCase mixed = save(repository, "selected sql without environment", rowCount(1, 1L), unselectedHttp);
            assertThrows(IllegalArgumentException.class, () -> executor.queueCase(mixed.id, null, null, null, "MANUAL"));
            Long selectedSql = repository.loadCase(mixed.id).actions.get(0).id;
            long fourth = executor.queueCase(mixed.id, null, null, selectedSql, "MANUAL");
            execute(executor, queue, fourth);
            assertEquals("PASSED", repository.loadExecution(fourth).status);
            assertNull(platform.queryForObject("SELECT environment_id FROM test_execution WHERE id=?", Long.class, fourth));
            assertEquals("NOT_SELECTED", platform.queryForObject("SELECT status FROM step_execution WHERE execution_id=? AND sort_no=2", String.class, fourth));
            assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }

    private static void execute(ExecutionService executor, ExecutionQueueRepository queue, long id) {
        PlatformModels.ClaimedExecution claim = queue.claimNext();
        assertNotNull(claim); assertEquals(id, claim.id);
        ReflectionTestUtils.invokeMethod(executor, "executeCase", claim);
    }
    private static PlatformModels.TestCase save(PlatformRepository repository, String name, PlatformModels.CaseAction... actions) {
        PlatformModels.TestCase value = new PlatformModels.TestCase();
        value.name = name; value.description = "isolated integration example";
        value.enabled = true; value.replaceActions = true; value.actions.addAll(Arrays.asList(actions));
        return repository.saveCase(value);
    }
    private static PlatformModels.CaseAction action(String type, int order) {
        PlatformModels.CaseAction value = new PlatformModels.CaseAction();
        value.actionType = type; value.name = type + order; value.sortNo = order; return value;
    }
    private static PlatformModels.CaseAction http(int order) {
        PlatformModels.CaseAction value = action("HTTP", order);
        value.requestBody = "{\"connection\":\"demo\",\"path\":\"/echo\",\"method\":\"GET\",\"headers\":{},\"body\":\"\"}";
        value.assertionsJson = "{\"version\":2,\"expected\":{\"status\":200,\"contentType\":\"text/plain;charset=UTF-8\",\"body\":\"ok\"}}";
        return value;
    }
    private static PlatformModels.CaseAction rowCount(int order, long count) {
        PlatformModels.CaseAction value = action("ASSERT", order);
        value.datasourceKey = "demo"; value.datasetName = "demo_records";
        value.querySql = "SELECT id,value FROM demo_records"; value.compareMode = "ROW_COUNT";
        value.expectedRowCount = count; return value;
    }
}
