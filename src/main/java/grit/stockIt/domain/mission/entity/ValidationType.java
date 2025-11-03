package grit.stockIt.domain.mission.entity;

public enum ValidationType {
    CLIENT_ONLY,     // 클라이언트가 완료 처리 (예: 출석체크)
    SERVER_PROGRESS  // 서버가 진행도를 누적 (예: 분할매수 3회)
}
