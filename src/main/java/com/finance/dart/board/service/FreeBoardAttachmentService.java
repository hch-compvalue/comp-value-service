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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
     * 새 첨부파일 일괄 검증 (개수·크기·MIME·확장자)
     * - 게시글 저장/기존 첨부 삭제 전에 호출해, 첨부가 거부되면 게시글도 저장되지 않게 한다
     *
     * @param existingCount 이 요청 처리 후에도 남아 있을 기존 첨부 수
     */
    public void validateFiles(int existingCount, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return;

        if (existingCount + files.size() > MAX_FILE_COUNT) {
            throw new BizException("첨부파일은 최대 " + MAX_FILE_COUNT + "개까지 가능합니다.");
        }
        files.forEach(this::validateFile);
    }

    /**
     * 수정 시 삭제 대상을 뺀 남은 기존 첨부 수 (해당 게시글 첨부만 대상)
     */
    public int countRemainingAttachments(Long boardId, List<Long> deleteIds) {
        List<FreeBoardAttachment> existing = attachmentRepository.findByFreeBoardId(boardId);
        if (deleteIds == null || deleteIds.isEmpty()) return existing.size();
        return (int) existing.stream().filter(a -> !deleteIds.contains(a.getId())).count();
    }

    /**
     * 첨부파일 저장
     * - 트랜잭션이 롤백되면 이번 요청에서 디스크에 저장한 파일도 지운다 (고아 파일 방지)
     */
    @Transactional
    public void saveAttachments(FreeBoard board, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return;

        // 기존 첨부파일 수 + 새 파일 수 체크 (호출부에서 미리 검증하지만 단독 호출 대비 유지)
        int existingCount = attachmentRepository.findByFreeBoardId(board.getId()).size();
        validateFiles(existingCount, files);

        List<String> storedFilenames = new ArrayList<>();
        deleteFilesOnRollback(storedFilenames);

        for (MultipartFile file : files) {
            String storedFilename = fileStorageService.storeFile(file);
            storedFilenames.add(storedFilename);

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
     * - 해당 게시글의 첨부만 삭제 (다른 게시글 첨부 id 가 넘어와도 무시)
     * - 디스크 파일은 커밋 후 삭제 → 수정이 실패해 롤백되면 파일이 그대로 남음
     */
    @Transactional
    public void deleteAttachmentsByIds(Long boardId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;

        List<String> storedFilenames = new ArrayList<>();
        for (Long id : ids) {
            attachmentRepository.findById(id)
                    .filter(attachment -> boardId.equals(attachment.getFreeBoard().getId()))
                    .ifPresent(attachment -> {
                        storedFilenames.add(attachment.getStoredFilename());
                        attachmentRepository.delete(attachment);
                    });
        }
        deleteFilesAfterCommit(storedFilenames);
    }

    /**
     * 게시글의 모든 첨부파일 물리 삭제 (DB 행은 게시글 cascade 로 삭제)
     * - 디스크 파일은 커밋 후 삭제 → 게시글 삭제가 실패하면 파일이 그대로 남음
     */
    @Transactional
    public void deleteAllAttachments(Long boardId) {
        List<String> storedFilenames = attachmentRepository.findByFreeBoardId(boardId).stream()
                .map(FreeBoardAttachment::getStoredFilename)
                .collect(Collectors.toList());
        deleteFilesAfterCommit(storedFilenames);
    }

    /** 커밋 성공 후 디스크 파일 삭제 (트랜잭션 밖에서 호출되면 즉시 삭제) */
    private void deleteFilesAfterCommit(List<String> storedFilenames) {
        if (storedFilenames.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            storedFilenames.forEach(fileStorageService::deleteFile);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storedFilenames.forEach(fileStorageService::deleteFile);
            }
        });
    }

    /** 롤백 시 디스크 파일 삭제 — 목록은 등록 후에 채워져도 됨 (롤백 시점의 목록 사용) */
    private void deleteFilesOnRollback(List<String> storedFilenames) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    storedFilenames.forEach(fileStorageService::deleteFile);
                }
            }
        });
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
