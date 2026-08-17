package Hampouch.server.domain.community.repository;

import Hampouch.server.domain.community.entity.FoodPostDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FoodPostDetailRepository extends JpaRepository<FoodPostDetail, Long> {

    Optional<FoodPostDetail> findByPostId(Long postId);
}