package grit.stockIt.domain.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "users")  // 'user'는 예약어일 수 있으므로 'users'로 테이블명 지정
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String nickname;

    private Long money = 0L;  // 보유 금액

    // UserTitle과의 양방향 관계 설정
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    private List<UserTitle> userTitles = new ArrayList<>();

    // 현재 사용 중인 칭호
    @OneToOne
    private UserTitle currentTitle;

    // 필요한 비즈니스 메서드
    public void addMoney(Long amount) {
        this.money += amount;
    }

    public void changeCurrentTitle(UserTitle title) {
        if (this.userTitles.contains(title)) {
            this.currentTitle = title;
        }
    }
}