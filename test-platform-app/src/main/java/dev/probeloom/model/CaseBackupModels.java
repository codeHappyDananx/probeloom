package dev.probeloom.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class CaseBackupModels {
    private CaseBackupModels() { }

    public static class Config {
        public boolean enabled;
        public String frequency = "DAILY";
        public String time = "02:00";
        public Integer dayOfWeek;
        public String repositoryType = "SVN";
        public String repositoryUrl = "";
        public String branch = "";
        public String username = "";
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public String password;
        public boolean passwordConfigured;
        public String certificateSha256 = "";
    }

    public static class Run {
        public long id;
        public String triggerType;
        public String status;
        public String repositoryType;
        public String repositoryUrl;
        public String startedAt;
        public String finishedAt;
        public int caseCount;
        public String revision;
        public String errorMessage;
    }

    public static class Status {
        public boolean running;
        public String nextRunAt;
        public List<Run> runs;
    }

    public static class Result {
        public final boolean changed;
        public final String revision;

        public Result(boolean changed, String revision) {
            this.changed = changed;
            this.revision = revision;
        }
    }
}
