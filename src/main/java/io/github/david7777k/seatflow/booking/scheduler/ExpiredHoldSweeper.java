package io.github.david7777k.seatflow.booking.scheduler;

import io.github.david7777k.seatflow.booking.service.HoldExpiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link HoldExpiryService} on a schedule.
 *
 * <p>Separate from the service so that scheduling and the work itself can be
 * reasoned about apart: the service is transactional and testable on its own,
 * this class only decides when and how often to call it.
 */
@Component
@ConditionalOnProperty(name = "seatflow.booking.expiry-sweep.enabled", matchIfMissing = true)
public class ExpiredHoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpiredHoldSweeper.class);

    private final HoldExpiryService holdExpiryService;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public ExpiredHoldSweeper(
            HoldExpiryService holdExpiryService,
            @Value("${seatflow.booking.expiry-sweep.batch-size:100}") int batchSize,
            @Value("${seatflow.booking.expiry-sweep.max-batches-per-run:20}") int maxBatchesPerRun) {
        this.holdExpiryService = holdExpiryService;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}: the gap is measured from the
     * end of one run to the start of the next, so a slow sweep cannot have the
     * next one launched on top of it.
     *
     * <p>Work is bounded per run. A backlog is worked through over several runs
     * rather than in one sweep that holds locks and a connection for as long as
     * the backlog happens to be.
     */
    @Scheduled(
            fixedDelayString = "${seatflow.booking.expiry-sweep.interval:PT30S}",
            initialDelayString = "${seatflow.booking.expiry-sweep.initial-delay:PT10S}")
    public void sweep() {
        int totalExpired = 0;

        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            int expired;
            try {
                expired = holdExpiryService.expireBatch(batchSize);
            } catch (RuntimeException ex) {
                // A failed sweep must not kill the scheduler. The next run
                // picks up whatever was left.
                log.error("Hold expiry sweep failed after {} holds", totalExpired, ex);
                return;
            }

            totalExpired += expired;

            if (expired < batchSize) {
                break;
            }
        }

        if (totalExpired > 0) {
            log.debug("Sweep expired {} holds", totalExpired);
        }
    }
}
