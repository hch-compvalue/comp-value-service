package com.finance.dart.board.controller;

import com.finance.dart.board.entity.FreeBoardAttachment;
import com.finance.dart.board.service.FileStorageService;
import com.finance.dart.board.service.FreeBoardAttachmentService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@AllArgsConstructor
@RequestMapping("freeboard")
@RestController
public class FreeBoardAttachmentController {

    private final FreeBoardAttachmentService attachmentService;
    private final FileStorageService fileStorageService;

    /**
     * 파일 다운로드
     */
    @GetMapping("/attachment/{attachmentId}/download")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable("attachmentId") Long attachmentId) {
        FreeBoardAttachment attachment = attachmentService.getAttachmentEntity(attachmentId);
        Resource resource = fileStorageService.loadFile(attachment.getStoredFilename());

        // 한글 파일명 UTF-8 인코딩
        String encodedFilename = URLEncoder.encode(attachment.getOriginalFilename(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        String contentDisposition = "attachment; filename*=UTF-8''" + encodedFilename;

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(resource);
    }

}
