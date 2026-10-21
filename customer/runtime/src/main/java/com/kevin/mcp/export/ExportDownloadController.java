package com.kevin.mcp.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 导出文件一次性下载端点；短码即凭证，失败统一 404 防止短码探测。
 *
 * <p>响应使用流式写出，大文件不整段驻留内存；文件名通过 Content-Disposition
 * 传给客户端，Agent 执行层保存到本地 outputs 时可直接使用。</p>
 *
 * @author Kevin
 * @date 2026-10-19
 */
@RestController
@RequestMapping("/internal/k3-export")
public class ExportDownloadController {

    private final LargeResultExportService exportService;

    /**
     * 注入导出服务。
     *
     * @param exportService 大结果导出服务
     */
    public ExportDownloadController(LargeResultExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 下载一次导出文件；下载成功后短码立即失效。
     *
     * @param fileId 下载短码
     * @return CSV 文件流；短码无效时 404
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String fileId) {
        ExportSessionRegistry.ExportSession session;
        try {
            session = exportService.consume(fileId);
        } catch (LargeResultExportService.ExportNotFoundException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Path file = Path.of(session.filePath());
        if (!Files.exists(file)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String encodedName = session.fileName().replace("\"", "");
        StreamingResponseBody body = output -> {
            try (var input = Files.newInputStream(file)) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedName + "\"")
                .contentType(MediaType.valueOf("text/csv;charset=UTF-8"))
                .body(body);
    }
}