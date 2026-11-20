package dev.probeloom.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.probeloom.service.AuthService;
import dev.probeloom.service.McpAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String AUTHENTICATED_USER = "platform.authenticatedUser";
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final McpAccessService mcpAccessService;

    public AuthInterceptor(AuthService authService, ObjectMapper objectMapper, McpAccessService mcpAccessService) {
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.mcpAccessService = mcpAccessService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || ("GET".equals(request.getMethod()) && "/api/auth/status".equals(path))
                || ("POST".equals(request.getMethod()) && ("/api/auth/login".equals(path) || "/api/auth/initialize".equals(path)))) {
            return true;
        }
        String token = request.getHeader("X-Platform-Token");
        if (token == null || token.trim().isEmpty()) {
            token = request.getHeader("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring("Bearer ".length());
            }
        }
        Map<String, Object> user = mcpAccessService.authenticatedUser(token);
        if (user != null && !allowsMcpRequest(request)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail("MCP 令牌仅允许基础配置维护、创建分组、案例操作、执行查询及结束执行")));
            return false;
        }
        if (user == null) user = authService.authenticatedUser(token);
        if (user != null) {
            request.setAttribute(AUTHENTICATED_USER, user);
            return true;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail("登录已失效，请重新登录")));
        return false;
    }

    private boolean allowsMcpRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("GET".equals(request.getMethod())) {
            return path.matches("/api/(bootstrap|cases/(page|summaries|recycle-bin|\\d+|\\d+/workbook)|case-workbook/template|executions|executions/(page|\\d+))");
        }
        if ("DELETE".equals(request.getMethod())) {
            return path.matches("/api/cases/\\d+(/recycle-bin)?");
        }
        return "POST".equals(request.getMethod())
                && path.matches("/api/(environments|datasources|case-workbook/import|cases|cases/(applications|run-batch|delete-batch)|cases/\\d+/(run|restore)|groups/(create|\\d+/run)|executions/(status|\\d+/end))");
    }
}
