package com.finance.dart.board.service;

import com.finance.dart.board.dto.FreeBoardDto;
import com.finance.dart.board.dto.FreeBoardListResponseDto;
import com.finance.dart.board.entity.FreeBoard;
import com.finance.dart.board.repository.FreeBoardRepository;
import com.finance.dart.board.util.ImageSizeValidator;
import com.finance.dart.common.exception.BizException;
import com.finance.dart.member.dto.Member;
import com.finance.dart.member.entity.MemberEntity;
import com.finance.dart.member.enums.RoleConstants;
import com.finance.dart.member.service.MemberService;
import com.finance.dart.member.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
public class FreeBoardService {

    private final FreeBoardRepository freeBoardRepository;
    private final MemberService memberService;
    private final SessionService sessionService;
    private final FreeBoardAttachmentService attachmentService;

    /**
     * Entity -> DTO 변환
     * @param board 게시글 엔티티
     * @param loginMemberId 로그인한 회원 ID (비밀글 체크용, null 가능)
     */
    private FreeBoardDto convertToDto(FreeBoard board, Long loginMemberId) {
        FreeBoardDto dto = new FreeBoardDto();
        dto.setId(board.getId());
        dto.setViewCount(board.getViewCount());
        dto.setNotice(board.isNotice());
        dto.setSecret(board.isSecret());

        // 비밀글 처리: 본인 글이 아니면 제목/내용 마스킹
        boolean isOwner = loginMemberId != null && board.getMember() != null
                          && loginMemberId.equals(board.getMember().getId());

        if (board.isSecret() && !isOwner) {
            dto.setTitle("🔒 비밀글입니다");
            dto.setContent("");
        } else {
            dto.setTitle(board.getTitle());
            dto.setContent(board.getContent());
        }

        // 작성자 정보
        MemberEntity member = board.getMember();
        if (member != null) {
            dto.setMemberId(member.getId());
            dto.setMemberUsername(member.getUsername());
            dto.setMemberNickname(member.getNickname());
        }

        // 날짜 포맷팅
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        dto.setCreatedAt(board.getCreatedAt().format(formatter));
        dto.setUpdatedAt(board.getUpdatedAt().format(formatter));

        // 첨부파일 목록
        dto.setAttachments(attachmentService.getAttachments(board.getId()));

        // 댓글 수
        dto.setCommentCount(board.getCommentCount() != null ? board.getCommentCount() : 0);

        return dto;
    }

    /**
     * 게시글 생성 — 첨부 검증 실패 시 게시글도 저장하지 않음 (첨부 저장 실패 시 함께 롤백)
     */
    @Transactional
    public FreeBoardDto createBoard(HttpServletRequest request, FreeBoardDto boardDto, List<MultipartFile> files) {
        // 로그인 회원 정보
        Member member = memberService.getLoginMember(request);
        if (member == null) {
            throw new BizException("로그인이 필요합니다.");
        }

        // 이미지 크기 검증
        ImageSizeValidator.validateImageSize(boardDto.getContent());

        // 첨부파일 검증 (게시글 저장 전)
        attachmentService.validateFiles(0, files);

        // 공지글 작성 권한 체크 (관리자만 가능)
        if (boardDto.isNotice()) {
            boolean isAdmin = sessionService.hasRole(request, RoleConstants.ROLE_ADMIN);
            boolean isSuperAdmin = sessionService.hasRole(request, RoleConstants.ROLE_SUPER_ADMIN);
            if (!isAdmin && !isSuperAdmin) {
                throw new BizException("공지글 작성 권한이 없습니다.");
            }
        }

        // 최소한의 정보만 가진 MemberEntity 구성 (id, username, nickname 정도만 사용)
        MemberEntity memberEntity = new MemberEntity();
        memberEntity.setId(member.getId());
        memberEntity.setUsername(member.getUsername());
        memberEntity.setNickname(member.getNickname());

        // DTO -> Entity 변환
        FreeBoard board = new FreeBoard();
        board.setTitle(boardDto.getTitle());
        board.setContent(boardDto.getContent());
        board.setMember(memberEntity);
        board.setNotice(boardDto.isNotice());
        board.setSecret(boardDto.isSecret());
        board.setCreatedAt(LocalDateTime.now());
        board.setUpdatedAt(LocalDateTime.now());

        FreeBoard savedBoard = freeBoardRepository.save(board);

        // 첨부파일 저장
        if (files != null && !files.isEmpty()) {
            attachmentService.saveAttachments(savedBoard, files);
        }

        // Entity -> DTO 변환하여 반환
        return convertToDto(savedBoard, member.getId());
    }

    /**
     * ID로 게시글 조회 (조회수 증가)
     */
    @Transactional
    public FreeBoardDto getBoardById(HttpServletRequest request, Long id) {
        Optional<FreeBoard> boardOpt = freeBoardRepository.findById(id);
        FreeBoard board = boardOpt.orElseThrow(() ->
                new BizException("게시글을 찾을 수 없습니다."));

        // 로그인 회원 정보 (비밀글 체크용)
        Member loginMember = null;
        try {
            loginMember = memberService.getLoginMember(request);
        } catch (Exception e) {
            // 로그인하지 않은 경우 loginMember는 null로 유지
        }

        Long loginMemberId = loginMember != null ? loginMember.getId() : null;

        // 비밀글인 경우 본인만 확인가능
        if (board.isSecret()) {
            boolean isOwner = loginMemberId != null && board.getMember() != null
                              && loginMemberId.equals(board.getMember().getId());
//            boolean isAdmin = loginMemberId != null && sessionService.hasRole(request, RoleConstants.ROLE_ADMIN);
//            boolean isSuperAdmin = loginMemberId != null && sessionService.hasRole(request, RoleConstants.ROLE_SUPER_ADMIN);

            if (!isOwner) {
                throw new BizException("비밀글은 작성자 본인만 조회할 수 있습니다.");
            }
        }

        // 조회수 증가
        board.setViewCount(board.getViewCount() + 1);
        freeBoardRepository.save(board);

        return convertToDto(board, loginMemberId);
    }

    /**
     * 모든 게시글 조회 (페이징, 검색)
     * - 공지글은 페이징 없이 전체 조회 (등록일 DESC)
     * - 일반글은 페이징 적용하여 조회
     */
    @Transactional(readOnly = true)
    public FreeBoardListResponseDto getAllBoards(HttpServletRequest request, Pageable pageable, String search, String sgubun) {

        // 로그인 회원 정보 (비밀글 마스킹용)
        Member loginMember = null;
        try {
            loginMember = memberService.getLoginMember(request);
        } catch (Exception e) {
            // 로그인하지 않은 경우 loginMember는 null로 유지
        }
        Long loginMemberId = loginMember != null ? loginMember.getId() : null;

        // 1. 공지글 전체 조회 (페이징 없음, 등록일 DESC)
        List<FreeBoard> noticeBoards = freeBoardRepository.findByNoticeTrue(
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        List<FreeBoardDto> notices = noticeBoards.stream()
                .map(board -> convertToDto(board, loginMemberId))
                .collect(Collectors.toList());

        // 2. 일반글 조회 (페이징 적용, notice = false)
        Page<FreeBoard> postsPage;

        // 검색 값 투입 (일반글만)
        if("1".equals(sgubun)) {          // 제목으로 검색
            postsPage = freeBoardRepository.findByNoticeFalseAndTitleContaining(search, pageable);
        } else if("2".equals(sgubun)) {   // 작성자로 검색
            postsPage = freeBoardRepository.findByNoticeFalseAndMember_NicknameContaining(search, pageable);
        } else if("3".equals(sgubun)) {   // 내용으로 검색
            postsPage = freeBoardRepository.findByNoticeFalseAndContentContaining(search, pageable);
        } else if("4".equals(sgubun)) {   // 제목, 내용으로 검색
            postsPage = freeBoardRepository.findByNoticeFalseAndTitleContainingOrNoticeFalseAndContentContaining(search, search, pageable);
        } else if("5".equals(sgubun)) {   // 아이디로 검색
            postsPage = freeBoardRepository.findByNoticeFalseAndMember_UsernameContaining(search, pageable);
        } else {    // 전체 조회 (일반글만)
            postsPage = freeBoardRepository.findByNoticeFalse(pageable);
        }

        // Entity -> DTO 변환 (비밀글 마스킹 처리 포함)
        List<FreeBoardDto> posts = postsPage.getContent().stream()
                .map(board -> convertToDto(board, loginMemberId))
                .collect(Collectors.toList());

        long totalPosts = postsPage.getTotalElements();

        // 응답 조립
        FreeBoardListResponseDto response = new FreeBoardListResponseDto();
        response.setNotices(notices);
        response.setPosts(posts);
        response.setTotalPosts(totalPosts);

        return response;
    }

    /**
     * 게시글 수정
     */
    @Transactional
    public FreeBoardDto updateBoard(HttpServletRequest request, Long id, FreeBoardDto boardDto,
                                        List<MultipartFile> files, List<Long> deleteAttachmentIds) {
        Optional<FreeBoard> boardOpt = freeBoardRepository.findById(id);
        FreeBoard board = boardOpt.orElseThrow(() ->
                new BizException("게시글을 찾을 수 없습니다."));

        // 로그인 회원 정보
        Member loginMember = memberService.getLoginMember(request);

        // 권한 체크
        validateUpdatePermission(request, loginMember, board);

        // 이미지 크기 검증
        ImageSizeValidator.validateImageSize(boardDto.getContent());

        // 공지글 수정 권한 체크 (관리자만 가능)
        if (boardDto.isNotice()) {
            boolean isAdmin = sessionService.hasRole(request, RoleConstants.ROLE_ADMIN);
            boolean isSuperAdmin = sessionService.hasRole(request, RoleConstants.ROLE_SUPER_ADMIN);
            if (!isAdmin && !isSuperAdmin) {
                throw new BizException("공지글 수정 권한이 없습니다.");
            }
        }

        // 새 첨부파일 검증 (기존 첨부 삭제·게시글 수정 전)
        attachmentService.validateFiles(attachmentService.countRemainingAttachments(id, deleteAttachmentIds), files);

        // 삭제 대상 첨부파일 처리
        if (deleteAttachmentIds != null && !deleteAttachmentIds.isEmpty()) {
            attachmentService.deleteAttachmentsByIds(id, deleteAttachmentIds);
        }

        // 수정
        board.setTitle(boardDto.getTitle());
        board.setContent(boardDto.getContent());
        board.setNotice(boardDto.isNotice());
        board.setSecret(boardDto.isSecret());
        board.setUpdatedAt(LocalDateTime.now());

        FreeBoard savedBoard = freeBoardRepository.save(board);

        // 새 첨부파일 저장
        if (files != null && !files.isEmpty()) {
            attachmentService.saveAttachments(savedBoard, files);
        }

        return convertToDto(savedBoard, loginMember.getId());
    }

    /**
     * 게시글 삭제 — 첨부 디스크 파일은 커밋 후 삭제
     */
    @Transactional
    public void deleteBoard(HttpServletRequest request, Long id) {
        Optional<FreeBoard> boardOpt = freeBoardRepository.findById(id);
        FreeBoard board = boardOpt.orElseThrow(() ->
                new BizException("게시글을 찾을 수 없습니다."));

        // 로그인 회원 정보
        Member loginMember = memberService.getLoginMember(request);

        // 권한 체크
        validateDeletePermission(request, loginMember, board);

        // 물리파일 삭제
        attachmentService.deleteAllAttachments(id);

        freeBoardRepository.delete(board);
    }

    /**
     * 수정 권한 체크
     * 슈퍼관리자, 관리자 : 자기 게시글만 수정 가능
     * 그 외 : 자기 게시글만 수정 가능
     */
    private void validateUpdatePermission(HttpServletRequest request, Member loginMember, FreeBoard board) {

        if (loginMember == null) {
            throw new BizException("로그인이 필요합니다.");
        }

        Long loginMemberId = loginMember.getId();
        Long writerId = board.getMember().getId();

        // 본인 글인지 체크
        if (!loginMemberId.equals(writerId)) {
            throw new BizException("해당 게시글을 수정할 권한이 없습니다.");
        }
    }

    /**
     * 삭제 권한 체크
     * 슈퍼관리자, 관리자 : 모든 게시글 삭제 가능
     * 그 외 : 자기 게시글만 삭제 가능
     */
    private void validateDeletePermission(HttpServletRequest request, Member loginMember, FreeBoard board) {

        if (loginMember == null) {
            throw new BizException("로그인이 필요합니다.");
        }

        Long loginMemberId = loginMember.getId();
        Long writerId = board.getMember().getId();

        // SUPER_ADMIN 또는 ADMIN 권한이면 모두 삭제 가능
        boolean isSuperAdmin = sessionService.hasRole(request, RoleConstants.ROLE_SUPER_ADMIN);
        boolean isAdmin = sessionService.hasRole(request, RoleConstants.ROLE_ADMIN);

        if (isSuperAdmin || isAdmin) {
            return;
        }

        // 그 외 권한은 본인 글만 삭제 가능
        if (!loginMemberId.equals(writerId)) {
            throw new BizException("해당 게시글을 삭제할 권한이 없습니다.");
        }
    }


}
