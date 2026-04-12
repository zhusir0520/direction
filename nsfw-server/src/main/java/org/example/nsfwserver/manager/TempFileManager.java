package org.example.nsfwserver.manager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时文件管理器
 * 解决磁盘爆炸问题：实现幂等拷贝，避免每次重启重复创建临时文件
 */
@Component
public class TempFileManager {
    private static final Logger logger = LoggerFactory.getLogger(TempFileManager.class);

    // 统一临时目录：~/.nsfw-server/models/
    private Path appTempDir;

    // 文件哈希缓存（避免重复计算）
    private final Map<String, String> fileHashCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            // 创建应用专用临时目录
            String userHome = System.getProperty("user.home");
            appTempDir = Paths.get(userHome, ".nsfw-server", "models");

            if (!Files.exists(appTempDir)) {
                Files.createDirectories(appTempDir);
                logger.info("Created application temp directory: {}", appTempDir);
            } else {
                logger.info("Using existing application temp directory: {}", appTempDir);
            }

            // 可选：清理旧文件（例如超过24小时的）
            cleanupOldFiles();

        } catch (Exception e) {
            logger.error("Failed to initialize TempFileManager", e);
            // 如果创建失败，回退到系统临时目录
            appTempDir = Paths.get(System.getProperty("java.io.tmpdir"), "nsfw-server-models");
            try {
                Files.createDirectories(appTempDir);
                logger.warn("Falling back to system temp directory: {}", appTempDir);
            } catch (IOException ex) {
                logger.error("Failed to create fallback temp directory", ex);
                throw new RuntimeException("Cannot create temp directory", ex);
            }
        }
    }

    /**
     * 幂等拷贝：从类路径资源复制到临时文件
     * 如果目标文件已存在且哈希匹配，直接返回现有文件路径
     *
     * @param resourcePath 类路径资源路径（不带"classpath:"前缀）
     * @param modelName 模型名称（用于生成文件名）
     * @return 临时文件路径
     */
    public Path copyResourceToTemp(String resourcePath, String modelName) throws IOException {
        // 1. 计算源文件哈希（使用SHA-256）
        String sourceHash = calculateResourceHash(resourcePath);

        // 2. 目标文件名：modelName_hash.onnx（只取哈希前8位）
        String targetFileName = String.format("%s_%s.onnx", modelName, sourceHash.substring(0, 8));
        Path targetPath = appTempDir.resolve(targetFileName);

        logger.debug("Checking temp file for {}: {}", resourcePath, targetPath);

        // 3. 检查文件是否存在且哈希匹配
        if (Files.exists(targetPath)) {
            String existingHash = calculateFileHash(targetPath);
            if (existingHash.equals(sourceHash)) {
                logger.debug("Existing temp file is valid for {}: {}", resourcePath, targetPath);
                return targetPath; // 文件已存在且完整
            } else {
                logger.warn("Existing temp file hash mismatch for {}. Expected: {}, Actual: {}. Will overwrite.",
                    resourcePath, sourceHash.substring(0, 8), existingHash.substring(0, 8));
            }
        }

        // 4. 执行拷贝（带文件锁保护，防止并发写入）
        copyWithLock(resourcePath, targetPath, sourceHash);

        logger.info("Copied resource {} to temp file: {}", resourcePath, targetPath);
        return targetPath;
    }

    /**
     * 计算类路径资源的哈希值
     */
    private String calculateResourceHash(String resourcePath) throws IOException {
        // 检查缓存
        String cacheKey = "resource:" + resourcePath;
        if (fileHashCache.containsKey(cacheKey)) {
            return fileHashCache.get(cacheKey);
        }

        try (InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (resourceStream == null) {
                throw new IOException("Resource not found in classpath: " + resourcePath);
            }

            String hash = calculateStreamHash(resourceStream);
            fileHashCache.put(cacheKey, hash);
            return hash;
        }
    }

    /**
     * 计算文件哈希值
     */
    private String calculateFileHash(Path filePath) throws IOException {
        // 检查缓存
        String cacheKey = "file:" + filePath.toString();
        if (fileHashCache.containsKey(cacheKey)) {
            return fileHashCache.get(cacheKey);
        }

        try (InputStream fileStream = Files.newInputStream(filePath)) {
            String hash = calculateStreamHash(fileStream);
            fileHashCache.put(cacheKey, hash);
            return hash;
        }
    }

    /**
     * 计算输入流的SHA-256哈希
     */
    private String calculateStreamHash(InputStream inputStream) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }

            // 转换为十六进制字符串
            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 带锁保护的文件拷贝（防止并发写入同一个文件）
     */
    private synchronized void copyWithLock(String resourcePath, Path targetPath, String expectedHash) throws IOException {
        // 再次检查文件是否已存在（双重检查锁定）
        if (Files.exists(targetPath)) {
            String existingHash = calculateFileHash(targetPath);
            if (existingHash.equals(expectedHash)) {
                return; // 另一个线程已经完成了拷贝
            }
        }

        // 执行拷贝
        try (InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (resourceStream == null) {
                throw new IOException("Resource not found in classpath: " + resourcePath);
            }

            Files.copy(resourceStream, targetPath, StandardCopyOption.REPLACE_EXISTING);

            // 验证拷贝结果
            verifyFileHash(targetPath, expectedHash);
        }
    }

    /**
     * 验证文件哈希
     */
    private void verifyFileHash(Path filePath, String expectedHash) throws IOException {
        String actualHash = calculateFileHash(filePath);
        if (!actualHash.equals(expectedHash)) {
            // 删除损坏的文件
            Files.deleteIfExists(filePath);
            throw new IOException(String.format(
                "File hash verification failed for %s. Expected: %s, Actual: %s",
                filePath.getFileName(),
                expectedHash.substring(0, 8),
                actualHash.substring(0, 8)
            ));
        }
    }

    /**
     * 清理旧文件（例如超过24小时的）
     */
    private void cleanupOldFiles() {
        try {
            long cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000); // 24小时前
            int deletedCount = 0;

            if (Files.exists(appTempDir) && Files.isDirectory(appTempDir)) {
                try (java.util.stream.Stream<Path> stream = Files.list(appTempDir)) {
                    for (Path file : stream.toArray(Path[]::new)) {
                        try {
                            if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() < cutoffTime) {
                                Files.delete(file);
                                deletedCount++;
                                logger.debug("Deleted old temp file: {}", file.getFileName());
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to delete old temp file: {}", file, e);
                        }
                    }
                }
            }

            if (deletedCount > 0) {
                logger.info("Cleaned up {} old temp files from {}", deletedCount, appTempDir);
            }

        } catch (Exception e) {
            logger.warn("Failed to cleanup old temp files", e);
        }
    }

    /**
     * 获取临时目录路径
     */
    public Path getAppTempDir() {
        return appTempDir;
    }

    /**
     * 清除哈希缓存（主要用于测试）
     */
    public void clearHashCache() {
        fileHashCache.clear();
    }
}