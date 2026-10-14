package com.finance.dart.api.domestic.controller;

import com.finance.dart.api.domestic.service.CorpCodeService;
import com.finance.dart.common.logging.TransactionLogging;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 공시정보 컨트롤러
 */

@RestController
@AllArgsConstructor
@RequestMapping("disclosure")
public class DisclosureController {

    private final CorpCodeService corpCodeService;


    /**
     * 고유번호 조회
     * @return
     */
    @TransactionLogging
    @GetMapping("corpCode")
    public ResponseEntity<Object> getCorpCode() {

        return new ResponseEntity<>(corpCodeService.getCorpCode(true), HttpStatus.OK);
    }

}