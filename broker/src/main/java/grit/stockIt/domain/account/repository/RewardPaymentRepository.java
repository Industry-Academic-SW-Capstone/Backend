package grit.stockIt.domain.account.repository;

import grit.stockIt.domain.account.entity.RewardPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewardPaymentRepository extends JpaRepository<RewardPayment, Long> {

    List<RewardPayment> findByMemberId(Long memberId);
}
