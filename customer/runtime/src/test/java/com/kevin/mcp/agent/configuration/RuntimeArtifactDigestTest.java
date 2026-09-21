package com.kevin.mcp.agent.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Runtime 产物摘要计算和部署期校验规则。
 *
 * @author Kevin
 * @date 2026-09-18
 */
class RuntimeArtifactDigestTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void shouldCalculateSha256ForPackagedFile() throws Exception {
        Path jar = temporaryDirectory.resolve("runtime.jar");
        Files.writeString(jar, "runtime-artifact");

        String expected = "sha256:" + hex(MessageDigest.getInstance("SHA-256")
                .digest("runtime-artifact".getBytes(StandardCharsets.UTF_8)));

        assertThat(RuntimeArtifactDigest.resolve(jar, "")).isEqualTo(expected);
    }

    @Test
    void shouldRejectConfiguredDigestMismatch() throws Exception {
        Path jar = temporaryDirectory.resolve("runtime.jar");
        Files.writeString(jar, "runtime-artifact");

        assertThatThrownBy(() -> RuntimeArtifactDigest.resolve(jar, "sha256:" + "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void shouldCalculateDigestForExplodedRuntimeWithoutConfiguration() throws Exception {
        Path classes = Files.createDirectory(temporaryDirectory.resolve("runtime-classes"));
        Files.writeString(classes.resolve("Runtime.class"), "runtime-bytecode");

        assertThat(RuntimeArtifactDigest.resolve(classes, ""))
                .matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void shouldChangeExplodedDigestWhenCompiledContentChanges() throws Exception {
        Path classes = Files.createDirectory(temporaryDirectory.resolve("runtime-classes"));
        Path classFile = classes.resolve("Runtime.class");
        Files.writeString(classFile, "runtime-bytecode-v1");
        String first = RuntimeArtifactDigest.resolve(classes, "");

        Files.writeString(classFile, "runtime-bytecode-v2");

        assertThat(RuntimeArtifactDigest.resolve(classes, "")).isNotEqualTo(first);
    }

    @Test
    void shouldIncludeEveryExplodedClasspathDirectory() throws Exception {
        Path appClasses = Files.createDirectory(temporaryDirectory.resolve("app-classes"));
        Path runtimeClasses = Files.createDirectory(temporaryDirectory.resolve("runtime-classes"));
        Files.writeString(appClasses.resolve("CustomerTool.class"), "customer-tool-v1");
        Files.writeString(runtimeClasses.resolve("Runtime.class"), "runtime-bytecode");
        String first = RuntimeArtifactDigest.resolveDirectories(List.of(appClasses, runtimeClasses), "");

        Files.writeString(appClasses.resolve("CustomerTool.class"), "customer-tool-v2");

        assertThat(RuntimeArtifactDigest.resolveDirectories(List.of(appClasses, runtimeClasses), ""))
                .isNotEqualTo(first);
    }

    @Test
    void shouldRejectInvalidConfiguredDigest() throws Exception {
        Path jar = temporaryDirectory.resolve("runtime.jar");
        Files.writeString(jar, "runtime-artifact");

        assertThatThrownBy(() -> RuntimeArtifactDigest.resolve(jar, "首次部署"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sha256:<64 lowercase hex>");
    }

    @Test
    void shouldAcceptMatchingConfiguredDigest() throws Exception {
        Path jar = temporaryDirectory.resolve("runtime.jar");
        Files.writeString(jar, "runtime-artifact");
        String expected = "sha256:" + hex(MessageDigest.getInstance("SHA-256")
                .digest("runtime-artifact".getBytes(StandardCharsets.UTF_8)));

        assertThat(RuntimeArtifactDigest.resolve(jar, expected)).isEqualTo(expected);
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
