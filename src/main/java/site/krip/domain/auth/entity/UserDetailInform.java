package site.krip.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** 유저 상세 정보 — 2차 회원가입 결과. PK = FK(user_id), {@code users} 와 1:1, DB CASCADE. */
@Entity
@Table(name = "user_detail_inform", indexes = {
        @jakarta.persistence.Index(name = "ix_user_detail_email", columnList = "email")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDetailInform {

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "email", length = 255, nullable = false)
    private String email;

    @Column(name = "user_name", length = 100, nullable = false)
    private String userName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "age", nullable = false)
    private int age;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false)
    private Gender gender;

    @Column(name = "nationality", length = 50, nullable = false)
    private String nationality;

    @Column(name = "profile_image_url", length = 2048)
    private String profileImageUrl;

    public UserDetailInform(User user, String email, String userName, String phoneNumber,
                            int age, Gender gender, String nationality) {
        this.user = user;
        this.email = email;
        this.userName = userName;
        this.phoneNumber = phoneNumber;
        this.age = age;
        this.gender = gender;
        this.nationality = nationality;
    }

    // ──────────────────── 프로필 부분 수정 (포함된 필드만 호출) ────────────────────

    public void changeEmail(String email) {
        this.email = email;
    }

    public void changeUserName(String userName) {
        this.userName = userName;
    }

    public void changePhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public void changeAge(int age) {
        this.age = age;
    }

    public void changeGender(Gender gender) {
        this.gender = gender;
    }

    public void changeNationality(String nationality) {
        this.nationality = nationality;
    }

    public void changeProfileImageUrl(String url) {
        this.profileImageUrl = url;
    }
}
