package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.NoSpendDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NoSpendDayRepository extends JpaRepository<NoSpendDay, Long> {

    boolean existsByUser_IdAndRecordDate(Long userId, LocalDate recordDate);

    void deleteByUser_IdAndRecordDate(Long userId, LocalDate recordDate);

    Optional<NoSpendDay> findTopByUser_IdOrderByRecordDateDesc(Long userId);

    /** Challenge 도메인의 캘린더 조회용 — [start,end]에서 오늘은 안 썼어요로 선언된 날짜만 골라낸다. */
    List<NoSpendDay> findByUser_IdAndRecordDateBetween(Long userId, LocalDate start, LocalDate end);
}
