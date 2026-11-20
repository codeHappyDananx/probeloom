package dev.probeloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.probeloom.model.PlatformModels.CaseAction;

/** 从冻结的步骤配置提取接口标识，不采集主机、查询参数或报文。 */
public final class ExecutionInterfaceIdentity {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExecutionInterfaceIdentity() { }

    public static String key(CaseAction action) {
        switch (action.actionType) {
            case "HTTP":
                try {
                    JsonNode request = JSON.readTree(action.requestBody);
                    return JSON.createArrayNode().add("HTTP").add(request.get("connection").textValue())
                            .add(request.path("method").asText("POST")).add(pathOnly(request.get("path").textValue())).toString();
                } catch (java.io.IOException e) {
                    throw new IllegalArgumentException("无法读取 HTTP 步骤的接口配置", e);
                }
            case "UPLOAD":
                return JSON.createArrayNode().add("UPLOAD").add(action.upload.connection)
                        .add(pathOnly(action.upload.path)).toString();
            default:
                return null;
        }
    }

    private static String pathOnly(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }
}
