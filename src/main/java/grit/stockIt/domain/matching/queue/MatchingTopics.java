package grit.stockIt.domain.matching.queue;

public final class MatchingTopics {

    // 종목 코드가 키다. 한 종목의 명령은 한 파티션에 순서대로 쌓인다.
    public static final String COMMANDS = "matching.commands";

    private MatchingTopics() {
    }
}
