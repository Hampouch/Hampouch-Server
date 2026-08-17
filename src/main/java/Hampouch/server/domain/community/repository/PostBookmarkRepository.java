package Hampouch.server.domain.community.repository;

import Hampouch.server.domain.community.entity.PostBookmark;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PostBookmarkRepository extends JpaRepository<PostBookmark, Long> {

    Optional<PostBookmark> findByPostIdAndUserId(Long postId, Long userId);

    boolean existsByPostIdAndUserId(Long postId, Long userId);

    List<PostBookmark> findByPostIdInAndUserId(List<Long> postIds, Long userId);

    @Query("""
            SELECT pb
            FROM PostBookmark pb
            WHERE pb.userId = :userId
            ORDER BY pb.createdAt DESC, pb.id DESC
            """)
    Slice<PostBookmark> findLatestByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("""
            SELECT pb
            FROM PostBookmark pb, Post p
            WHERE pb.userId = :userId
            AND p.id = pb.postId
            ORDER BY p.likeCount DESC, p.id DESC
            """)
    Slice<PostBookmark> findPopularByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("""
            SELECT pb
            FROM PostBookmark pb, Post p
            WHERE pb.userId = :userId
            AND p.id = pb.postId
            ORDER BY p.viewCount DESC, p.id DESC
            """)
    Slice<PostBookmark> findMostViewedByUserId(@Param("userId") Long userId, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PostBookmark pb WHERE pb.postId = :postId")
    void deleteAllByPostId(@Param("postId") Long postId);
}