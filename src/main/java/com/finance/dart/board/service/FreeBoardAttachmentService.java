package com.finance.dart.board.service;

import com.finance.dart.board.dto.FreeBoardAttachmentDto;
import com.finance.dart.board.entity.FreeBoard;
import com.finance.dart.board.entity.FreeBoardAttachment;
import com.finance.dart.board.repository.FreeBoardAttachmentRepository;
import com.finance.dart.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Service
public class FreeBoardAttachmentService {

    private final FreeBoardAttachmentRepository attachmentRepository;
    private final FileStorageService fileStorageService;

    private static final int MAX_FILE_COUNT = 5;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    // 허용 MIME 타입 화이트리스트
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            // 이미지
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/svg+xml",
            // PDF
            "application/pdf",
            // Office
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            // 텍스트
            "text/plain", "text/csv",
            // 압축
            "application/zip", "application/x-7z-compressed", "application/x-rar-compressed",
            "application/vnd.rar"
    );

    // 차단 확장자 블랙리스트
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "bat", "cmd", "sh", "ps1", "vbs", "js", "jar", "war", "class", "dll", "so", "msi"
    );

    /**
     * 첨부파일 저장
     */
    @Transactional
    public void saveAttachments(FreeBoard board, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return;

        // 기존 첨부파일 수 + 새 파일 수 체크
        int existingCount = attachmentRepository.findByFreeBoardId(board.getId()).size();
        if (existingCount + files.size() > MAX_FILE_COUNT) {
            throw new BizException("첨부파일은 최대 " + MAX_FILE_COUNT + "개까지 가능합니다.");
        }

        for (MultipartFile file : files) {
            validateFile(file);

            String storedFilename = fileStorageService.storeFile(file);

            FreeBoardAttachment attachment = new FreeBoardAttachment();
            attachment.setFreeBoard(board);
            attachment.setOriginalFilename(file.getOriginalFilename());
            attachment.setStoredFilename(storedFilename);
            attachment.setFileSize(file.getSize());
            attachment.setContentType(file.getContentType());

            attachmentRepository.save(attachment);
        }
    }

    /**
     * 파일 검증 (크기, MIME 타입, 확장자)
     */
    private void validateFile(MultipartFile file) {
        // 크기 체크
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BizException("파일 크기는 10MB 이하만 가능합니다: " + file.getOriginalFilename());
        }

        // MIME 타입 체크
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BizException("허용되지 않는 파일 형식입니다: " + file.getOriginalFilename());
        }

        // 확장자 블랙리스트 체크
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && originalFilename.contains(".")) {
            String extension = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
            if (BLOCKED_EXTENSIONS.contains(extension)) {
                throw new BizException("허용되지 않는 파일 확장자입니다: " + extension);
            }
        }
    }

    /**
     * 선택된 첨부파일 삭제 (수정 시)
     */
    @Transactional
    public void deleteAttachmentsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;

        for (Long id : ids) {
            attachmentRepository.findById(id).ifPresent(attachment -> {
                fileStorageService.deleteFile(attachment.getStoredFilename());
                attachmentRepository.delete(attachment);
            });
        }
    }

    /**
     * 게시글의 모든 첨부파일 물리 삭제
     */
    @Transactional
    public void deleteAllAttachments(Long boardId) {
        List<FreeBoardAttachment> attachments = attachmentRepository.findByFreeBoardId(boardId);
        for (FreeBoardAttachment attachment : attachments) {
            fileStorageService.deleteFile(attachment.getStoredFilename());
        }
    }

    /**
     * 첨부파일 목록 DTO 반환
     */
    public List<FreeBoardAttachmentDto> getAttachments(Long boardId) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return attachmentRepository.findByFreeBoardId(boardId).stream()
                .map(attachment -> {
                    FreeBoardAttachmentDto dto = new FreeBoardAttachmentDto();
                    dto.setId(attachment.getId());
                    dto.setOriginalFilename(attachment.getOriginalFilename());
                    dto.setFileSize(attachment.getFileSize());
                    dto.setContentType(attachment.getContentType());
                    dto.setCreatedAt(attachment.getCreatedAt().format(formatter));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    /**
     * 다운로드용 엔티티 조회
     */
    public FreeBoardAttachment getAttachmentEntity(Long attachmentId) {
        return attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BizException("첨부파일을 찾을 수 없습니다."));
    }

}
