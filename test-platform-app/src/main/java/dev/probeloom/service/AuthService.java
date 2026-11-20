package dev.probeloom.service;

import dev.probeloom.model.PlatformModels;
import dev.probeloom.repository.PlatformRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {
    private final PlatformRepository repository;
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(PlatformRepository repository, JdbcTemplate platformJdbcTemplate) {
        this.repository = repository;
        this.jdbc = platformJdbcTemplate;
    }

    public boolean initializationRequired() {
        return !repository.hasUser("admin");
    }

    @Transactional
    public PlatformModels.LoginResult initializeAdmin(PlatformModels.InitializeAdminRequest request) {
        if (request == null || request.password == null || request.password.isEmpty()) {
            throw new IllegalArgumentException("请设置管理员密码");
        }
        // 唯一用户名约束保证并发首次设置仅创建一次，已有管理员密码不可覆盖。
        int created = jdbc.update("INSERT INTO platform_user(username,password_hash) VALUES (?,?) ON CONFLICT (username) DO NOTHING",
                "admin", passwordEncoder.encode(request.password));
        if (created == 0) throw new IllegalArgumentException("管理员已初始化，请登录");
        PlatformModels.LoginResult result = new PlatformModels.LoginResult();
        result.username = "admin";
        result.token = repository.createSession("admin");
        return result;
    }

    public PlatformModels.LoginResult login(PlatformModels.LoginRequest request) {
        if (request == null || request.username == null || request.password == null) {
            throw new IllegalArgumentException("请输入用户名和密码");
        }
        String passwordHash = repository.findPasswordHash(request.username);
        if (passwordHash == null || !passwordEncoder.matches(request.password, passwordHash)) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        PlatformModels.LoginResult result = new PlatformModels.LoginResult();
        result.username = request.username;
        result.token = repository.createSession(request.username);
        return result;
    }

    /** 根据有效会话读取操作者身份，执行归属不接收客户端用户字段。 */
    public Map<String, Object> authenticatedUser(String token) {
        if (token == null) return null;
        List<Map<String, Object>> users = jdbc.query(
                "SELECT u.id,u.username FROM session_token s JOIN platform_user u ON u.id=s.user_id WHERE s.token=? AND s.expire_at>=?",
                (rs, row) -> {
                    Map<String, Object> user = new LinkedHashMap<String, Object>();
                    user.put("id", rs.getLong("id"));
                    user.put("username", rs.getString("username"));
                    return user;
                }, token, Instant.now().toString());
        return users.isEmpty() ? null : users.get(0);
    }
}
