package dev.probeloom.service;

import dev.probeloom.model.CaseBackupModels.Config;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 隔离 SVN 配置，通过标准输入传递密码；随包客户端执行单次 SVN 操作。 */
final class SvnCli {
    private final Config config;
    private final Path work;
    private final Path configuration;

    SvnCli(Config config, Path work) throws Exception {
        this.config = config;
        this.work = Files.createDirectories(work.toAbsolutePath());
        this.configuration = Files.createDirectories(this.work.resolve("svn-config"));
        if (config.password != null && (config.password.contains("\n") || config.password.contains("\r"))) {
            throw new IllegalArgumentException("SVN 标准输入密码不能包含换行");
        }
        Files.write(configuration.resolve("config"), ("[auth]\npassword-stores =\nstore-auth-creds = no\n"
                + "store-passwords = no\nstore-plaintext-passwords = no\n").getBytes(StandardCharsets.UTF_8));
        List<String> authorities = new ArrayList<String>();
        if ("https".equalsIgnoreCase(URI.create(config.repositoryUrl).getScheme())) {
            if (config.certificateSha256.isEmpty()) {
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init((KeyStore) null);
                for (TrustManager manager : factory.getTrustManagers()) {
                    if (manager instanceof X509TrustManager) {
                        for (X509Certificate certificate : ((X509TrustManager) manager).getAcceptedIssuers()) {
                            Path file = configuration.resolve("ca-" + authorities.size() + ".pem");
                            String pem = "-----BEGIN CERTIFICATE-----\n"
                                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded())
                                    + "\n-----END CERTIFICATE-----\n";
                            Files.write(file, pem.getBytes(StandardCharsets.US_ASCII));
                            authorities.add(file.toString());
                        }
                    }
                }
            } else {
                pinCertificate();
            }
        }
        Files.write(configuration.resolve("servers"), ("[global]\nhttp-timeout = 120\n"
                + "store-auth-creds = no\nstore-passwords = no\nstore-plaintext-passwords = no\n"
                + "ssl-trust-default-ca = no\nssl-authority-files = " + String.join(";", authorities) + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    byte[] run(String tool, List<String> arguments) throws Exception {
        Path output = Files.createTempFile(work, "svn-output-", ".tmp");
        try {
            execute(tool, arguments, output);
            return Files.readAllBytes(output);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /** 固定版本读取原始文件，避免 export 的关键字、换行和符号链接转换。 */
    void snapshot(String url, long revision, Path target) throws Exception {
        target = target.toAbsolutePath().normalize();
        Files.createDirectories(target);
        Document listing = xml(run("svn", Arrays.asList("list", "--xml", "--depth", "infinity", "-r",
                Long.toString(revision), url + "@" + revision)));
        NodeList entries = listing.getElementsByTagName("entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            String name = entry.getElementsByTagName("name").item(0).getTextContent();
            if (name.contains("\\") || name.contains("\n") || name.contains("\r")) {
                throw new IllegalArgumentException("SVN 案例路径不能包含反斜杠或换行");
            }
            Path destination = target.resolve(name).normalize();
            if (!destination.startsWith(target)) throw new IllegalArgumentException("SVN 目录项超出 cases 范围");
            if ("dir".equals(entry.getAttribute("kind"))) Files.createDirectories(destination);
            else {
                Files.createDirectories(destination.getParent());
                execute("svn", Arrays.asList("cat", "--ignore-keywords", "-r", Long.toString(revision),
                        append(url, name) + "@" + revision), destination);
            }
        }
    }

    private void execute(String tool, List<String> arguments, Path output) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String executable = windows ? Paths.get(System.getProperty("probeloom.home", System.getProperty("user.dir")),
                "native", "windows-x86_64", "bin", tool + ".exe").toAbsolutePath().toString() : tool;
        if (windows && !Files.isRegularFile(Paths.get(executable))) {
            throw new IllegalStateException("SVN 备份客户端缺失，请使用完整发行包及其启动脚本：" + tool);
        }
        List<String> command = new ArrayList<String>();
        command.add(executable);
        command.addAll(arguments);
        command.addAll(Arrays.asList("--non-interactive", "--no-auth-cache", "--config-dir", configuration.toString(),
                "--password-from-stdin"));
        if (!config.username.isEmpty()) command.addAll(Arrays.asList("--username", config.username));
        Path error = Files.createTempFile(work, "svn-error-", ".tmp");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile())
                    .redirectOutput(output.toFile()).redirectError(error.toFile());
            builder.environment().put("LC_ALL", "C.UTF-8");
            builder.environment().put("LANG", "C.UTF-8");
            builder.environment().put("LANGUAGE", "C");
            process = builder.start();
            try (OutputStream input = process.getOutputStream()) {
                input.write(((config.password == null ? "" : config.password) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("SVN 操作超时（120 秒）");
            }
            if (process.exitValue() != 0) {
                String failure = new String(Files.readAllBytes(error), StandardCharsets.UTF_8).trim();
                if (config.password != null && !config.password.isEmpty()) failure = failure.replace(config.password, "***");
                throw new IllegalStateException("SVN 操作失败（退出码 " + process.exitValue() + "）：" + failure);
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
            Files.deleteIfExists(error);
        }
    }

    private void pinCertificate() throws Exception {
        URI uri = URI.create(config.repositoryUrl);
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        final X509Certificate[] matched = new X509Certificate[1];
        X509TrustManager trust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("不接受客户端证书认证");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    if (!hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())).equalsIgnoreCase(config.certificateSha256)) {
                        throw new CertificateException("SVN 服务器证书 SHA-256 指纹不匹配");
                    }
                    matched[0] = chain[0];
                } catch (CertificateException e) { throw e; }
                catch (Exception e) { throw new CertificateException(e); }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trust}, null);
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), 120000);
            socket.setSoTimeout(120000);
            socket.startHandshake();
        }
        String realm = "https://" + uri.getHost() + ":" + port;
        Path cache = Files.createDirectories(configuration.resolve("auth/svn.ssl.server"));
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        // 仅缓存匹配指纹的公开证书；SVN 在实际连接时再次按证书字节匹配。
        authField(content, "ascii_cert", Base64.getEncoder().encodeToString(matched[0].getEncoded()));
        authField(content, "failures", "1073741839");
        authField(content, "svn:realmstring", realm);
        content.write("END\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(cache.resolve(hex(MessageDigest.getInstance("MD5").digest(realm.getBytes(StandardCharsets.UTF_8)))), content.toByteArray());
    }

    private static void authField(OutputStream output, String key, String value) throws Exception {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(("K " + keyBytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        output.write(keyBytes);
        output.write(("\nV " + valueBytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        output.write(valueBytes);
        output.write('\n');
    }

    static Document xml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    static String append(String url, String path) throws Exception {
        return url.replaceAll("/$", "") + "/" + new URI(null, null, "/" + path, null).getRawPath().substring(1);
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value) result.append(String.format("%02x", item & 255));
        return result.toString();
    }
}
