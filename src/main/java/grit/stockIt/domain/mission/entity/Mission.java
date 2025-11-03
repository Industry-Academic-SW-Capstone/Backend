package grit.stockIt.domain.mission.entity;

import grit.stockIt.domain.mission.entity.MissionType;
import grit.stockIt.domain.mission.entity.ValidationType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class Mission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long missionId; // 미션 ID 기획과 매칭

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MissionType missionType; // 일일, 주간 미션

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ValidationType validationType; // 클라이언트가 완료처리 OR 서버의 처리

    @Column(nullable = false)
    private String title; // 미션 주제

    private String description; // 미션 설명

    @Column(nullable = false)
    private int requiredCount = 1; // 목표 횟수 (예: 분할매수 3회)

    private Long rewardId; // 보상 정보 (별도 테이블 FK)
}