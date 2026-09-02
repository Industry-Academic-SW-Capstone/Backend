package grit.stockIt.global.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자 판정.
 *
 * <p>Member에 역할 컬럼이 없어 스키마 변경 없이 프로퍼티 허용목록으로 판정한다.
 * 역할 컬럼이 생기면 이 클래스를 지우고 {@code Member.role}을 권한으로 승격시키면 된다.
 *
 * <p>목록이 비어 있으면 아무도 관리자가 아니다(fail-closed) — 설정 누락이 전체 공개로
 * 이어지지 않게 하기 위함이다.
 */
@Slf4j
@Component
public class AdminEmailAllowlist {

    private final Set<String> adminEmails;

    public AdminEmailAllowlist(@Value("${app.admin.emails:}") List<String> adminEmails) {
        this.adminEmails = adminEmails.stream()
                .map(String::trim)
                .filter(email -> !email.isEmpty())
                .map(email -> email.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        if (this.adminEmails.isEmpty()) {
            log.warn("app.admin.emails가 비어 있습니다. 관리자 API에 아무도 접근할 수 없습니다.");
        }
    }

    public boolean isAdmin(String email) {
        return email != null && adminEmails.contains(email.toLowerCase(Locale.ROOT));
    }
}
