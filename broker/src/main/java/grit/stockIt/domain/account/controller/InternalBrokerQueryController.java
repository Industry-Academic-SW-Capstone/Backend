package grit.stockIt.domain.account.controller;

import grit.stockIt.contract.BrokerQueryApi;
import grit.stockIt.domain.account.service.LocalBrokerQueryClient;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 부가 서비스의 동기 조회(BrokerQueryClient)에 HTTP 로 답한다. 내부망 전용 — 서비스별로 띄울 때만 켜고 게이트웨이에 열지 않는다.
@Hidden
@RestController
@RequestMapping(BrokerQueryApi.BASE)
@ConditionalOnProperty(name = "stockit.internal-api.enabled", havingValue = "true")
@RequiredArgsConstructor
public class InternalBrokerQueryController {

    private final LocalBrokerQueryClient brokerQueryClient;

    @GetMapping(BrokerQueryApi.DEFAULT_ACCOUNT_ASSET)
    public ResponseEntity<BrokerQueryApi.Asset> defaultAccountAsset(@PathVariable Long memberId) {
        return brokerQueryClient.defaultAccountAsset(memberId)
                .map(amount -> ResponseEntity.ok(new BrokerQueryApi.Asset(amount)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping(BrokerQueryApi.MEMBERS_HOLDING_STOCK)
    public BrokerQueryApi.MemberIds membersHoldingStock(@RequestBody BrokerQueryApi.MemberIds request) {
        return new BrokerQueryApi.MemberIds(List.copyOf(brokerQueryClient.membersHoldingStock(request.memberIds())));
    }

    @GetMapping(BrokerQueryApi.MEMBER_IDS)
    public BrokerQueryApi.MemberIds memberIds() {
        return new BrokerQueryApi.MemberIds(brokerQueryClient.allMemberIds());
    }
}
