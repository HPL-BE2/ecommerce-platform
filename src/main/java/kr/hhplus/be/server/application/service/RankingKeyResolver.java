package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.domain.model.RankingPeriod;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.Locale;

@Component
public class RankingKeyResolver {
    private static final DateTimeFormatter DAILY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTHLY_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final WeekFields WEEK_FIELDS = WeekFields.ISO;

    public String resolveKey(RankingPeriod period, OffsetDateTime timestamp) {
        LocalDate date = timestamp != null ? timestamp.toLocalDate() : LocalDate.now();
        return resolveKey(period, date);
    }

    public String resolveKey(RankingPeriod period, LocalDate referenceDate) {
        LocalDate date = referenceDate != null ? referenceDate : LocalDate.now();
        return switch (period) {
            case REALTIME -> "ranking:product:realtime";
            case DAILY -> "ranking:product:daily:" + DAILY_FMT.format(date);
            case WEEKLY -> {
                int week = date.get(WEEK_FIELDS.weekOfWeekBasedYear());
                int year = date.get(WEEK_FIELDS.weekBasedYear());
                yield String.format(Locale.ROOT, "ranking:product:weekly:%d-W%02d", year, week);
            }
            case MONTHLY -> "ranking:product:monthly:" + MONTHLY_FMT.format(date);
        };
    }
}
