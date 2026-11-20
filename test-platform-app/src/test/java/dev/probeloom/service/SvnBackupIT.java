package dev.probeloom.service;

import dev.probeloom.model.CaseBackupModels.Config;
import dev.probeloom.model.CaseBackupModels.Result;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit integration tests against an isolated HTTP SVN repository. */
class SvnBackupIT {
    @TempDir Path directory;
    @BeforeAll static void fixture() throws Exception { SvnTestFixture.load(); }

    @Test
    void publishesOnlyCasesAtomicallyWithoutChangingExistingProperties() throws Exception {
        Config config = SvnTestFixture.repository(System.getProperty("probeloom.svn.httpUrl"), directory);
        SvnCli cli = new SvnCli(config, directory.resolve("inspect"));
        Path outside = file("outside.txt", "outside");
        cli.run("svnmucc", Arrays.asList("-m", "outside fixture", "put", outside.toString(), config.repositoryUrl + "/outside.txt",
                "propset", "fixture:root", "preserved", config.repositoryUrl));
        Path export = Files.createDirectory(directory.resolve("export"));
        write(export.resolve("change.xlsx"), "first");
        write(export.resolve("remove.xlsx"), "remove");
        write(export.resolve("file-to-directory"), "file");
        Files.createDirectory(export.resolve("directory-to-file"));
        Files.createDirectory(export.resolve("empty"));
        long before = revision(cli, config.repositoryUrl);
        CaseBackupPublisher publisher = new CaseBackupPublisher();
        Result first = publisher.publish(config, export, directory.resolve("work-first"), "first backup");
        assertTrue(first.changed);
        assertEquals("r" + (before + 1), first.revision);
        cli.run("svnmucc", Arrays.asList("-m", "property fixture", "propset", "fixture:cases", "keep directory", config.repositoryUrl + "/cases",
                "propset", "fixture:file", "keep file", config.repositoryUrl + "/cases/change.xlsx"));
        Result same = publisher.publish(config, export, directory.resolve("work-same"), "unchanged");
        assertFalse(same.changed);
        assertEquals("r" + revision(cli, config.repositoryUrl), same.revision);
        write(export.resolve("change.xlsx"), "second");
        Files.delete(export.resolve("remove.xlsx"));
        Files.delete(export.resolve("file-to-directory"));
        Files.createDirectory(export.resolve("file-to-directory"));
        write(export.resolve("file-to-directory/嵌套.xlsx"), "nested");
        Files.delete(export.resolve("directory-to-file"));
        write(export.resolve("directory-to-file"), "replacement");
        long revision = revision(cli, config.repositoryUrl);
        Result changed = publisher.publish(config, export, directory.resolve("work-changed"), "changed backup");
        assertEquals("r" + (revision + 1), changed.revision);
        assertEquals("outside", text(cli, "cat", config.repositoryUrl + "/outside.txt"));
        assertEquals("second", text(cli, "cat", config.repositoryUrl + "/cases/change.xlsx"));
        assertEquals("replacement", text(cli, "cat", config.repositoryUrl + "/cases/directory-to-file"));
        assertEquals("nested", text(cli, "cat", config.repositoryUrl + "/cases/file-to-directory/嵌套.xlsx"));
        assertEquals("preserved", text(cli, "propget", "fixture:root", config.repositoryUrl).trim());
        assertEquals("keep directory", text(cli, "propget", "fixture:cases", config.repositoryUrl + "/cases").trim());
        assertEquals("keep file", text(cli, "propget", "fixture:file", config.repositoryUrl + "/cases/change.xlsx").trim());
        assertEquals("dir", text(cli, "info", "--show-item", "kind", config.repositoryUrl + "/cases/empty").trim());
        assertThrows(Exception.class, () -> text(cli, "info", config.repositoryUrl + "/cases/remove.xlsx"));
    }

    @Test
    void rejectsStaleRevisionWithoutCommittingAnyPartialFile() throws Exception {
        Config config = SvnTestFixture.repository(System.getProperty("probeloom.svn.httpUrl"), directory);
        CaseBackupPublisher publisher = new CaseBackupPublisher();
        Path export = Files.createDirectory(directory.resolve("export"));
        write(export.resolve("change.xlsx"), "initial");
        publisher.publish(config, export, directory.resolve("initial"), "initial");
        SvnCli cli = new SvnCli(config, directory.resolve("stale-client"));
        long base = revision(cli, config.repositoryUrl);
        Path before = directory.resolve("before");
        cli.snapshot(config.repositoryUrl + "/cases", base, before);
        Path winner = file("winner.xlsx", "concurrent winner");
        cli.run("svnmucc", Arrays.asList("-r", String.valueOf(base), "-m", "competing commit", "put", winner.toString(), config.repositoryUrl + "/cases/change.xlsx"));
        write(export.resolve("change.xlsx"), "stale attempt");
        write(export.resolve("a-partial.xlsx"), "must not commit");
        assertThrows(Exception.class, () -> publisher.syncSvn(cli, config.repositoryUrl + "/cases", base, before, export, "stale commit"));
        assertEquals(base + 1, revision(cli, config.repositoryUrl));
        assertEquals("concurrent winner", text(cli, "cat", config.repositoryUrl + "/cases/change.xlsx"));
        assertThrows(Exception.class, () -> text(cli, "info", config.repositoryUrl + "/cases/a-partial.xlsx"));
        assertEquals("initial", text(cli, "cat", "-r", String.valueOf(base), config.repositoryUrl + "/cases/change.xlsx"));
    }

    @Test
    void scopesBackupToConfiguredSubdirectory() throws Exception {
        Config config = SvnTestFixture.repository(System.getProperty("probeloom.svn.httpUrl"), directory);
        SvnCli cli = new SvnCli(config, directory.resolve("inspect"));
        String rootUrl = config.repositoryUrl;
        cli.run("svnmucc", Arrays.asList("-m", "branch", "mkdir", rootUrl + "/branch", "propset", "fixture:branch", "preserved", rootUrl + "/branch"));
        Path rootExport = Files.createDirectory(directory.resolve("root-export"));
        write(rootExport.resolve("root.xlsx"), "root remains");
        CaseBackupPublisher publisher = new CaseBackupPublisher();
        publisher.publish(config, rootExport, directory.resolve("root-work"), "root backup");
        config.repositoryUrl = rootUrl + "/branch";
        Path branchExport = Files.createDirectory(directory.resolve("branch-export"));
        write(branchExport.resolve("branch.xlsx"), "branch first");
        publisher.publish(config, branchExport, directory.resolve("branch-work"), "branch backup");
        write(branchExport.resolve("branch.xlsx"), "branch updated");
        assertTrue(publisher.publish(config, branchExport, directory.resolve("branch-update"), "branch update").changed);
        assertEquals("branch updated", text(cli, "cat", rootUrl + "/branch/cases/branch.xlsx"));
        assertEquals("root remains", text(cli, "cat", rootUrl + "/cases/root.xlsx"));
        assertEquals("preserved", text(cli, "propget", "fixture:branch", rootUrl + "/branch").trim());
        assertThrows(Exception.class, () -> text(cli, "info", rootUrl + "/cases/branch.xlsx"));
        assertThrows(Exception.class, () -> text(cli, "info", rootUrl + "/branch/cases/root.xlsx"));
    }

    private static long revision(SvnCli cli, String url) throws Exception { return Long.parseLong(text(cli, "info", "--show-item", "revision", url).trim()); }
    private static String text(SvnCli cli, String... arguments) throws Exception { return new String(cli.run("svn", Arrays.asList(arguments)), StandardCharsets.UTF_8); }
    private Path file(String name, String value) throws Exception { Path file = directory.resolve(name); write(file, value); return file; }
    private static void write(Path file, String value) throws Exception { Files.write(file, value.getBytes(StandardCharsets.UTF_8)); }
}

final class SvnTestFixture {
    static void load() throws Exception {
        String path = System.getProperty("probeloom.svn.fixture");
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("Provide -Dprobeloom.svn.fixture with isolated fixture properties");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Paths.get(path))) { properties.load(input); }
        for (String name : properties.stringPropertyNames()) System.setProperty(name, properties.getProperty(name));
    }
    static Config repository(String rootUrl, Path directory) throws Exception {
        Config config = new Config();
        config.repositoryType = "SVN";
        config.repositoryUrl = rootUrl;
        config.username = System.getProperty("probeloom.svn.username");
        config.password = System.getProperty("probeloom.svn.password");
        String url = rootUrl + "/test-" + UUID.randomUUID();
        new SvnCli(config, directory.resolve("fixture-create")).run("svnmucc", Arrays.asList("-m", "isolated test directory", "mkdir", url));
        config.repositoryUrl = url;
        return config;
    }
}
