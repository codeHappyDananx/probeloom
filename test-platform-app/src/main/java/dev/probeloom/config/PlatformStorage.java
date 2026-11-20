package dev.probeloom.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class PlatformStorage {
    @Value("${platform.storage.mode:sqlite}")
    private String storageMode;

    @Value("${platform.storage.path:./data/probeloom.db}")
    private String storagePath;

    private Path databasePath;

    @PostConstruct
    public void initialize() throws IOException {
        databasePath = Paths.get(storagePath).toAbsolutePath().normalize();
        Files.createDirectories(databasePath.getParent());
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public Path getDataDirectory() {
        return databasePath.getParent();
    }

    public boolean isJdbc() {
        return "jdbc".equalsIgnoreCase(storageMode) || "server".equalsIgnoreCase(storageMode);
    }

    public String getStoragePath() {
        return storagePath;
    }
}
