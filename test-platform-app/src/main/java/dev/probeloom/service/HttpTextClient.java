package dev.probeloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;

/** 按显式 URL、请求方式和请求头调用 HTTP，不添加 RPC 包装或跟随重定向。 */
final class HttpTextClient {
    ObjectNode execute(ObjectNode request) throws Exception {
        if (!request.path("url").isTextual() || !request.path("body").isTextual()
                || !request.path("headers").isObject()) {
            throw new IllegalArgumentException("HTTP 请求必须包含字符串 url、字符串 body 和对象 headers");
        }
        URI uri = new URI(request.get("url").textValue());
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("HTTP URL 必须是完整的 http/https 地址");
        }
        String contentType = null;
        Iterator<Map.Entry<String, JsonNode>> headers = request.get("headers").fields();
        while (headers.hasNext()) {
            Map.Entry<String, JsonNode> header = headers.next();
            if (!header.getValue().isTextual()) throw new IllegalArgumentException("HTTP 请求头必须是字符串");
            if ("Content-Type".equalsIgnoreCase(header.getKey())) contentType = header.getValue().textValue();
        }
        String method = request.has("method") ? request.get("method").textValue() : "POST";
        request.put("method", method);
        byte[] bytes = new byte[0];
        if ("POST".equals(method)) {
            Charset charset = charset(contentType);
            ByteBuffer encoded = charset.newEncoder().encode(CharBuffer.wrap(request.get("body").textValue()));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        }
        request.put("bodyBase64", Base64.getEncoder().encodeToString(bytes));

        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setDoOutput("POST".equals(method));
            headers = request.get("headers").fields();
            while (headers.hasNext()) {
                Map.Entry<String, JsonNode> header = headers.next();
                connection.setRequestProperty(header.getKey(), header.getValue().textValue());
            }
            if ("POST".equals(method)) connection.setFixedLengthStreamingMode(bytes.length);
            try { connection.connect(); }
            catch (java.net.ConnectException e) {
                throw new IllegalStateException("HTTP 连接失败，请求未送达：" + e.getMessage(), e);
            }
            ObjectNode result;
            byte[] responseBytes;
            try {
                if ("POST".equals(method)) {
                    try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                }
                int status = connection.getResponseCode();
                result = request.objectNode();
                result.put("status", status);
                result.put("contentType", connection.getContentType());
                ObjectNode responseHeaders = result.putObject("headers");
                for (Map.Entry<String, java.util.List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() != null) {
                        com.fasterxml.jackson.databind.node.ArrayNode values = responseHeaders.putArray(header.getKey());
                        for (String value : header.getValue()) values.add(value);
                    }
                }
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream != null) {
                    try (InputStream input = stream) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) != -1) response.write(buffer, 0, count);
                    }
                }
                responseBytes = response.toByteArray();
            } catch (IOException e) {
                throw new RemoteOutcomeUnknownException("HTTP 调用结果未知，需核查远端处理状态：" + e.getMessage(), e);
            }
            result.put("bodyBase64", Base64.getEncoder().encodeToString(responseBytes));
            // 先记录原始证据，再解码；错误字符集或非法字节由执行器记为失败。
            request.set("response", result);
            String responseType = connection.getContentType();
            Charset responseCharset = MediaType.APPLICATION_JSON_VALUE.equals(responseType)
                    ? StandardCharsets.UTF_8 : charset(responseType);
            result.put("body", responseCharset.newDecoder()
                    .decode(ByteBuffer.wrap(responseBytes)).toString());
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private Charset charset(String contentType) {
        if (contentType == null) throw new IllegalArgumentException("HTTP Content-Type 必须声明 charset");
        Charset charset = MediaType.parseMediaType(contentType).getCharset();
        if (charset == null) throw new IllegalArgumentException("HTTP Content-Type 必须声明 charset");
        return charset;
    }
}
