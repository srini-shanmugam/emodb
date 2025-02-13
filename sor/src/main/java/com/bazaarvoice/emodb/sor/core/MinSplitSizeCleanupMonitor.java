package com.bazaarvoice.emodb.sor.core;

import com.bazaarvoice.curator.recipes.leader.LeaderService;
import com.bazaarvoice.emodb.common.dropwizard.guice.SelfHostAndPort;
import com.bazaarvoice.emodb.common.dropwizard.leader.LeaderServiceTask;
import com.bazaarvoice.emodb.common.dropwizard.lifecycle.LifeCycleRegistry;
import com.bazaarvoice.emodb.common.dropwizard.lifecycle.ManagedGuavaService;
import com.bazaarvoice.emodb.common.zookeeper.store.MapStore;
import com.bazaarvoice.emodb.sor.DataStoreZooKeeper;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.inject.Inject;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Leader elected moniotor that cleans up expired {@link DataStoreMinSplitSize}'s in Zookeeper.
 */
public class MinSplitSizeCleanupMonitor extends LeaderService {

    private static final String SERVICE_NAME = "min-split-size-cleanup-monitor";
    private static final String LEADER_DIR = "/leader/min-split-size";

    @Inject
    public MinSplitSizeCleanupMonitor(@DataStoreZooKeeper CuratorFramework curator, @SelfHostAndPort HostAndPort selfHostAndPort,
                                      LeaderServiceTask leaderServiceTask, LifeCycleRegistry lifecycle,
                                      @MinSplitSizeMap MapStore<DataStoreMinSplitSize> minSplitSizeMap, Clock clock) {
        super(curator, LEADER_DIR, selfHostAndPort.toString(), SERVICE_NAME, 1, TimeUnit.MINUTES,
                () -> new MinSplitSizeCleanupService(minSplitSizeMap, clock));
        leaderServiceTask.register(SERVICE_NAME, this);
        lifecycle.manage(new ManagedGuavaService(this));
    }

    private static class MinSplitSizeCleanupService extends AbstractScheduledService {

        private final Logger _log = LoggerFactory.getLogger(MinSplitSizeCleanupService.class);

        private final MapStore<DataStoreMinSplitSize> _minSplitSizeMap;
        private final Clock _clock;

        public MinSplitSizeCleanupService(MapStore<DataStoreMinSplitSize> minSplitSizeMap, Clock clock) {
            _minSplitSizeMap = requireNonNull(minSplitSizeMap);
            _clock = requireNonNull(clock);
        }

        @Override
        protected void runOneIteration() {
            try {
                Map<String, DataStoreMinSplitSize> minSplitSizes = _minSplitSizeMap.getAll();
                for (Map.Entry<String, DataStoreMinSplitSize> entry : minSplitSizes.entrySet()) {
                    if (entry.getValue().getExpirationTime().isBefore(_clock.instant())) {
                        _minSplitSizeMap.remove(entry.getKey());
                    }
                }
            } catch (Exception e) {
                _log.warn("Failed to cleanup expired min split sizes.", e);
            }
        }

        /**
         *
         */
        @Override
        protected Scheduler scheduler() {

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            return Scheduler.newFixedRateSchedule(
                    Duration.between(now, OffsetDateTime.of(now.toLocalDate().plusDays(1).atStartOfDay(), ZoneOffset.UTC)).getSeconds(),
                    Duration.ofDays(1).getSeconds(), TimeUnit.SECONDS);
        }
    }
}
