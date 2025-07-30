package com.companya.realtime.service;

import com.companya.realtime.model.OutboundEvent;
import com.companya.realtime.repository.OutboundEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ResilienceMetricsService {

    private static final Logger logger = LoggerFactory.getLogger(ResilienceMetricsService.class);

    private final OutboundEventRepository outboundEventRepository;

    // Simple counters for tracking metrics
    private final AtomicLong poisonMessagesCount = new AtomicLong(0);
    private final AtomicLong vendorFailuresCount = new AtomicLong(0);
    private final AtomicLong databaseFailuresCount = new AtomicLong(0);
    private final AtomicLong retriesCount = new AtomicLong(0);

    public ResilienceMetricsService(OutboundEventRepository outboundEventRepository) {
        this.outboundEventRepository = outboundEventRepository;
    }

    public void recordPoisonMessage() {
        poisonMessagesCount.incrementAndGet();
        logger.debug("Recorded poison message metric - Total: {}", poisonMessagesCount.get());
    }

    public void recordVendorFailure() {
        vendorFailuresCount.incrementAndGet();
        logger.debug("Recorded vendor failure metric - Total: {}", vendorFailuresCount.get());
    }

    public void recordDatabaseFailure() {
        databaseFailuresCount.incrementAndGet();
        logger.debug("Recorded database failure metric - Total: {}", databaseFailuresCount.get());
    }

    public void recordRetry() {
        retriesCount.incrementAndGet();
        logger.debug("Recorded retry metric - Total: {}", retriesCount.get());
    }

    public long getPoisonMessagesCount() {
        return poisonMessagesCount.get();
    }

    public long getVendorFailuresCount() {
        return vendorFailuresCount.get();
    }

    public long getDatabaseFailuresCount() {
        return databaseFailuresCount.get();
    }

    public long getRetriesCount() {
        return retriesCount.get();
    }

    /**
     * Clean up old failed events that are beyond recovery
     * Runs daily at 2 AM
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupOldFailedEvents() {
        logger.info("Starting cleanup of old failed events");

        try {
            LocalDateTime cutoffDate = LocalDateTime.now().minus(7, ChronoUnit.DAYS);

            List<OutboundEvent> oldFailedEvents = outboundEventRepository
                    .findByStatusAndLastAttemptBefore(OutboundEvent.Status.FAILED, cutoffDate);

            if (!oldFailedEvents.isEmpty()) {
                logger.info("Cleaning up {} failed events older than 7 days", oldFailedEvents.size());
                outboundEventRepository.deleteAll(oldFailedEvents);
                logger.info("Successfully cleaned up {} old failed events", oldFailedEvents.size());
            } else {
                logger.info("No old failed events to clean up");
            }

        } catch (Exception ex) {
            logger.error("Failed to cleanup old failed events: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Log resilience statistics for monitoring
     * Runs every 5 minutes
     */
    @Scheduled(fixedRate = 300000)
    public void logResilienceStatistics() {
        try {
            int pending = outboundEventRepository.countByStatus(OutboundEvent.Status.PENDING);
            int sent = outboundEventRepository.countByStatus(OutboundEvent.Status.SENT);
            int failed = outboundEventRepository.countByStatus(OutboundEvent.Status.FAILED);

            // Calculate recent retry activity
            LocalDateTime recentTime = LocalDateTime.now().minus(5, ChronoUnit.MINUTES);
            List<OutboundEvent> recentRetries = outboundEventRepository
                    .findByStatusAndLastAttemptAfter(OutboundEvent.Status.PENDING, recentTime);

            long recentRetryCount = recentRetries.stream()
                    .mapToInt(OutboundEvent::getRetryCount)
                    .sum();

            logger.info("=== RESILIENCE STATISTICS ===");
            logger.info("Outbound Events - Pending: {}, Sent: {}, Failed: {}", pending, sent, failed);
            logger.info("Error Counts - Poison Messages: {}, Vendor Failures: {}, Database Failures: {}",
                       poisonMessagesCount.get(), vendorFailuresCount.get(), databaseFailuresCount.get());
            logger.info("Retry Activity - Total Retries: {}, Recent Retries (5min): {}",
                       retriesCount.get(), recentRetryCount);
            logger.info("==============================");

            // Alert if too many pending events
            if (pending > 100) {
                logger.warn("HIGH ALERT: {} pending outbound events detected - vendor may be down", pending);
            }

            // Alert if high failure rate
            if (failed > 50) {
                logger.warn("HIGH ALERT: {} failed events detected - check vendor integration", failed);
            }

        } catch (Exception ex) {
            logger.error("Failed to log resilience statistics: {}", ex.getMessage(), ex);
        }
    }
}
