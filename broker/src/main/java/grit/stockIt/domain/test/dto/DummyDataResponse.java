package grit.stockIt.domain.test.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 더미 데이터 생성 결과 DTO
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DummyDataResponse {
    
    /**
     * 생성된 회원 수
     */
    private int memberCount;
    
    /**
     * 생성된 계좌 수
     */
    private int accountCount;
    
    /**
     * 최소 잔액
     */
    private BigDecimal minBalance;
    
    /**
     * 최대 잔액
     */
    private BigDecimal maxBalance;
    
    /**
     * 평균 잔액
     */
    private BigDecimal avgBalance;
    
    /**
     * 소요 시간 (ms)
     */
    private long elapsedTimeMs;
    
    /**
     * 메시지
     */
    private String message;
}

