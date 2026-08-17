package Hampouch.server.domain.user.repository;

import Hampouch.server.domain.user.entity.NotificationSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationScheduleRepository extends JpaRepository<NotificationSchedule, Long> {
}
