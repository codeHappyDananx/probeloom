package dev.probeloom.service;

import dev.probeloom.repository.PlatformRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** MCP 专用文件令牌及执行身份，不依赖浏览器登录会话。 */
@Service
@DependsOn("platformStartupCheck")
public class McpAccessService {
    private static final Logger logger = LoggerFactory.getLogger(McpAccessService.class);
    private final PlatformRepository repository;
    private final JdbcTemplate jdbc;
    private final Path tokenFile;
    private final String migrationSourceMode;
    private byte[] token;
    private Map<String, Object> identity;

    public McpAccessService(PlatformRepository repository, JdbcTemplate platformJdbcTemplate,
                           @Value("${platform.mcp.token-file:./config/mcp-token}") String tokenFile,
                           @Value("${platform.migration.source.mode:}") String migrationSourceMode) {
        this.repository = repository;
        this.jdbc = platformJdbcTemplate;
        this.tokenFile = Paths.get(tokenFile).toAbsolutePath().normalize();
        this.migrationSourceMode = migrationSourceMode;
    }

    @PostConstruct
    public void initialize() throws Exception {
        if (!migrationSourceMode.trim().isEmpty()) return;
        /*
         * ========================================================================
         * 步骤1：准备 MCP 专用令牌
         * ========================================================================
         * 数据源：部署配置目录中的 mcp-token。
         * 操作：首次启动生成随机令牌；重启沿用文件，不写入口令或运行日志。
         */
        logger.info("开始准备 MCP 访问令牌");
        // 1.1 仅在文件不存在时生成，Unix 文件仅属主可读写。
        if (!Files.exists(tokenFile)) {
            Files.createDirectories(tokenFile.getParent());
            if (tokenFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(tokenFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(tokenFile);
            }
            String generated = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            Files.write(tokenFile, generated.getBytes(StandardCharsets.US_ASCII));
        }
        // 1.2 文件异常直接阻断启动，避免生成不可复用的访问凭据。
        String value = new String(Files.readAllBytes(tokenFile), StandardCharsets.US_ASCII).trim();
        if (!value.matches("[a-f0-9]{64}")) throw new IllegalStateException("MCP 令牌文件格式错误：" + tokenFile);
        token = value.getBytes(StandardCharsets.US_ASCII);
        logger.info("MCP 访问令牌准备完成，文件：{}", tokenFile);

        /*
         * ========================================================================
         * 步骤2：准备 MCP 执行身份
         * ========================================================================
         * 目标表：platform_user。
         * 操作：使用独立 probeloom-mcp 账号归属执行记录；登录密码为随机值，不供人工登录。
         */
        logger.info("开始准备 MCP 执行身份");
        // 2.1 首次创建身份，后续复用同一账号。
        if (!repository.hasUser("probeloom-mcp")) {
            repository.createUser("probeloom-mcp", new BCryptPasswordEncoder().encode(UUID.randomUUID().toString()));
        }
        // 2.2 保持与平台会话相同的 id、username 类型，并标识服务访问。
        identity = jdbc.queryForObject("SELECT id,username FROM platform_user WHERE username=?", (rs, row) -> {
            Map<String, Object> user = new LinkedHashMap<String, Object>();
            user.put("id", rs.getLong("id"));
            user.put("username", rs.getString("username"));
            user.put("mcp", true);
            return user;
        }, "probeloom-mcp");
        logger.info("MCP 执行身份准备完成");
    }

    public Map<String, Object> authenticatedUser(String suppliedToken) {
        return token != null && suppliedToken != null
                && MessageDigest.isEqual(token, suppliedToken.getBytes(StandardCharsets.US_ASCII)) ? identity : null;
    }
}
