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
@Table(name = "food_post_detail")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FoodPostDetail {

    @Id
    @Column(name = "post_id")
    private Long postId;

    @Column(nullable = false, length = 100)
    private String menu;

    @Column(nullable = false, length = 100)
    private String place;

    @Column(nullable = false)
    private int price;

    @Column(name = "taste_rating", nullable = false)
    private int tasteRating;

    @Column(name = "cost_rating", nullable = false)
    private int costRating;

    @Column(name = "mood_rating", nullable = false)
    private int moodRating;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private FoodPostDetail(Long postId, String menu, String place, int price,
                           int tasteRating, int costRating, int moodRating) {
        this.postId = postId;
        this.menu = menu;
        this.place = place;
        this.price = price;
        this.tasteRating = tasteRating;
        this.costRating = costRating;
        this.moodRating = moodRating;
    }

    public static FoodPostDetail create(Long postId, String menu, String place, int price,
                                        int tasteRating, int costRating, int moodRating) {
        return new FoodPostDetail(postId, menu, place, price, tasteRating, costRating, moodRating);
    }

    public void update(String menu, String place, int price, int tasteRating, int costRating, int moodRating) {
        this.menu = menu;
        this.place = place;
        this.price = price;
        this.tasteRating = tasteRating;
        this.costRating = costRating;
        this.moodRating = moodRating;
    }
}