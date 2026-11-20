package dev.probeloom.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.probeloom.model.PlatformModels.ProtocolConnection;

/** 声明连接协议，并将请求交给该协议实现执行。实现类作为 Spring Bean 注册。 */
public interface ProtocolAdapter {
    String protocol();
    ObjectNode execute(ProtocolConnection connection, ObjectNode request) throws Exception;
}
