package com.finance.dart.api.domestic.collect;

import com.finance.dart.api.domestic.enums.KrExchange;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * KIS 종목 마스터 파일(kospi/kosdaq_code.mst.zip) → 보통주 목록
 * - 종목명은 MS949 바이트 고정폭 → 문자 단위가 아니라 바이트 단위로 자른다 (ROADMAP_domestic 0단계 함정 7)
 * - 보통주 = 그룹코드 ST + 우선주 구분 0 + 스팩 아님 + 6자리 코드 (ETF·리츠·외국주는 그룹코드가 달라 자동 제외)
 * - 종목코드는 숫자만이 아니다: 2024년부터 신규 상장은 영문 섞인 코드(예: 0126Z0 삼성에피스홀딩스)
 */
public final class KrStockMasterParser {

    private static final Charset MS949 = Charset.forName("MS949");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String GROUP_STOCK = "ST";
    private static final String COMMON_STOCK = "0";
    private static final String YES = "Y";
    private static final String NO = "N";
    private static final int SHORT_CODE_LENGTH = 9;
    private static final int STANDARD_CODE_LENGTH = 12;
    private static final int NAME_OFFSET = SHORT_CODE_LENGTH + STANDARD_CODE_LENGTH;
    static final String STOCK_CODE_PATTERN = "[0-9A-Z]{6}";

    /** 마스터 1행 (보통주) */
    public record Row(String stockCode, String stockName, KrExchange exchange, String indexLarge, String indexMid,
                      String indexSmall, LocalDate listedDate, String fiscalMonth, String tradingHalt,
                      String administrative, Long listedShares) {}

    private KrStockMasterParser() {}

    public static List<Row> parseZip(byte[] zip, KrExchange exchange) {
        return parse(unzipFirstEntry(zip), exchange);
    }

    public static List<Row> parse(byte[] content, KrExchange exchange) {
        List<Row> rows = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= content.length; i++) {
            if (i == content.length || content[i] == '\n') {
                int end = (i > start && content[i - 1] == '\r') ? i - 1 : i;
                Row row = parseLine(Arrays.copyOfRange(content, start, end), exchange);
                if (row != null) rows.add(row);
                start = i + 1;
            }
        }
        return rows;
    }

    /** 보통주가 아니거나 형식이 맞지 않으면 null */
    static Row parseLine(byte[] line, KrExchange exchange) {
        int tailStart = line.length - exchange.getTailLength();
        if (tailStart <= NAME_OFFSET) return null;

        String group = ascii(line, tailStart + KrExchange.GROUP_OFFSET, KrExchange.GROUP_LENGTH);
        String preferred = ascii(line, tailStart + exchange.getPreferredOffset(), 1);
        String spac = ascii(line, tailStart + exchange.getSpacOffset(), 1);
        String code = ascii(line, 0, SHORT_CODE_LENGTH).trim();
        if (!GROUP_STOCK.equals(group) || !COMMON_STOCK.equals(preferred) || YES.equals(spac)
                || !code.matches(STOCK_CODE_PATTERN)) {
            return null;
        }

        String name = new String(line, NAME_OFFSET, tailStart - NAME_OFFSET, MS949).trim();
        return new Row(code, name, exchange,
                ascii(line, tailStart + KrExchange.INDEX_LARGE_OFFSET, KrExchange.INDEX_CODE_LENGTH),
                ascii(line, tailStart + KrExchange.INDEX_MID_OFFSET, KrExchange.INDEX_CODE_LENGTH),
                ascii(line, tailStart + KrExchange.INDEX_SMALL_OFFSET, KrExchange.INDEX_CODE_LENGTH),
                parseDate(ascii(line, tailStart + exchange.getListedDateOffset(), KrExchange.DATE_LENGTH)),
                ascii(line, tailStart + exchange.getFiscalMonthOffset(), KrExchange.MONTH_LENGTH),
                yesNo(ascii(line, tailStart + exchange.getHaltOffset(), 1)),
                yesNo(ascii(line, tailStart + exchange.getAdministrativeOffset(), 1)),
                parseShares(ascii(line, tailStart + exchange.getListedSharesOffset(), KrExchange.LISTED_SHARES_LENGTH)));
    }

    /** 천 주 단위 → 주. 0이거나 숫자가 아니면 null */
    private static Long parseShares(String thousands) {
        try {
            long v = Long.parseLong(thousands.trim());
            return v > 0 ? v * KrExchange.LISTED_SHARES_UNIT : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String ascii(byte[] line, int offset, int length) {
        return new String(line, offset, length, StandardCharsets.US_ASCII);
    }

    private static String yesNo(String flag) {
        return YES.equals(flag) ? YES : NO;
    }

    private static LocalDate parseDate(String yyyymmdd) {
        try {
            return LocalDate.parse(yyyymmdd, YYYYMMDD);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static byte[] unzipFirstEntry(byte[] zip) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = zis.getNextEntry();
            if (entry == null) throw new IllegalStateException("KIS 마스터 zip이 비어 있음");
            return zis.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("KIS 마스터 zip 해제 실패", e);
        }
    }
}
