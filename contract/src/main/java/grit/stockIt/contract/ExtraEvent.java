package grit.stockIt.contract;

import java.util.UUID;

// extra.events 메시지. 종류마다 채우는 부분이 다르다. 필드는 추가만 한다.
// eventId 는 증권사가 같은 이벤트를 두 번 처리하지 않게 거르는 키다 — 같은 보상이 정당하게 여러 번 지급되므로
// (매일 출석, 트랙 초기화 뒤 재완료, 파산) 보상 ID 로는 거를 수 없다.
public record ExtraEvent(
        String eventId,
        ExtraEventType type,
        Long memberId,
        long occurredAt,
        RewardGranted rewardGranted,
        TierChanged tierChanged
) {

    // rewardId 는 기록용이다.
    public record RewardGranted(Long rewardId, long moneyAmount) {
    }

    public record TierChanged(String tier) {
    }

    public static ExtraEvent rewardGranted(Long memberId, RewardGranted payload) {
        return new ExtraEvent(newId(), ExtraEventType.REWARD_GRANTED, memberId, now(), payload, null);
    }

    public static ExtraEvent tierChanged(Long memberId, String tier) {
        return new ExtraEvent(newId(), ExtraEventType.TIER_CHANGED, memberId, now(), null, new TierChanged(tier));
    }

    public String key() {
        return String.valueOf(memberId);
    }

    public void validate() {
        if (eventId == null || type == null || memberId == null || !validForType()) {
            throw new InvalidExtraEventException("처리할 수 없는 부가 이벤트입니다. " + this);
        }
    }

    private boolean validForType() {
        return switch (type) {
            case REWARD_GRANTED -> rewardGranted != null && rewardGranted.moneyAmount() > 0;
            case TIER_CHANGED -> tierChanged != null && tierChanged.tier() != null;
        };
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
