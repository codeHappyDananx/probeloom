package dev.probeloom.service;

import dev.probeloom.model.CaseBackupModels.Config;
import dev.probeloom.model.CaseBackupModels.Result;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 只同步仓库的 cases 目录；SVN 单事务提交，Git 使用普通快进推送。 */
@Service
public class CaseBackupPublisher {
    public Result publish(Config config, Path export, Path work, String message) throws Exception {
        return "SVN".equals(config.repositoryType) ? publishSvn(config, export, work, message) : publishGit(config, export, work, message);
    }

    private Result publishSvn(Config config, Path export, Path work, String message) throws Exception {
        /*
         * ========================================================================
         * 步骤1：隔离客户端配置并冻结仓库读取版本
         * ========================================================================
         * 1) 认证只经标准输入传给随包客户端，不写入认证缓存。
         * 2) 固定 HEAD 版本，后续读取和提交都使用同一基线。
         */
        SvnCli cli = new SvnCli(config, work);
        Document info = SvnCli.xml(cli.run("svn", Arrays.asList("info", "--xml", config.repositoryUrl + "@HEAD")));
        Element entry = (Element) info.getElementsByTagName("entry").item(0);
        if (!"dir".equals(entry.getAttribute("kind"))) throw new IllegalArgumentException("SVN 仓库地址必须指向目录");
        long revision = Long.parseLong(entry.getAttribute("revision"));
        String url = entry.getElementsByTagName("url").item(0).getTextContent();
        String casesUrl = SvnCli.append(url, "cases");
        /*
         * ========================================================================
         * 步骤2：读取 cases 原始内容并与导出结果比较
         * ========================================================================
         * 1) 只读取固定版本下的 cases，其他目录及属性不参与覆盖。
         * 2) 目录结构和 SHA-256 一致时不创建提交。
         */
        Document listing = SvnCli.xml(cli.run("svn", Arrays.asList("list", "--xml", "-r", Long.toString(revision), url + "@" + revision)));
        NodeList entries = listing.getElementsByTagName("entry");
        Path before = null;
        for (int i = 0; i < entries.getLength(); i++) {
            Element child = (Element) entries.item(i);
            if ("cases".equals(child.getElementsByTagName("name").item(0).getTextContent())) {
                if (!"dir".equals(child.getAttribute("kind"))) throw new IllegalStateException("仓库中的 cases 必须是目录");
                before = work.toAbsolutePath().resolve("before");
                cli.snapshot(casesUrl, revision, before);
            }
        }
        return syncSvn(cli, casesUrl, revision, before, export, message);
    }

    Result syncSvn(SvnCli cli, String casesUrl, long revision, Path before, Path export, String message) throws Exception {
        Node previous = before == null ? null : readLocal(before);
        Node current = readLocal(export);
        if (same(previous, current)) return new Result(false, "r" + revision);
        /*
         * ========================================================================
         * 步骤3：单事务提交 cases 差异
         * ========================================================================
         * 1) 动作文件只包含变化项；更新文件保留已有属性。
         * 2) svnmucc 携带基线版本，冲突由服务端拒绝，不产生部分提交。
         */
        List<String> actions = new ArrayList<String>();
        svnActions(actions, casesUrl, previous, current);
        Path actionFile = Files.createTempFile(export.getParent(), "svn-actions-", ".txt");
        try {
            for (String action : actions) {
                if (action.contains("\n") || action.contains("\r")) throw new IllegalArgumentException("SVN 动作参数不能包含换行");
            }
            Files.write(actionFile, actions, StandardCharsets.UTF_8);
            String output = new String(cli.run("svnmucc", Arrays.asList("-r", Long.toString(revision), "-m", message,
                    "--extra-args", actionFile.toAbsolutePath().toString())), StandardCharsets.UTF_8);
            Matcher committed = Pattern.compile("(?m)^r([0-9]+) committed by ").matcher(output);
            if (!committed.find()) throw new IllegalStateException("SVN 提交未返回版本号");
            return new Result(true, "r" + committed.group(1));
        } finally {
            Files.deleteIfExists(actionFile);
        }
    }

    private void svnActions(List<String> actions, String url, Node before, Node after) throws Exception {
        if (before != null && before.directory != after.directory) {
            actions.addAll(Arrays.asList("rm", url));
            before = null;
        }
        if (after.directory) {
            if (before == null) actions.addAll(Arrays.asList("mkdir", url));
            if (before != null) {
                for (String name : before.children.keySet()) {
                    if (!after.children.containsKey(name)) actions.addAll(Arrays.asList("rm", SvnCli.append(url, name)));
                }
            }
            for (Map.Entry<String, Node> child : after.children.entrySet()) {
                Node old = before == null ? null : before.children.get(child.getKey());
                if (!same(old, child.getValue())) svnActions(actions, SvnCli.append(url, child.getKey()), old, child.getValue());
            }
        } else {
            actions.addAll(Arrays.asList("put", after.file.toAbsolutePath().toString(), url));
            if (before == null) actions.addAll(Arrays.asList("propset", "svn:mime-type",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", url));
        }
    }

    private Node readLocal(Path path) throws Exception {
        Node node = new Node(Files.isDirectory(path));
        node.file = path;
        if (node.directory) {
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(path)) {
                for (Path child : paths) node.children.put(child.getFileName().toString(), readLocal(child));
            }
        } else {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            node.hash = hex(digest.digest());
        }
        return node;
    }

    private boolean same(Node before, Node after) {
        if (before == null || before.directory != after.directory) return false;
        if (!after.directory) return before.hash.equals(after.hash);
        if (!before.children.keySet().equals(after.children.keySet())) return false;
        for (String name : after.children.keySet()) if (!same(before.children.get(name), after.children.get(name))) return false;
        return true;
    }

    private Result publishGit(Config config, Path export, Path work, String message) throws Exception {
        UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(config.username, config.password == null ? "" : config.password);
        Collection<Ref> heads = Git.lsRemoteRepository().setRemote(config.repositoryUrl).setHeads(true).setTimeout(120).setCredentialsProvider(credentials).call();
        String branch = Constants.R_HEADS + config.branch;
        if (!heads.isEmpty() && heads.stream().noneMatch(ref -> branch.equals(ref.getName()))) throw new IllegalArgumentException("Git 分支不存在：" + config.branch);
        CloneCommand clone = Git.cloneRepository().setURI(config.repositoryUrl).setDirectory(work.toFile()).setCloneAllBranches(false).setTimeout(120).setCredentialsProvider(credentials);
        if (!heads.isEmpty()) clone.setBranch(branch);
        try (Git git = clone.call()) {
            if (heads.isEmpty()) git.getRepository().updateRef(Constants.HEAD, true).link(branch);
            Path target = work.resolve("cases");
            if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> paths = Files.walk(target)) {
                    if (paths.anyMatch(Files::isSymbolicLink)) throw new IllegalArgumentException("Git 仓库的 cases 目录不能包含符号链接");
                }
                deleteTree(target);
            }
            try (Stream<Path> paths = Files.walk(export)) {
                for (Path source : (Iterable<Path>) paths::iterator) {
                    Path destination = target.resolve(export.relativize(source));
                    if (Files.isDirectory(source)) Files.createDirectories(destination);
                    else Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            git.add().addFilepattern("cases").call();
            git.add().addFilepattern("cases").setUpdate(true).call();
            if (git.status().call().isClean()) {
                return new Result(false, git.getRepository().resolve(Constants.HEAD) == null ? null : git.getRepository().resolve(Constants.HEAD).name());
            }
            String commit = git.commit().setMessage(message).setAuthor("Test Platform", "test-platform@localhost").setCommitter("Test Platform", "test-platform@localhost").call().name();
            for (PushResult push : git.push().setRemote("origin").setRefSpecs(new RefSpec(branch + ":" + branch)).setTimeout(120).setCredentialsProvider(credentials).call()) {
                for (RemoteRefUpdate update : push.getRemoteUpdates()) {
                    if (update.getStatus() != RemoteRefUpdate.Status.OK && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                        throw new IllegalStateException("Git 推送失败：" + update.getStatus() + " " + update.getMessage());
                    }
                }
            }
            return new Result(true, commit);
        }
    }

    public void deleteTree(Path path) throws java.io.IOException {
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(item);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value) result.append(String.format("%02x", item & 255));
        return result.toString();
    }

    private static class Node {
        final boolean directory;
        final Map<String, Node> children = new TreeMap<String, Node>();
        Path file;
        String hash;
        Node(boolean directory) { this.directory = directory; }
    }
}
