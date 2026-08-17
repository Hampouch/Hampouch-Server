package Hampouch.server.domain.community.repository;

import Hampouch.server.domain.community.entity.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    Optional<PostLike> findByPostIdAndUserId(Long postId, Long userId);

    boolean existsByPostIdAndUserId(Long postId, Long userId);

    /** 목록 조회 시 여러 게시글에 대해 로그인 유저의 좋아요 여부를 한 번에 확인 (N+1 방지) */
    List<PostLike> findByPostIdInAndUserId(List<Long> postIds, Long userId);
}