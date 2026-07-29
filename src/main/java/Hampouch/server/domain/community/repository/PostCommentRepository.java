package Hampouch.server.domain.community.repository;

import Hampouch.server.domain.community.entity.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

    //상세 조회
    List<PostComment> findByPostIdOrderByCreatedAtAsc(Long postId);
}