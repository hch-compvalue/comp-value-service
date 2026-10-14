package com.finance.dart.board.service;

import com.finance.dart.board.dto.SellRecordDto;
import com.finance.dart.board.entity.SellRecordEntity;
import com.finance.dart.board.repository.SellRecordRepository;
import com.finance.dart.common.util.ConvertUtil;
import com.finance.dart.member.dto.Member;
import com.finance.dart.member.entity.MemberEntity;
import com.finance.dart.member.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

@Slf4j
@AllArgsConstructor
@Service
public class SellRecordService {

    private final SellRecordRepository sellRecordRepository;
    private final MemberService memberService;

    /**
     * 매도기록 등록
     * @param request
     * @param sellRecordDto
     * @return
     */
    public SellRecordDto regiSellRecord(HttpServletRequest request, SellRecordDto sellRecordDto) {

        // 로그인 회원 정보
        MemberEntity memberEntity = getMemberEntity(request);

        // DTO -> Entity 변환
        SellRecordEntity sellRecordEntity = ConvertUtil.parseObject(sellRecordDto, SellRecordEntity.class);
        sellRecordEntity.setMember(memberEntity);
        sellRecordEntity.setCreatedAt(LocalDateTime.now());
        sellRecordEntity.setUpdatedAt(LocalDateTime.now());

        // 데이터 저장
        SellRecordEntity savedEntity = sellRecordRepository.save(sellRecordEntity);

        // Entity -> DTO 변환하여 반환
        return ConvertUtil.parseObject(savedEntity, SellRecordDto.class);
    }

    /**
     * 매도기록 수정
     * @param request
     * @param updateDto
     * @return
     */
    public SellRecordDto modiSellRecord(HttpServletRequest request, SellRecordDto updateDto) {

        // 로그인 회원 정보
        MemberEntity memberEntity = getMemberEntity(request);

        // 데이터 조회
        Optional<SellRecordEntity> dataOpt = sellRecordRepository.findById(updateDto.getId());
        if (dataOpt.isEmpty()) return null;

        // 데이터 수정
        SellRecordEntity data = dataOpt.get();
        data.setMember(memberEntity);

        if (updateDto.getSymbol() != null) data.setSymbol(updateDto.getSymbol());
        if (updateDto.getCompanyName() != null) data.setCompanyName(updateDto.getCompanyName());
        if (updateDto.getSellDate() != null) data.setSellDate(updateDto.getSellDate());
        if (updateDto.getSellPrice() != null) data.setSellPrice(updateDto.getSellPrice());
        if (updateDto.getSellQty() != null) data.setSellQty(updateDto.getSellQty());
        if (updateDto.getRealizedPnl() != null) data.setRealizedPnl(updateDto.getRealizedPnl());
        if (updateDto.getBuyExchangeRateAtTrade() != null) data.setBuyExchangeRateAtTrade(updateDto.getBuyExchangeRateAtTrade());
        if (updateDto.getSellExchangeRateAtTrade() != null) data.setSellExchangeRateAtTrade(updateDto.getSellExchangeRateAtTrade());
        if (updateDto.getRmk() != null) data.setRmk(updateDto.getRmk());
        data.setUpdatedAt(LocalDateTime.now());

        SellRecordEntity savedEntity = sellRecordRepository.save(data);

        // Entity -> DTO 변환하여 반환
        return ConvertUtil.parseObject(savedEntity, SellRecordDto.class);
    }

    /**
     * 매도기록 삭제
     * @param deleteDto
     */
    public void delSellRecord(SellRecordDto deleteDto) {

        if (deleteDto.getId() != null) {
            sellRecordRepository.deleteById(deleteDto.getId());
        }
    }

    /**
     * 매도기록 목록 조회
     * @param request
     * @return
     */
    public List<SellRecordDto> getSellRecordList(HttpServletRequest request) {

        List<SellRecordDto> sellRecordDtoList = new LinkedList<>();

        // 로그인 회원정보
        Member member = memberService.getLoginMember(request);
        Long memberId = member.getId();

        List<SellRecordEntity> sellRecordEntityList = sellRecordRepository.findByMember_Id(memberId);

        for (SellRecordEntity sellRecordEntity : sellRecordEntityList) {
            SellRecordDto sellRecordDto = ConvertUtil.parseObject(sellRecordEntity, SellRecordDto.class);
            sellRecordDtoList.add(sellRecordDto);
        }

        return sellRecordDtoList;
    }

    /**
     * 로그인 회원정보 조회
     * @param request
     * @return
     */
    private MemberEntity getMemberEntity(HttpServletRequest request) {
        // 로그인 회원 정보
        Member member = memberService.getLoginMember(request);
        if (member == null) {
            throw new RuntimeException("로그인이 필요합니다.");
        }

        MemberEntity memberEntity = new MemberEntity();
        memberEntity.setId(member.getId());
        memberEntity.setUsername(member.getUsername());
        memberEntity.setNickname(member.getNickname());

        return memberEntity;
    }
}
