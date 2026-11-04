package com.finance.dart.api.domestic.service;

import com.finance.dart.api.domestic.dto.CorpCodeDTO;
import com.finance.dart.api.domestic.dto.CorpCodeResDTO;
import com.finance.dart.common.config.SingleOrArrayDeserializer;
import com.finance.dart.common.util.XmlUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.json.XML;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.util.List;

/**
 * 공시정보 > 고유번호 zip 파싱 서비스 (국내 종목 마스터 수집에서 사용)
 */
@Slf4j
@Service
public class CorpCodeService {
    /**
     * 고유번호 zip 파일 파싱 (파일을 직접 받은 경우 — 국내 종목 마스터 수집, 타임아웃·재시도는 호출부)
     *
     * @param zipFile         OpenDart corpCode.xml zip
     * @param publicCompanyYn 상장기업 여부
     * @return CorpCodeResDTO 객체
     */
    public CorpCodeResDTO parseCorpCodeZip(byte[] zipFile, boolean publicCompanyYn) {
        return getZipData(zipFile, publicCompanyYn);
    }

    private CorpCodeResDTO getZipData(byte[] zipFile, boolean publicCompanyYn) {
        String xmlContent = XmlUtil.getXmlContentOfZipFile(zipFile);
        if (xmlContent == null || xmlContent.isEmpty()) {
            throw new RuntimeException("서버로부터 응답을 받지 못했습니다.");
        }

        JSONObject jsonObject = XML.toJSONObject(xmlContent).getJSONObject("result");
        CorpCodeResDTO corpCodeResDTO = getSingArrayToListGson().fromJson(jsonObject.toString(), CorpCodeResDTO.class);

        // 상장 기업만 필터링 (stockCode가 존재하는 경우)
        if (publicCompanyYn) {
            List<CorpCodeDTO> filteredList = corpCodeResDTO.getList().stream()
                    .filter(corp -> corp.getStockCode() != null && !corp.getStockCode().isEmpty())
                    .toList();
            corpCodeResDTO.setList(filteredList);
        }
        return corpCodeResDTO;
    }

    private Gson getSingArrayToListGson() {
        Type corpCodeDtoListType = new TypeToken<List<CorpCodeDTO>>() {}.getType();

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(corpCodeDtoListType, new SingleOrArrayDeserializer<>(CorpCodeDTO.class))
                .create();

        return gson;
    }
}