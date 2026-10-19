package com.finance.dart.board.service;

import com.finance.dart.board.dto.SymbolMemoDto;
import com.finance.dart.board.entity.SymbolMemoEntity;
import com.finance.dart.board.repository.SymbolMemoRepository;
import com.finance.dart.board.repository.TranRecordRepository;
import com.finance.dart.common.exception.BizException;
import com.finance.dart.common.util.StringUtil;
import com.finance.dart.member.dto.Member;
import com.finance.dart.member.entity.MemberEntity;
import com.finance.dart.member.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 보유종목 종목 메모 서비스
 * - 메모는 보유 중인 심볼에만 저장 가능, 보유 매수 건이 모두 없어지면 함께 삭제
 */
@Slf4j
@AllArgsConstructor
@Service
public class SymbolMemoService {

    private static final DateTimeFormatter UPDATED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SymbolMemoRepository symbolMemoRepository;
    private final TranRecordRepository tranRecordRepository;
    private final MemberService memberService;

    /**
     * 로그인 회원의 종목 메모 목록
     */
    public List<SymbolMemoDto> getMemoList(HttpServletRequest request) {
        Long memberId = getLoginMemberId(request);
        return symbolMemoRepository.findByMember_Id(memberId).stream().map(this::toDto).toList();
    }

    /**
     * 종목 메모 저장 (없으면 생성, 빈 메모면 삭제)
     * @return 저장된 메모 (삭제 시 memo 빈 값)
     */
    @Transactional
    public SymbolMemoDto saveMemo(HttpServletRequest request, SymbolMemoDto reqDto) {
        Long memberId = getLoginMemberId(request);
        String symbol = reqDto.getSymbol();
        if (StringUtil.isStringEmpty(symbol)) throw new BizException("티커 정보가 존재하지 않습니다.");
        if (!tranRecordRepository.existsByMember_IdAndSymbol(memberId, symbol)) {
            throw new BizException("보유 중인 종목에만 메모를 남길 수 있습니다.");
        }

        String memo = reqDto.getMemo() == null ? "" : reqDto.getMemo().trim();
        if (memo.length() > SymbolMemoEntity.MEMO_MAX_LENGTH) {
            throw new BizException("메모는 " + SymbolMemoEntity.MEMO_MAX_LENGTH + "자까지 입력할 수 있습니다.");
        }

        if (memo.isEmpty()) {
            symbolMemoRepository.deleteByMember_IdAndSymbol(memberId, symbol);
            SymbolMemoDto deleted = new SymbolMemoDto();
            deleted.setSymbol(symbol);
            deleted.setMemo("");
            return deleted;
        }

        SymbolMemoEntity entity = symbolMemoRepository.findByMember_IdAndSymbol(memberId, symbol)
                .orElseGet(() -> newEntity(memberId, symbol));
        entity.setMemo(memo);
        entity.setUpdatedAt(LocalDateTime.now());
        return toDto(symbolMemoRepository.save(entity));
    }

    /**
     * 보유 매수 건이 하나도 남지 않은 심볼의 메모 삭제 (매수 건 삭제·전량 매도 후 호출)
     */
    @Transactional
    public void deleteIfNotHeld(Long memberId, String symbol) {
        if (StringUtil.isStringEmpty(symbol) || tranRecordRepository.existsByMember_IdAndSymbol(memberId, symbol)) return;
        symbolMemoRepository.deleteByMember_IdAndSymbol(memberId, symbol);
    }

    /**
     * 매수 건 티커 변경 후 메모 정리
     * - 이전 심볼 보유분이 남아 있으면 그대로
     * - 남지 않았으면: 새 심볼에 메모가 없을 때만 옮기고, 있으면 이전 메모 삭제
     */
    @Transactional
    public void handleSymbolChange(Long memberId, String oldSymbol, String newSymbol) {
        if (StringUtil.isStringEmpty(oldSymbol) || oldSymbol.equals(newSymbol)) return;
        if (tranRecordRepository.existsByMember_IdAndSymbol(memberId, oldSymbol)) return;

        symbolMemoRepository.findByMember_IdAndSymbol(memberId, oldSymbol).ifPresent(oldMemo -> {
            if (symbolMemoRepository.findByMember_IdAndSymbol(memberId, newSymbol).isPresent()) {
                symbolMemoRepository.delete(oldMemo);
            } else {
                oldMemo.setSymbol(newSymbol);
                oldMemo.setUpdatedAt(LocalDateTime.now());
                symbolMemoRepository.save(oldMemo);
            }
        });
    }

    private SymbolMemoEntity newEntity(Long memberId, String symbol) {
        MemberEntity member = new MemberEntity();
        member.setId(memberId);
        SymbolMemoEntity entity = new SymbolMemoEntity();
        entity.setMember(member);
        entity.setSymbol(symbol);
        return entity;
    }

    private SymbolMemoDto toDto(SymbolMemoEntity entity) {
        SymbolMemoDto dto = new SymbolMemoDto();
        dto.setSymbol(entity.getSymbol());
        dto.setMemo(entity.getMemo());
        dto.setUpdatedAt(entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().format(UPDATED_AT_FORMAT));
        return dto;
    }

    private Long getLoginMemberId(HttpServletRequest request) {
        Member member = memberService.getLoginMember(request);
        if (member == null) throw new BizException("로그인이 필요합니다.");
        return member.getId();
    }
}
