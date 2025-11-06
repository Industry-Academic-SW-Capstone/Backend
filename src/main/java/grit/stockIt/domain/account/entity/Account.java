package grit.stockIt.domain.account.entity;

import jakarta.persistence.*;
import lombok.*;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.global.common.BaseEntity; // 추가

@Entity
@Table(name = "account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false)
    private Contest contest;
}