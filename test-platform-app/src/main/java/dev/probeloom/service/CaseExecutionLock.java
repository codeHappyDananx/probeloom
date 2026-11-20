package dev.probeloom.service;

import dev.probeloom.config.PlatformStorage;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 同机平台与监督器共用案例锁；进程退出由操作系统释放，锁文件不删除。 */
final class CaseExecutionLock implements AutoCloseable {
    private static final java.util.Set<Path> owned = new java.util.HashSet<Path>();
    private final FileChannel channel;
    private final FileLock lock;
    private final Path path;

    private CaseExecutionLock(FileChannel channel, FileLock lock, Path path) {
        this.channel = channel;
        this.lock = lock;
        this.path = path;
    }

    static synchronized CaseExecutionLock acquire(PlatformStorage storage, long executionId) throws IOException {
        Path directory = storage.getDataDirectory().resolve("execution-locks");
        Files.createDirectories(directory);
        Path path = directory.resolve(executionId + ".lock");
        // 同 JVM 不重复打开持锁文件，避免关闭另一通道时释放进程已有的文件锁。
        if (owned.contains(path)) return null;
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                owned.add(path);
                return new CaseExecutionLock(channel, lock, path);
            }
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
        channel.close();
        return null;
    }

    @Override
    public void close() throws IOException {
        synchronized (CaseExecutionLock.class) {
            try { lock.release(); }
            finally {
                try { channel.close(); }
                finally { owned.remove(path); }
            }
        }
    }
}
