package Hampouch.server.domain.community.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "post_image",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_post_image_post_sort",
                columnNames = {"post_id", "sort_order"}
        )
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_image_id")
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "image_url", nullable = false, length = 1000)
    private String imageUrl;

    @Column(name = "image_key", nullable = false, length = 500)
    private String imageKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private PostImage(Long postId, String imageUrl, String imageKey, int sortOrder) {
        this.postId = postId;
        this.imageUrl = imageUrl;
        this.imageKey = imageKey;
        this.sortOrder = sortOrder;
    }

    public static PostImage create(Long postId, String imageUrl, String imageKey, int sortOrder) {
        return new PostImage(postId, imageUrl, imageKey, sortOrder);
    }
}