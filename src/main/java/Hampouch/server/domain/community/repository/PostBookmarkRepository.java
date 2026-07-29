package Hampouch.server.domain.community.repository;

import Hampouch.server.domain.community.entity.PostBookmark;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PostBookmarkRepository extends JpaRepository<PostBookmark, Long> {

    Optional<PostBookmark> findByPostIdAndUserId(Long postId, Long userId);

    boolean existsByPostIdAndUserId(Long postId, Long userId);

    List<PostBookmark> findByPostIdInAndUserId(List<Long> postIds, Long userId);

    /** 저장한 글 조회 (마이페이지) - bookmarkedAt(=createdAt) 최신순 */
    Page<PostBookmark> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}