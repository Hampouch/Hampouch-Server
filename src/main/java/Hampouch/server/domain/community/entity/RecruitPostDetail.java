package Hampouch.server.domain.community.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "recruit_post_detail")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecruitPostDetail {

    @Id
    @Column(name = "post_id")
    private Long postId;

    @Column(name = "battle_id", nullable = false)
    private Long battleId;

    @Column(name = "battle_url", nullable = false, length = 500)
    private String battleUrl;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private RecruitPostDetail(Long postId, Long battleId, String battleUrl) {
        this.postId = postId;
        this.battleId = battleId;
        this.battleUrl = battleUrl;
    }

    public static RecruitPostDetail create(Long postId, Long battleId, String battleUrl) {
        return new RecruitPostDetail(postId, battleId, battleUrl);
    }

    public void update(Long battleId, String battleUrl) {
        this.battleId = battleId;
        this.battleUrl = battleUrl;
    }
}