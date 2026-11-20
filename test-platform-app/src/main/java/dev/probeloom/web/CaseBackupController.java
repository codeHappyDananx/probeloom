package dev.probeloom.web;

import dev.probeloom.model.CaseBackupModels.Config;
import dev.probeloom.model.CaseBackupModels.Run;
import dev.probeloom.model.CaseBackupModels.Status;
import dev.probeloom.service.CaseBackupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 基础配置中的案例备份；沿用平台网页登录鉴权，口令只写入、不返回。 */
@RestController
@RequestMapping("/api/case-backup")
public class CaseBackupController {
    private final CaseBackupService backups;

    public CaseBackupController(CaseBackupService backups) { this.backups = backups; }

    @GetMapping("/config")
    public ApiResponse<Config> config() { return ApiResponse.ok(backups.config()); }

    /** 校验频率和仓库参数，保存加密配置并刷新定时计划。 */
    @PostMapping("/config")
    public ApiResponse<Config> save(@RequestBody Config config) { return ApiResponse.ok(backups.save(config)); }

    @GetMapping("/status")
    public ApiResponse<Status> status() { return ApiResponse.ok(backups.status()); }

    /** 按已保存配置异步备份，返回本轮编号，执行结果从状态接口查询。 */
    @PostMapping("/run")
    public ApiResponse<Run> run() { return ApiResponse.ok(backups.start(false)); }
}
