package dev.probeloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.probeloom.model.PlatformModels;

/** 平台保存和工作簿导入共用的 HTTP 文本案例契约。 */
public final class HttpCaseRules {
    private HttpCaseRules() { }

    public static void validate(PlatformModels.CaseAction action) {
        if (!"HTTP".equals(action.actionType)) return;
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode request = mapper.readTree(action.requestBody);
            if (request == null || !request.isObject() || request.has("url")
                    || !request.path("connection").isTextual() || request.get("connection").textValue().trim().isEmpty()
                    || !request.path("path").isTextual() || !request.path("headers").isObject()
                    || !request.path("body").isTextual()) {
                throw new IllegalArgumentException("HTTP 请求须包含连接标识 connection、接口路径 path、对象 headers、字符串 body");
            }
            java.util.Iterator<String> fields = request.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!java.util.Arrays.asList("connection", "method", "path", "headers", "body").contains(field)) {
                    throw new IllegalArgumentException("HTTP 请求字段不支持：" + field);
                }
            }
            if (request.has("method") && (!request.get("method").isTextual()
                    || !("GET".equals(request.get("method").textValue())
                    || "POST".equals(request.get("method").textValue())))) {
                throw new IllegalArgumentException("HTTP method 只支持 GET 或 POST");
            }
            if ("GET".equals(request.path("method").textValue()) && !request.get("body").textValue().isEmpty()) {
                throw new IllegalArgumentException("HTTP GET 的 body 必须为空，参数填写在 path 查询串中");
            }
            java.net.URI path = new java.net.URI(request.get("path").textValue()
                    .replaceAll("\\{\\{\\s*[a-zA-Z0-9_.-]+\\s*}}", "variable"));
            if (!request.get("path").textValue().startsWith("/") || path.isAbsolute()
                    || path.getRawAuthority() != null || path.getRawFragment() != null) {
                throw new IllegalArgumentException("HTTP 接口路径必须以 / 开头，不能包含目标主机或片段");
            }
            java.util.Iterator<JsonNode> headers = request.get("headers").elements();
            while (headers.hasNext()) {
                if (!headers.next().isTextual()) throw new IllegalArgumentException("HTTP 请求头必须是字符串");
            }
            JsonNode assertion = mapper.readTree(action.assertionsJson);
            JsonNode expected = assertion == null ? null : assertion.get("expected");
            if (assertion == null || assertion.path("version").asInt() != 2 || expected == null
                    || !expected.isObject() || !expected.path("status").isIntegralNumber()
                    || !expected.path("contentType").isTextual() || !expected.path("body").isTextual()) {
                throw new IllegalArgumentException("HTTP 断言须使用 version=2，并同时核对 status、contentType、body");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("HTTP 请求或断言 JSON 不合法：" + action.name, e);
        }
    }

    public static void validateUpload(PlatformModels.CaseAction action) {
        PlatformModels.CaseUpload upload = action.upload;
        if (upload == null || upload.connection == null || upload.connection.trim().isEmpty()) {
            throw new IllegalArgumentException("上传步骤须填写连接标识");
        }
        try {
            java.net.URI path = new java.net.URI(upload.path.replaceAll("\\{\\{\\s*[a-zA-Z0-9_.-]+\\s*}}", "variable"));
            if (!upload.path.startsWith("/") || path.isAbsolute() || path.getRawAuthority() != null || path.getRawFragment() != null) {
                throw new IllegalArgumentException("上传接口路径必须以 / 开头，不能包含目标主机或片段");
            }
        } catch (java.net.URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("上传接口路径不合法", e);
        }
    }

    public static void validateConnections(java.util.List<PlatformModels.ProtocolConnection> connections) {
        java.util.Set<String> keys = new java.util.HashSet<String>();
        for (PlatformModels.ProtocolConnection connection : connections) {
            if (connection.key == null || !connection.key.matches("[A-Za-z][A-Za-z0-9_-]*") || !keys.add(connection.key)) {
                throw new IllegalArgumentException("HTTP 连接标识须以字母开头，仅含字母、数字、下划线或横线，且环境内唯一");
            }
            if (!"HTTP".equals(connection.protocol)) throw new IllegalArgumentException("连接协议不支持：" + connection.protocol);
            try {
                java.net.URI uri = new java.net.URI(connection.baseUrl);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                    throw new IllegalArgumentException("HTTP 服务根地址只能包含 http/https、主机和端口：" + connection.key);
                }
            } catch (java.net.URISyntaxException | NullPointerException e) {
                throw new IllegalArgumentException("HTTP 服务根地址不合法：" + connection.key, e);
            }
            if (connection.headers == null) throw new IllegalArgumentException("HTTP 环境请求头必须是对象");
            java.util.Set<String> names = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            for (java.util.Map.Entry<String, String> header : connection.headers.entrySet()) {
                if (header.getKey().trim().isEmpty() || header.getValue() == null || !names.add(header.getKey())) {
                    throw new IllegalArgumentException("HTTP 环境请求头名称不能为空或重复，值必须是字符串");
                }
            }
        }
    }
}
