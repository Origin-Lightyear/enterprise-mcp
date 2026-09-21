package com.kevin.mcp.agent.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 解析当前 Runtime 实际运行产物的 SHA-256 摘要，并校验外部期望值。
 *
 * @author Kevin
 * @date 2026-09-18
 */
@Component
public final class RuntimeArtifactDigest {

    private static final String DIGEST_PREFIX = "sha256:";
    private static final int BUFFER_SIZE = 8192;
    private static final int SHA256_HEX_LENGTH = 64;
    private static final Logger log = LoggerFactory.getLogger(RuntimeArtifactDigest.class);

    private final String value;

    /**
     * 根据 Runtime 身份配置解析当前加载位置。
     *
     * @param properties Runtime 身份及可选摘要期望值
     */
    @Autowired
    public RuntimeArtifactDigest(RuntimeIdentityProperties properties) {
        this(resolveRuntime(properties.getArtifactDigest()));
        log.info("Runtime artifact digest resolved: artifactDigest={}, expectedDigestConfigured={}",
                value, properties.getArtifactDigest() != null && !properties.getArtifactDigest().isBlank());
    }

    private RuntimeArtifactDigest(String value) {
        this.value = value;
    }

    RuntimeArtifactDigest(Path codeSource, String expectedDigest) {
        this.value = resolve(codeSource, expectedDigest);
    }

    /**
     * 返回已校验的实际产物摘要。
     *
     * @return {@code sha256:<64 位小写十六进制>} 摘要
     */
    public String getValue() {
        return value;
    }

    private static String resolveRuntime(String expectedDigest) {
        Path codeSource = resolveCodeSource();
        if (Files.isDirectory(codeSource)) {
            return resolveDirectories(classpathDirectories(codeSource), expectedDigest);
        }
        return resolve(codeSource, expectedDigest);
    }

    private static Path resolveCodeSource() {
        java.io.File source = new ApplicationHome(RuntimeArtifactDigest.class).getSource();
        if (source != null) {
            return source.toPath();
        }
        // ApplicationHome 在 JUnit 调用栈中会主动隐藏 source，需回退到 classes 目录以验证 exploded 模式。
        CodeSource codeSource = RuntimeArtifactDigest.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IllegalStateException("Cannot locate the running Runtime artifact");
        }
        try {
            return Path.of(codeSource.getLocation().toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Cannot resolve the running Runtime artifact location", exception);
        }
    }

    static String resolve(Path codeSource, String expectedDigest) {
        if (Files.isRegularFile(codeSource)) {
            String actual = digestFile(codeSource);
            return validateExpected(actual, expectedDigest);
        }
        if (Files.isDirectory(codeSource)) {
            return resolveDirectories(List.of(codeSource), expectedDigest);
        }
        throw new IllegalStateException("Runtime artifact location is neither a JAR nor a directory: " + codeSource);
    }

    static String resolveDirectories(List<Path> directories, String expectedDigest) {
        if (directories == null || directories.isEmpty()) {
            throw new IllegalStateException("Exploded Runtime classpath does not contain a compiled output directory");
        }
        List<String> directoryDigests = directories.stream()
                .map(RuntimeArtifactDigest::digestDirectory)
                .sorted()
                .toList();
        MessageDigest aggregate = newDigest();
        for (String directoryDigest : directoryDigests) {
            updateFramed(aggregate, directoryDigest.getBytes(StandardCharsets.UTF_8));
        }
        String actual = DIGEST_PREFIX + HexFormat.of().formatHex(aggregate.digest());
        return validateExpected(actual, expectedDigest);
    }

    private static List<Path> classpathDirectories(Path codeSource) {
        Set<Path> directories = new LinkedHashSet<>();
        directories.add(codeSource.toAbsolutePath().normalize());
        addClasspathDirectories(directories, System.getProperty("java.class.path", ""));
        addClasspathDirectories(directories, System.getProperty("surefire.test.class.path", ""));
        return List.copyOf(directories);
    }

    private static void addClasspathDirectories(Set<Path> directories, String classpath) {
        if (classpath == null || classpath.isBlank()) {
            return;
        }
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(entry).toAbsolutePath().normalize();
                if (Files.isDirectory(path)) {
                    directories.add(path);
                }
            } catch (InvalidPathException ignored) {
                // 非文件系统 classpath 条目无法参与 exploded 内容摘要，交由其余有效目录形成开发摘要。
            }
        }
    }

    private static String digestDirectory(Path directory) {
        MessageDigest digest = newDigest();
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
            files.sort(Comparator.comparing(file -> normalizedRelativePath(directory, file)));
            for (Path file : files) {
                updateFramed(digest, normalizedRelativePath(directory, file).getBytes(StandardCharsets.UTF_8));
                updateFile(digest, file);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot calculate exploded Runtime SHA-256", exception);
        }
    }

    private static String normalizedRelativePath(Path directory, Path file) {
        return directory.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private static void updateFile(MessageDigest digest, Path file) throws IOException {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(Files.size(file)).array());
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
    }

    private static void updateFramed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private static String validateExpected(String actual, String expectedDigest) {
        String expected = normalizeExpected(expectedDigest);
        if (!expected.isEmpty() && !expected.equals(actual)) {
            throw new IllegalStateException("Configured artifact digest does not match the running artifact: expected "
                    + expected + ", actual " + actual);
        }
        return actual;
    }

    private static String normalizeExpected(String expectedDigest) {
        String normalized = expectedDigest == null ? "" : expectedDigest.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (!isValid(normalized)) {
            throw new IllegalStateException("Configured artifact digest must match sha256:<64 lowercase hex>");
        }
        return normalized;
    }

    private static boolean isValid(String digest) {
        if (!digest.startsWith(DIGEST_PREFIX)
                || digest.length() != DIGEST_PREFIX.length() + SHA256_HEX_LENGTH) {
            return false;
        }
        return digest.substring(DIGEST_PREFIX.length()).chars()
                .allMatch(character -> (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'));
    }

    private static String digestFile(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = newDigest();
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return DIGEST_PREFIX + HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot calculate the running JAR SHA-256", exception);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
