package grit.stockIt.domain.title.entity;

import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "title")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Title extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "title_id")
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    // (필요시 칭호 아이콘 이미지 경로 등을 추가할 수 있습니다)
    // @Column(name = "icon_url")
    // private String iconUrl;

    @Builder
    public Title(String name, String description) {
        this.name = name;
        this.description = description;
    }
}