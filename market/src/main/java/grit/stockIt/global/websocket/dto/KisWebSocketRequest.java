package grit.stockIt.global.websocket.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// KIS 웹소켓 요청 DTO
public record KisWebSocketRequest(
        @JsonProperty("header") Header header,
        @JsonProperty("body") Body body
) {
    
    public record Header(
            @JsonProperty("approval_key") String approvalKey,
            @JsonProperty("custtype") String custtype,
            @JsonProperty("tr_type") String trType,
            @JsonProperty("content-type") String contentType
    ) {
        public static Header subscribe(String approvalKey) {
            return new Header(approvalKey, "P", "1", "utf-8");
        }
        
        public static Header unsubscribe(String approvalKey) {
            return new Header(approvalKey, "P", "2", "utf-8");
        }
    }
    
    public record Body(
            @JsonProperty("input") Input input
    ) {}
    
    public record Input(
            @JsonProperty("tr_id") String trId,
            @JsonProperty("tr_key") String trKey
    ) {
        public static Input forStock(String stockCode) {
            return new Input("H0STCNT0", stockCode);
        }
        
        public static Input forOrderBook(String stockCode) {
            return new Input("H0STASP0", stockCode);
        }
    }
    
    public static KisWebSocketRequest subscribe(String approvalKey, String stockCode) {
        return new KisWebSocketRequest(
                Header.subscribe(approvalKey),
                new Body(Input.forStock(stockCode))
        );
    }
    
    public static KisWebSocketRequest subscribeOrderBook(String approvalKey, String stockCode) {
        return new KisWebSocketRequest(
                Header.subscribe(approvalKey),
                new Body(Input.forOrderBook(stockCode))
        );
    }
    
    public static KisWebSocketRequest unsubscribe(String approvalKey, String stockCode) {
        return new KisWebSocketRequest(
                Header.unsubscribe(approvalKey),
                new Body(Input.forStock(stockCode))
        );
    }
    
    public static KisWebSocketRequest unsubscribeOrderBook(String approvalKey, String stockCode) {
        return new KisWebSocketRequest(
                Header.unsubscribe(approvalKey),
                new Body(Input.forOrderBook(stockCode))
        );
    }
}

