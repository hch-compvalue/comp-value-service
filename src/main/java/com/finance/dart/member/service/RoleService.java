package com.finance.dart.member.service;

import com.finance.dart.common.constant.ResponseEnum;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.member.entity.MemberEntity;
import com.finance.dart.member.entity.MemberRoleEntity;
import com.finance.dart.member.entity.RoleEntity;
import com.finance.dart.member.repository.MemberRepository;
import com.finance.dart.member.repository.MemberRoleRepository;
import com.finance.dart.member.repository.RoleRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@AllArgsConstructor
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final MemberRepository memberRepository;
    private final MemberRoleRepository memberRoleRepository;

    /**
     * 권한 목록 조회
     */
    public CommonResponse<List<RoleEntity>> getRoles() {
        List<RoleEntity> roles = roleRepository.findAll();
        return new CommonResponse<>(roles);
    }

    /**
     * 회원에게 권한 부여
     */
    @Transactional
    public CommonResponse<Void> assignRoleToMember(Long memberId, Long roleId) {
        CommonResponse<Void> response = new CommonResponse<>();

        Optional<MemberEntity> memberOpt = memberRepository.findById(memberId);
        Optional<RoleEntity> roleOpt = roleRepository.findById(roleId);

        if (memberOpt.isEmpty() || roleOpt.isEmpty()) {
            response.setResponeInfo(ResponseEnum.NOT_FOUND);
            return response;
        }

        // 이미 해당 권한이 있는지 체크 (순환 참조 방지 - Repository 메서드 사용)
        boolean alreadyHasRole = memberRoleRepository.existsByMemberIdAndRoleId(memberId, roleId);

        if (alreadyHasRole) {
            response.setResponeInfo(ResponseEnum.DUPLICATE);
            return response;
        }

        MemberRoleEntity memberRole = new MemberRoleEntity();
        memberRole.setMember(memberOpt.get());
        memberRole.setRole(roleOpt.get());
        memberRoleRepository.save(memberRole);

        response.setResponeInfo(ResponseEnum.OK);
        return response;
    }

    /**
     * 회원의 권한 제거
     */
    @Transactional
    public CommonResponse<Void> removeRoleFromMember(Long memberId, Long roleId) {
        CommonResponse<Void> response = new CommonResponse<>();

        memberRoleRepository.deleteByMemberIdAndRoleId(memberId, roleId);
        response.setResponeInfo(ResponseEnum.OK);
        return response;
    }

    /**
     * 회원의 권한 목록 조회 (순환 참조 방지 - JPQL 직접 사용)
     */
    public CommonResponse<List<String>> getMemberRoles(Long memberId) {
        List<String> roleNames = memberRoleRepository.findRoleNamesByMemberId(memberId);
        return new CommonResponse<>(roleNames);
    }
}
