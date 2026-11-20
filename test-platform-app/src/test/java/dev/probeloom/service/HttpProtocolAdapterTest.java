package dev.probeloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.probeloom.model.PlatformModels;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HttpProtocolAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sendsExactTextWithFrozenConnectionHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> token = new AtomicReference<String>();
        AtomicReference<byte[]> sent = new AtomicReference<byte[]>();
        byte[] payload = "  alpha|中文||  ".getBytes(StandardCharsets.UTF_8);
        server.createContext("/echo", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Token"));
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = exchange.getRequestBody().read(buffer)) != -1) bytes.write(buffer, 0, count);
            sent.set(bytes.toByteArray());
            exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            PlatformModels.ProtocolConnection connection = connection(server);
            connection.headers.put("X-Token", "frozen-token");
            ObjectNode request = JSON.createObjectNode();
            request.put("path", "/echo").put("method", "POST").put("body", new String(payload, StandardCharsets.UTF_8));
            request.putObject("headers").put("Content-Type", "text/plain;charset=UTF-8").put("x-token", "case-token");
            ObjectNode response = new HttpProtocolAdapter().execute(connection, request);
            assertEquals(200, response.get("status").intValue());
            assertArrayEquals(payload, sent.get());
            assertEquals("frozen-token", token.get());
            assertArrayEquals(payload, Base64.getDecoder().decode(request.get("bodyBase64").textValue()));
            assertArrayEquals(payload, Base64.getDecoder().decode(response.get("bodyBase64").textValue()));
            assertEquals(new String(payload, StandardCharsets.UTF_8), response.get("body").textValue());
        } finally { server.stop(0); }
    }

    @Test
    void uploadsDirectlyToConfiguredHttpConnection() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> multipart = new AtomicReference<String>();
        server.createContext("/upload", exchange -> {
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = exchange.getRequestBody().read(buffer)) != -1) body.write(buffer, 0, count);
            multipart.set(new String(body.toByteArray(), StandardCharsets.UTF_8));
            byte[] response = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            PlatformModels.CaseUpload upload = new PlatformModels.CaseUpload();
            upload.connection = "demo";
            upload.path = "/upload";
            upload.fileField = "file";
            upload.fileName = "sample.txt";
            upload.contentType = "text/plain";
            PlatformModels.TestFileAsset asset = new PlatformModels.TestFileAsset();
            asset.content = "sample-content".getBytes(StandardCharsets.UTF_8);
            HttpUploadClient.UploadResult result = new HttpUploadClient().upload(connection(server).baseUrl,
                    upload, asset, Collections.emptyMap(), Collections.singletonMap("note", "example"));
            assertEquals("{\"accepted\":true}", result.responseBody);
            assertTrue(result.requestUrl.endsWith("/upload"));
            assertTrue(multipart.get().contains("filename=\"sample.txt\""));
            assertTrue(multipart.get().contains("sample-content"));
            assertTrue(multipart.get().contains("example"));
        } finally { server.stop(0); }
    }

    @Test
    void keepsRequestPathWithinConfiguredHost() {
        assertEquals("https://example.test/path?q=1", HttpProtocolAdapter.resolveUrl("https://example.test", "/path?q=1"));
        assertThrows(IllegalArgumentException.class, () -> HttpProtocolAdapter.resolveUrl("https://example.test", "//other.test/path"));
        assertThrows(IllegalArgumentException.class, () -> HttpProtocolAdapter.resolveUrl("https://example.test", "https://other.test/path"));
    }

    private static PlatformModels.ProtocolConnection connection(HttpServer server) {
        PlatformModels.ProtocolConnection connection = new PlatformModels.ProtocolConnection();
        connection.key = "demo";
        connection.protocol = "HTTP";
        connection.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return connection;
    }
}
