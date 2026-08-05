package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.NoSpendDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface NoSpendDayRepository extends JpaRepository<NoSpendDay, Long> {

    boolean existsByUser_IdAndRecordDate(Long userId, LocalDate recordDate);

    void deleteByUser_IdAndRecordDate(Long userId, LocalDate recordDate);

    Optional<NoSpendDay> findTopByUser_IdOrderByRecordDateDesc(Long userId);
}
