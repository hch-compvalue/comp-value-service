package com.finance.dart.board.repository;

import com.finance.dart.board.entity.FreeBoardAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FreeBoardAttachmentRepository extends JpaRepository<FreeBoardAttachment, Long> {

    List<FreeBoardAttachment> findByFreeBoardId(Long freeBoardId);

}
