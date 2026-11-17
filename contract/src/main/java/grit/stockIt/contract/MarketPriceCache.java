package grit.stockIt.contract;

import java.math.BigDecimal;
import java.util.Optional;

// 시세가 주인인 현재가·상한가 캐시. 다른 서비스는 이 인터페이스로만 읽고 쓴다 — 저장소(Redis)와 키 형식은 시세 구현만 안다.
// 서비스를 나눠도 HTTP 로 바꾸지 않는다. 랭킹은 한 번에 많은 종목을 읽어 캐시를 직접 읽는 것이 목적이다.
// 증권사는 KIS REST 로 받은 값을 채워 넣을 수 있다(구독하지 않은 종목).
public interface MarketPriceCache {

    void updateLastPrice(String stockCode, BigDecimal price);

    Optional<BigDecimal> getLastPrice(String stockCode);

    void updateUpperLimitPrice(String stockCode, BigDecimal price);

    Optional<BigDecimal> getUpperLimitPrice(String stockCode);
}
