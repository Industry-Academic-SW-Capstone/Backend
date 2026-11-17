package grit.stockIt.global.exception;

/**
 * 거래 불가 종목 예외
 */
public class UntradeableStockException extends RuntimeException {
    
    public UntradeableStockException(String message) {
        super(message);
    }
    
    public UntradeableStockException() {
        this("이 종목은 거래가 제한됩니다.");
    }
}

