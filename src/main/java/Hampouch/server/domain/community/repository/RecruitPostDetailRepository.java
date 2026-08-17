package Hampouch.server.domain.community.repository;

import Hampouch.server.domain.community.entity.RecruitPostDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RecruitPostDetailRepository extends JpaRepository<RecruitPostDetail, Long> {

    Optional<RecruitPostDetail> findByPostId(Long postId);
}