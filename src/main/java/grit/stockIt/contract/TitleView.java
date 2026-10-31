package grit.stockIt.contract;

// 부가 서비스가 증권사에 알려 주는 칭호 정보.
public record TitleView(Long titleId, String name, String description) {
}
