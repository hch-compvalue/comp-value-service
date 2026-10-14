package com.finance.dart.member.repository;

import com.finance.dart.member.entity.MemberRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MemberRoleRepository extends JpaRepository<MemberRoleEntity, Long> {

    void deleteByMemberIdAndRoleId(Long memberId, Long roleId);

    /**
     * 회원의 특정 권한 보유 여부 확인 (순환 참조 없이 ID만으로 체크)
     */
    boolean existsByMemberIdAndRoleId(Long memberId, Long roleId);

    /**
     * 회원의 권한명 목록 조회 (join fetch로 N+1 문제 해결 및 순환 참조 방지)
     */
    @Query("SELECT r.roleName FROM MemberRoleEntity mr JOIN mr.role r WHERE mr.member.id = :memberId")
    List<String> findRoleNamesByMemberId(@Param("memberId") Long memberId);
}
