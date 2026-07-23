package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.CustomCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * find-or-create 진입점 — (user_id, name) 유니크 제약과 짝을 이루는 조회 쿼리.
 * 서비스 계층에서 이걸로 먼저 찾고, 없을 때만 CustomCategory.of()로 새로 만든다.
 */
public interface CustomCategoryRepository extends JpaRepository<CustomCategory, Long> {

    Optional<CustomCategory> findByUser_IdAndName(Long userId, String name);
}
