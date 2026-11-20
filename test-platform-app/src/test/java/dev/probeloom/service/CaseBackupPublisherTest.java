package dev.probeloom.service;

import dev.probeloom.model.CaseBackupModels.Config;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CaseBackupPublisherTest {
    @TempDir Path directory;

    @AfterEach
    void releaseReadOnlyGitObjects() throws Exception {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile).forEach(path -> path.toFile().setWritable(true));
        }
    }

    @Test
    void gitBackupPreservesOutsideFilesAndDoesNotCommitUnchangedCases() throws Exception {
        Path remote = directory.resolve("remote.git");
        try (Git bare = Git.init().setBare(true).setDirectory(remote.toFile()).call()) { }
        Path initial = directory.resolve("initial");
        try (Git git = Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(initial.toFile()).call()) {
            Files.write(initial.resolve("outside.txt"), "outside".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("outside.txt").call();
            git.commit().setMessage("fixture").setAuthor("Fixture", "fixture@localhost").setCommitter("Fixture", "fixture@localhost").call();
            git.push().call();
        }
        Config config = new Config();
        config.repositoryType = "GIT";
        config.repositoryUrl = remote.toUri().toString();
        config.branch = "master";
        Path export = Files.createDirectory(directory.resolve("export"));
        Files.write(export.resolve("case.xlsx"), "first".getBytes(StandardCharsets.UTF_8));
        CaseBackupPublisher publisher = new CaseBackupPublisher();
        assertTrue(publisher.publish(config, export, directory.resolve("first"), "first backup").changed);
        assertFalse(publisher.publish(config, export, directory.resolve("same"), "no changes").changed);
        Files.write(export.resolve("case.xlsx"), "second".getBytes(StandardCharsets.UTF_8));
        assertTrue(publisher.publish(config, export, directory.resolve("second"), "second backup").changed);
        try (Git verify = Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(directory.resolve("verify").toFile()).call()) {
            Path checkout = verify.getRepository().getWorkTree().toPath();
            assertEquals("outside", new String(Files.readAllBytes(checkout.resolve("outside.txt")), StandardCharsets.UTF_8));
            assertEquals("second", new String(Files.readAllBytes(checkout.resolve("cases/case.xlsx")), StandardCharsets.UTF_8));
        }
    }
}
