package grit.stockIt.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker // STOMP 기반의 웹소켓 메시지 브로커 기능을 활성화
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private static final String STOCK_ENDPOINT = "/ws"; // 웹소켓 연결 엔드포인트
    private static final String CLIENT_PREFIX1 = "/topic"; // 클라이언트가 구독할 때 사용
    private static final String CLIENT_PREFIX2 = "/queue";
    private static final String SERVER_PREFIX = "/app";

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 메시지 브로커 활성화 (간단한 인메모리 브로커 사용)
        // 클라이언트가 해당 토픽을 구독하면 서버가 해당 주소로 메시지를 보냈을 때 브로커가 구독한 클라이언트들에게 메시지를 전달
        config.enableSimpleBroker(CLIENT_PREFIX1, CLIENT_PREFIX2); // '/topic/**, /queue/**' 으로 보내는 메시지를 브로드캐스트
        
        // 클라이언트가 메시지를 보낼 때의 prefix 설정, 컨트롤러의 @MessageMapping으로 전달
        config.setApplicationDestinationPrefixes(SERVER_PREFIX);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket 연결 엔드포인트
        registry.addEndpoint(STOCK_ENDPOINT)
                .setAllowedOriginPatterns("*") // 개발용 (운영에서는 특정 도메인 지정)
                .withSockJS(); // SockJS 지원
    }
}

