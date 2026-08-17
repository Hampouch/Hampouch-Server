package Hampouch.server.domain.community.repository;

import Hampouch.server.domain.community.entity.PostImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostImageRepository extends JpaRepository<PostImage, Long> {

    List<PostImage> findByPostIdOrderBySortOrderAsc(Long postId);

    boolean existsByImageKey(String imageKey);

    // 썸네일용: 게시글마다 sortOrder가 가장 작은 이미지 1건만 조회
    @Query("""
        SELECT pi FROM PostImage pi
        WHERE pi.postId IN :postIds
        AND pi.sortOrder = (
            SELECT MIN(pi2.sortOrder) FROM PostImage pi2 WHERE pi2.postId = pi.postId
        )
        """)
    List<PostImage> findFirstImagesByPostIdIn(@Param("postIds") List<Long> postIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PostImage pi WHERE pi.postId = :postId")
    void deleteAllByPostId(@Param("postId") Long postId);
}