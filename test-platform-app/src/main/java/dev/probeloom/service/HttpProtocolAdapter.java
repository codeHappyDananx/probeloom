package dev.probeloom.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.probeloom.model.PlatformModels.ProtocolConnection;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** HTTP 和 HTTPS 共用同一适配器；正文和响应原始字节由 HTTP 客户端保存。 */
@Component
public final class HttpProtocolAdapter implements ProtocolAdapter {
    @Override
    public String protocol() { return "HTTP"; }

    @Override
    public ObjectNode execute(ProtocolConnection connection, ObjectNode request) throws Exception {
        request.put("url", resolveUrl(connection.baseUrl, request.get("path").textValue()));
        ObjectNode headers = (ObjectNode) request.get("headers");
        for (Map.Entry<String, String> header : connection.headers.entrySet()) {
            List<String> replaced = new ArrayList<String>();
            headers.fieldNames().forEachRemaining(name -> {
                if (name.equalsIgnoreCase(header.getKey())) replaced.add(name);
            });
            replaced.forEach(headers::remove);
            headers.put(header.getKey(), header.getValue());
        }
        return new HttpTextClient().execute(request);
    }

    static String resolveUrl(String baseUrl, String path) {
        URI base = URI.create(baseUrl);
        URI relative = URI.create(path);
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null) {
            throw new IllegalArgumentException("连接地址必须是完整 HTTP/HTTPS 地址");
        }
        if (relative.isAbsolute() || relative.getRawAuthority() != null || relative.getRawFragment() != null || !path.startsWith("/")) {
            throw new IllegalArgumentException("请求路径必须是以 / 开头的站内路径");
        }
        return base.resolve(relative).toString();
    }
}
