package Hampouch.server.domain.expense.entity;

import Hampouch.server.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "no_spend_day",
        uniqueConstraints = @UniqueConstraint(name = "uq_no_spend_day_user_date", columnNames = {"user_id", "record_date"})
)
@EntityListeners(AuditingEntityListener.class)
public class NoSpendDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "no_spend_day_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private NoSpendDay(User user, LocalDate recordDate) {
        this.user = user;
        this.recordDate = recordDate;
    }

    public static NoSpendDay of(User user, LocalDate recordDate) {
        return new NoSpendDay(user, recordDate);
    }
}
