package dev.probeloom.service;

import dev.probeloom.model.PlatformModels;
import dev.probeloom.repository.PlatformRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;

@Service
public class ScheduleService {
    @org.springframework.beans.factory.annotation.Value("${platform.supervisor.only:false}") private boolean supervisorOnly;
    private final PlatformRepository repository;
    private final ExecutionService executionService;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<Long, ScheduledFuture<?>> futures = new HashMap<Long, ScheduledFuture<?>>();

    public ScheduleService(PlatformRepository repository, ExecutionService executionService) {
        this.repository = repository;
        this.executionService = executionService;
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("test-platform-scheduler-");
        scheduler.initialize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void reload() {
        if (supervisorOnly) return;
        for (ScheduledFuture<?> future : futures.values()) { future.cancel(false); }
        futures.clear();
        for (PlatformModels.SchedulePlan plan : repository.listSchedules()) {
            if (Boolean.TRUE.equals(plan.enabled) && plan.environmentId != null) {
                Trigger trigger = new CronTrigger(plan.cronExpression, TimeZone.getTimeZone("Asia/Shanghai"));
                futures.put(plan.id, scheduler.schedule(() -> executionService.queueGroup(plan.groupId, plan.environmentId, "SCHEDULE"), trigger));
            }
        }
    }

    @PreDestroy
    public void shutdown() { scheduler.shutdown(); }
}
