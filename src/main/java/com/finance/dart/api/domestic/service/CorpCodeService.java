package com.finance.dart.api.domestic.service;

import com.finance.dart.api.domestic.dto.CorpCodeDTO;
import com.finance.dart.api.domestic.dto.CorpCodeResDTO;
import com.finance.dart.common.config.SingleOrArrayDeserializer;
import com.finance.dart.common.util.XmlUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.json.XML;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.util.List;

/**
 * 공시정보 > 고유번호 조회 서비스
 */
@Slf4j
@Service
@AllArgsConstructor
public class CorpCodeService {
    private final  CorpCodeCacheService corpCodeCacheService;

    /**
     * 고유번호 조회
     *
     * @param publicCompanyYn 상장기업 여부
     * @return CorpCodeResDTO 객체
     */
    public CorpCodeResDTO getCorpCode(boolean publicCompanyYn) {

        ResponseEntity<byte[]> response = corpCodeCacheService.getCoprCodeFile();
        byte[] zipFile = response.getBody();

        return getZipData(zipFile, publicCompanyYn);
    }

    /**
     * 기업명으로 기업정보 검색
     *
     * @param publicCompanyYn 상장기업 여부
     * @param corpName        검색할 기업명
     * @return 해당 기업 정보를 담은 CorpCodeDTO (찾지 못하면 null)
     */
    public CorpCodeDTO getCorpCodeFindName(boolean publicCompanyYn, String corpName) {

        ResponseEntity<byte[]> response = corpCodeCacheService.getCoprCodeFile();
        byte[] zipFile = response.getBody();

        CorpCodeResDTO corpCodeResDTO = getZipData(zipFile, publicCompanyYn);
        return corpCodeResDTO.getList().stream()
                .filter(corpDTO -> corpName.equals(corpDTO.getCorpName()))
                .findFirst()
                .orElse(null);
    }

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