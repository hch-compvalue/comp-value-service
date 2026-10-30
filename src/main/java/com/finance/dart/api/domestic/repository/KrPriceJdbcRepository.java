package com.finance.dart.api.domestic.repository;

import com.finance.dart.api.domestic.collect.KrDailyBar;
import com.finance.dart.api.domestic.collect.KrIndexBar;
import com.finance.dart.api.domestic.valuation.KrPriceSeries;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 국내 일봉·지수 대량 저장 (JdbcTemplate 배치 upsert)
 * - JPA saveAll은 복합키 행마다 SELECT 후 저장 → 종목당 1,200행 넘는 첫 적재에 부적합
 */
@Repository
@RequiredArgsConstructor
public class KrPriceJdbcRepository {

    private static final String UPSERT_DAILY = """
            INSERT INTO tb_kr_daily_price (stock_code, trade_date, open_price, high_price, low_price, close_price, volume, trade_amount)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (stock_code, trade_date) DO UPDATE SET
              open_price = EXCLUDED.open_price, high_price = EXCLUDED.high_price, low_price = EXCLUDED.low_price,
              close_price = EXCLUDED.close_price, volume = EXCLUDED.volume, trade_amount = EXCLUDED.trade_amount
            """;

    private static final String UPSERT_INDEX = """
            INSERT INTO tb_kr_index_daily (index_code, trade_date, open_value, high_value, low_value, close_value)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (index_code, trade_date) DO UPDATE SET
              open_value = EXCLUDED.open_value, high_value = EXCLUDED.high_value,
              low_value = EXCLUDED.low_value, close_value = EXCLUDED.close_value
            """;

    /** 종목별 마지막 저장일 + 그날 종가 (증분 시작점·수정주가 변경 감지용) */
    private static final String SELECT_LAST_DAILY = """
            SELECT DISTINCT ON (stock_code) stock_code, trade_date, close_price
            FROM tb_kr_daily_price ORDER BY stock_code, trade_date DESC
            """;

    private static final String SELECT_LAST_INDEX_DATE = "SELECT max(trade_date) FROM tb_kr_index_daily WHERE index_code = ?";

    private static final String SELECT_DAILY_CLOSES =
            "SELECT trade_date, close_price FROM tb_kr_daily_price WHERE stock_code = ? AND trade_date >= ? ORDER BY trade_date";
    private static final String SELECT_INDEX_CLOSES =
            "SELECT trade_date, close_value FROM tb_kr_index_daily WHERE index_code = ? AND trade_date >= ? ORDER BY trade_date";

    private final JdbcTemplate jdbcTemplate;

    /** 종목 종가 (from 이후, 날짜 오름차순) */
    public List<KrPriceSeries.DailyClose> findDailyCloses(String stockCode, LocalDate from) {
        return jdbcTemplate.query(SELECT_DAILY_CLOSES, (rs, i) ->
                new KrPriceSeries.DailyClose(rs.getDate(1).toLocalDate(), rs.getInt(2)), stockCode, Date.valueOf(from));
    }

    /** 지수 종가 (from 이후, 날짜 오름차순) */
    public List<KrPriceSeries.DailyClose> findIndexCloses(String indexCode, LocalDate from) {
        return jdbcTemplate.query(SELECT_INDEX_CLOSES, (rs, i) ->
                new KrPriceSeries.DailyClose(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2).doubleValue()), indexCode, Date.valueOf(from));
    }

    /** 마지막 저장 일봉 (종목코드 → 날짜·종가) */
    public record LastBar(LocalDate tradeDate, int closePrice) {}

    public Map<String, LastBar> findLastDailyBars() {
        Map<String, LastBar> result = new HashMap<>();
        jdbcTemplate.query(SELECT_LAST_DAILY, rs -> {
            result.put(rs.getString("stock_code"),
                    new LastBar(rs.getDate("trade_date").toLocalDate(), rs.getInt("close_price")));
        });
        return result;
    }

    public LocalDate findLastIndexDate(String indexCode) {
        Date date = jdbcTemplate.queryForObject(SELECT_LAST_INDEX_DATE, Date.class, indexCode);
        return date == null ? null : date.toLocalDate();
    }

    @Transactional
    public void upsertDaily(String stockCode, List<KrDailyBar> bars) {
        jdbcTemplate.batchUpdate(UPSERT_DAILY, bars, bars.size(), (ps, bar) -> {
            ps.setString(1, stockCode);
            ps.setDate(2, Date.valueOf(bar.tradeDate()));
            ps.setInt(3, bar.open());
            ps.setInt(4, bar.high());
            ps.setInt(5, bar.low());
            ps.setInt(6, bar.close());
            ps.setLong(7, bar.volume());
            ps.setLong(8, bar.tradeAmount());
        });
    }

    /** 수정주가가 바뀐 종목: 기존 일봉 전부 지우고 새로 받은 이력으로 교체 (한 트랜잭션) */
    @Transactional
    public void replaceDaily(String stockCode, List<KrDailyBar> bars) {
        jdbcTemplate.update("DELETE FROM tb_kr_daily_price WHERE stock_code = ?", stockCode);
        upsertDaily(stockCode, bars);
    }

    @Transactional
    public void upsertIndex(String indexCode, List<KrIndexBar> bars) {
        jdbcTemplate.batchUpdate(UPSERT_INDEX, bars, bars.size(), (ps, bar) -> {
            ps.setString(1, indexCode);
            ps.setDate(2, Date.valueOf(bar.tradeDate()));
            ps.setBigDecimal(3, bar.open());
            ps.setBigDecimal(4, bar.high());
            ps.setBigDecimal(5, bar.low());
            ps.setBigDecimal(6, bar.close());
        });
    }
}
