package dev.probeloom.service;

import dev.probeloom.config.PlatformStorage;
import dev.probeloom.model.CaseBackupModels.Config;
import dev.probeloom.model.CaseBackupModels.Result;
import dev.probeloom.model.CaseBackupModels.Run;
import dev.probeloom.model.CaseBackupModels.Status;
import dev.probeloom.model.PlatformModels;
import dev.probeloom.repository.CaseBackupRepository;
import dev.probeloom.repository.PlatformRepository;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronSequenceGenerator;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;

@Service
public class CaseBackupService {
    @org.springframework.beans.factory.annotation.Value("${platform.supervisor.only:false}") private boolean supervisorOnly;
    private static final Logger logger = LoggerFactory.getLogger(CaseBackupService.class);
    private static final TimeZone TIMEZONE = TimeZone.getTimeZone("Asia/Shanghai");
    private final CaseBackupRepository backups;
    private final PlatformRepository cases;
    private final CaseWorkbookService workbooks;
    private final CaseBackupPublisher publisher;
    private final PlatformStorage storage;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private ScheduledFuture<?> future;

    public CaseBackupService(CaseBackupRepository backups, PlatformRepository cases, CaseWorkbookService workbooks, CaseBackupPublisher publisher, PlatformStorage storage) {
        this.backups = backups;
        this.cases = cases;
        this.workbooks = workbooks;
        this.publisher = publisher;
        this.storage = storage;
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("case-backup-");
        scheduler.initialize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void initialize() {
        if (supervisorOnly) return;
        backups.recoverInterrupted();
        reload(backups.config(false));
    }

    public Config config() { return backups.config(false); }

    public synchronized Config save(Config value) {
        if (backups.running()) throw new IllegalStateException("备份执行中，请结束后再修改配置");
        validate(value);
        Config previous = backups.config(true);
        if ((value.password == null || value.password.isEmpty()) && value.repositoryType.equals(previous.repositoryType)
                && value.repositoryUrl.equals(previous.repositoryUrl) && value.username.equals(previous.username)) value.password = previous.password;
        backups.save(value);
        reload(value);
        return config();
    }

    public Status status() {
        Status value = new Status();
        value.running = backups.running();
        value.runs = backups.recentRuns();
        Config config = config();
        if (config.enabled) value.nextRunAt = new CronSequenceGenerator(cron(config), TIMEZONE).next(new Date()).toInstant().toString();
        return value;
    }

    public synchronized Run start(boolean scheduled) {
        Config config = backups.config(true);
        if (scheduled && !config.enabled) return null;
        validate(config);
        String slot = scheduled ? LocalDate.now(ZoneId.of("Asia/Shanghai")) + "T" + config.time : null;
        Run run = backups.start(config, scheduled ? "SCHEDULE" : "MANUAL", slot);
        if (run != null) scheduler.execute(() -> execute(config, run));
        return run;
    }

    private void reload(Config config) {
        if (future != null) future.cancel(false);
        future = null;
        if (config.enabled) future = scheduler.schedule(() -> {
            try { start(true); }
            catch (RuntimeException e) { logger.error("提交定时案例备份失败：{}", e.getMessage()); }
        }, new CronTrigger(cron(config), TIMEZONE));
    }

    private void execute(Config config, Run run) {
        Path temporary = null;
        int count = 0;
        try {
            /*
             * ========================================================================
             * 步骤1：逐案例导出 Excel
             * ========================================================================
             * 数据源：平台库中全部未删除案例，包含停用案例及案例附件。
             * 1) 按平台完整分组树建立同名文件夹，每次只持有一个完整案例和工作簿。
             * 2) 全部导出完成才连接仓库，导出失败不修改远端案例文件。
             */
            logger.info("开始导出案例备份，备份编号：{}", run.id);
            temporary = Files.createTempDirectory(storage.getDataDirectory(), "case-backup-");
            Path export = Files.createDirectory(temporary.resolve("export"));
            Map<Long, PlatformModels.CaseGroup> groups = new HashMap<Long, PlatformModels.CaseGroup>();
            for (PlatformModels.CaseGroup group : cases.listGroups()) groups.put(group.id, group);
            Map<Long, Path> directories = new HashMap<Long, Path>();
            for (PlatformModels.CaseGroup group : groups.values()) {
                ArrayDeque<String> names = new ArrayDeque<String>();
                for (PlatformModels.CaseGroup current = group; current != null; current = current.parentId == null ? null : groups.get(current.parentId)) {
                    if (current.name.matches(".*[\\x00-\\x1f<>:\"/\\\\|?*].*") || current.name.matches(".*[ .]$") || current.name.equals(".")) throw new IllegalArgumentException("分组名称不能直接用作文件夹：" + current.name);
                    names.addFirst(current.name);
                }
                Path directory = export;
                for (String name : names) directory = directory.resolve(name);
                directories.put(group.id, Files.createDirectories(directory));
            }
            List<PlatformModels.TestCase> summaries = cases.listCaseSummaries();
            for (PlatformModels.TestCase summary : summaries) {
                // 1.1 编号防止同名覆盖，名称仅用于辨识，文件路径不接收业务 SQL 或外部路径。
                Path directory = summary.groupId == null ? export : directories.get(summary.groupId);
                // 1.2 复用页面导出入口，附件随案例工作簿留存。
                try {
                    Files.write(directory.resolve(summary.id + "_" + fileName(summary.name) + ".xlsx"), stableBackupWorkbook(workbooks.exportCase(summary.id)));
                } catch (RuntimeException e) {
                    throw new IllegalStateException("案例 " + summary.caseCode + "（" + summary.name + "）导出失败：" + e.getMessage(), e);
                }
                count++;
            }
            // 1.3 Git 不记录空目录，使用固定占位文件保留平台的空分组。
            if ("GIT".equals(config.repositoryType)) {
                for (Path directory : directories.values()) {
                    try (java.util.stream.Stream<Path> children = Files.list(directory)) {
                        if (!children.findAny().isPresent()) Files.createFile(directory.resolve(".gitkeep"));
                    }
                }
            }
            logger.info("案例备份导出完成，备份编号：{}，案例数：{}", run.id, count);

            /*
             * ========================================================================
             * 步骤2：提交案例目录并保存结果
             * ========================================================================
             * 目标：已配置 SVN 或 Git 仓库中的 cases 目录。
             * 1) 按本轮导出覆盖更新，移除已删除、改名和换组案例的旧文件。
             * 2) 仓库提交成功后保存版本；失败保留原因，下次从远端最新版本重新开始。
             */
            logger.info("开始提交案例备份，备份编号：{}，仓库类型：{}", run.id, config.repositoryType);
            Result result = publisher.publish(config, export, temporary.resolve("git"), "[测试案例备份][" + count + "个案例]");
            backups.finish(run.id, result.changed ? "SUCCESS" : "NO_CHANGES", count, result.revision, null);
            logger.info("案例备份提交完成，备份编号：{}，版本：{}", run.id, result.revision);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (config.password != null && !config.password.isEmpty()) message = message.replace(config.password, "***");
            backups.finish(run.id, "FAILED", count, null, message);
            logger.error("案例备份失败，备份编号：{}，原因：{}", run.id, message);
        } finally {
            if (temporary != null) {
                try { publisher.deleteTree(temporary); }
                catch (java.io.IOException e) { logger.warn("清理案例备份临时目录失败，备份编号：{}", run.id); }
            }
        }
    }

    static byte[] stableBackupWorkbook(byte[] workbook) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(workbook));
             ZipOutputStream output = new ZipOutputStream(result)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                ZipEntry stable = new ZipEntry(entry.getName());
                stable.setTime(315532800000L);
                output.putNextEntry(stable);
                if ("docProps/core.xml".equals(entry.getName())) {
                    ByteArrayOutputStream content = new ByteArrayOutputStream();
                    int count;
                    while ((count = input.read(buffer)) != -1) content.write(buffer, 0, count);
                    String xml = new String(content.toByteArray(), StandardCharsets.UTF_8);
                    // 工作簿由本应用的 POI 导出；仅固定已确认每次生成变化的 created 值。
                    xml = xml.replaceFirst("(<dcterms:created[^>]*>)[^<]*(</dcterms:created>)", "$11980-01-01T00:00:00Z$2");
                    output.write(xml.getBytes(StandardCharsets.UTF_8));
                } else {
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                output.closeEntry();
            }
        }
        return result.toByteArray();
    }

    private String fileName(String value) {
        String name = value.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").replaceAll("[ .]+$", "");
        return name.substring(0, name.offsetByCodePoints(0, Math.min(60, name.codePointCount(0, name.length()))));
    }

    private String cron(Config value) {
        LocalTime time = LocalTime.parse(value.time);
        return "0 " + time.getMinute() + " " + time.getHour() + " * * " + ("WEEKLY".equals(value.frequency) ? value.dayOfWeek % 7 : "*");
    }

    private void validate(Config value) {
        if (!"DAILY".equals(value.frequency) && !"WEEKLY".equals(value.frequency)) throw new IllegalArgumentException("备份频率仅支持每天或每周");
        if (value.time == null || !value.time.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException("备份时间须填写 HH:mm，按北京时间执行");
        if ("WEEKLY".equals(value.frequency) && (value.dayOfWeek == null || value.dayOfWeek < 1 || value.dayOfWeek > 7)) throw new IllegalArgumentException("请选择备份星期");
        if (!"SVN".equals(value.repositoryType) && !"GIT".equals(value.repositoryType)) throw new IllegalArgumentException("仓库类型仅支持 SVN 或 Git");
        if (value.repositoryUrl == null || value.repositoryUrl.isEmpty()) throw new IllegalArgumentException("请填写仓库 HTTP(S) 地址");
        URI uri = URI.create(value.repositoryUrl);
        if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException("仓库地址须为 HTTP(S) 地址，认证信息请单独填写");
        if (value.username == null || value.certificateSha256 == null) throw new IllegalArgumentException("仓库用户名及证书指纹须为字符串，未配置时填写空字符串");
        if (!value.certificateSha256.isEmpty() && (!"SVN".equals(value.repositoryType) || !value.certificateSha256.matches("[0-9a-fA-F]{64}"))) throw new IllegalArgumentException("SVN 证书指纹须填写 64 位 SHA-256 十六进制值");
        if ("GIT".equals(value.repositoryType) && (value.branch == null || !Repository.isValidRefName("refs/heads/" + value.branch))) throw new IllegalArgumentException("请填写有效的 Git 分支名");
    }

    @PreDestroy
    public void shutdown() { scheduler.shutdown(); }
}
