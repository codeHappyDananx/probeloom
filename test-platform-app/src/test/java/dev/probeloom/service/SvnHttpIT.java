package dev.probeloom.service;

import dev.probeloom.model.CaseBackupModels.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 显式运行；真实 HTTP/HTTPS 服务器由测试环境提供，缺失参数直接失败。 */
class SvnHttpIT {
    @TempDir Path directory;

    @BeforeAll
    static void fixture() throws Exception { SvnTestFixture.load(); }

    @Test
    void fallsBackToIpv4WhenLocalhostIpv6ConnectionIsRefused() throws Exception {
        String root = required("probeloom.svn.ipv4FallbackUrl");
        URI endpoint = URI.create(root);
        assertEquals("localhost", endpoint.getHost());
        try (Socket ipv4 = new Socket()) {
            ipv4.connect(new InetSocketAddress("127.0.0.1", endpoint.getPort()), 2000);
        }
        try (Socket ipv6 = new Socket()) {
            assertThrows(java.io.IOException.class, () ->
                    ipv6.connect(new InetSocketAddress("::1", endpoint.getPort()), 2000),
                    "Fallback fixture must listen on IPv4 only");
        }
        Config config = SvnTestFixture.repository(root, directory);
        Path export = export();
        CaseBackupPublisher publisher = new CaseBackupPublisher();
        Path work = directory.resolve("fallback");
        assertTrue(publisher.publish(config, export, work, "IPv6 refusal with IPv4 fallback").changed);
        assertFalse(publisher.publish(config, export, directory.resolve("fallback-unchanged"), "IPv4 fallback unchanged").changed);
        assertNoCachedCredentials(work);
    }

    @Test
    void validatesHttpCredentialsWithoutCachingThem() throws Exception {
        Config correct = SvnTestFixture.repository(required("probeloom.svn.httpUrl"), directory);
        Path export = export();
        CaseBackupPublisher publisher = new CaseBackupPublisher();
        Path accepted = directory.resolve("accepted");
        publisher.publish(correct, export, accepted, "HTTP authentication");
        assertNoCachedCredentials(accepted);
        Files.write(export.resolve("sample.xlsx"), "HTTP content update".getBytes(StandardCharsets.UTF_8));
        assertTrue(publisher.publish(correct, export, directory.resolve("content-update"), "HTTP content update").changed);
        Files.createDirectory(export.resolve("nested"));
        Files.write(export.resolve("nested/新增.xlsx"), "nested".getBytes(StandardCharsets.UTF_8));
        assertTrue(publisher.publish(correct, export, directory.resolve("add-child"), "HTTP add child").changed);
        Files.delete(export.resolve("nested/新增.xlsx"));
        Files.delete(export.resolve("nested"));
        assertTrue(publisher.publish(correct, export, directory.resolve("remove-child"), "HTTP remove child").changed);
        assertFalse(publisher.publish(correct, export, directory.resolve("unchanged"), "HTTP unchanged").changed);
        Config incorrect = config(correct.repositoryUrl);
        incorrect.password = "deliberately-wrong-password";
        Path rejected = directory.resolve("rejected");
        assertThrows(Exception.class, () -> publisher.publish(incorrect, export, rejected, "reject wrong credentials"));
        assertNoCachedCredentials(rejected);
    }

    @Test
    void acceptsOnlyConfiguredSha256ForSelfSignedTls() throws Exception {
        Config config = SvnTestFixture.repository(required("probeloom.svn.httpUrl"), directory);
        config.repositoryUrl = config.repositoryUrl.replace(required("probeloom.svn.httpUrl"), required("probeloom.svn.httpsUrl"));
        Path export = export();
        CaseBackupPublisher publisher = new CaseBackupPublisher();
        Path missing = directory.resolve("missing-pin");
        assertThrows(Exception.class, () -> publisher.publish(config, export, missing, "reject missing pin"));
        assertNoCachedCredentials(missing);
        config.certificateSha256 = required("probeloom.svn.certificateSha256");
        Path accepted = directory.resolve("correct-pin");
        publisher.publish(config, export, accepted, "accept correct pin");
        assertNoCachedCredentials(accepted);
        config.certificateSha256 = String.join("", Collections.nCopies(64, "0"));
        Path rejected = directory.resolve("wrong-pin");
        assertThrows(Exception.class, () -> publisher.publish(config, export, rejected, "reject wrong pin"));
        assertNoCachedCredentials(rejected);
    }

    @Test
    void usesJvmTrustRootsAndStillRejectsWrongExplicitPin() throws Exception {
        String[] properties = {"javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword", "javax.net.ssl.trustStoreType"};
        String[] original = new String[properties.length];
        for (int i = 0; i < properties.length; i++) original[i] = System.getProperty(properties[i]);
        try {
            Config config = SvnTestFixture.repository(required("probeloom.svn.httpUrl"), directory);
        config.repositoryUrl = config.repositoryUrl.replace(required("probeloom.svn.httpUrl"), required("probeloom.svn.httpsUrl"));
            Path export = export();
            CaseBackupPublisher publisher = new CaseBackupPublisher();
            System.setProperty(properties[0], directory.resolve("absent-truststore.jks").toString());
            System.setProperty(properties[1], "fixture-only");
            System.setProperty(properties[2], "JKS");
            assertThrows(Exception.class, () -> publisher.publish(config, export, directory.resolve("untrusted-root"), "untrusted JVM root"));
            System.setProperty(properties[0], required("probeloom.svn.trustStore"));
            System.setProperty(properties[1], required("probeloom.svn.trustStorePassword"));
            Path trusted = directory.resolve("trusted-root");
            publisher.publish(config, export, trusted, "JVM trusted root");
            assertNoCachedCredentials(trusted);
            config.certificateSha256 = required("probeloom.svn.certificateSha256");
            publisher.publish(config, export, directory.resolve("trusted-correct-pin"), "trusted correct pin");
            config.certificateSha256 = String.join("", Collections.nCopies(64, "0"));
            Path rejected = directory.resolve("trusted-wrong-pin");
            assertThrows(Exception.class, () -> publisher.publish(config, export, rejected, "trusted but wrong pin"));
            assertNoCachedCredentials(rejected);
        } finally {
            for (int i = 0; i < properties.length; i++) {
                if (original[i] == null) System.clearProperty(properties[i]);
                else System.setProperty(properties[i], original[i]);
            }
        }
    }

    private Config config(String url) {
        Config config = new Config();
        config.repositoryUrl = url;
        config.username = required("probeloom.svn.username");
        config.password = required("probeloom.svn.password");
        return config;
    }
    private Path export() throws Exception {
        Path export = Files.createDirectory(directory.resolve("export"));
        Files.write(export.resolve("sample.xlsx"), "real SVN HTTP sample".getBytes(StandardCharsets.UTF_8));
        return export;
    }
    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("SVN HTTP integration requires -D" + property);
        return value;
    }
    private static void assertNoCachedCredentials(Path work) throws Exception {
        Path auth = work.resolve("svn-config/auth");
        if (Files.exists(auth)) {
            try (Stream<Path> files = Files.walk(auth)) {
                assertFalse(files.filter(Files::isRegularFile).anyMatch(path ->
                        path.toString().contains("svn.simple") || path.toString().contains("svn.username")
                        || path.toString().contains("svn.ssl.client-passphrase")),
                        "SVN passwords and usernames must not be cached on disk");
            }
        }
    }
}
