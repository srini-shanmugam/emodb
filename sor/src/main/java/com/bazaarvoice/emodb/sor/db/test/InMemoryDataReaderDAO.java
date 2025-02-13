package com.bazaarvoice.emodb.sor.db.test;

import com.bazaarvoice.emodb.common.api.impl.LimitCounter;
import com.bazaarvoice.emodb.common.uuid.TimeUUIDs;
import com.bazaarvoice.emodb.sor.api.Change;
import com.bazaarvoice.emodb.sor.api.ChangeBuilder;
import com.bazaarvoice.emodb.sor.api.Compaction;
import com.bazaarvoice.emodb.sor.api.History;
import com.bazaarvoice.emodb.sor.api.ReadConsistency;
import com.bazaarvoice.emodb.sor.api.WriteConsistency;
import com.bazaarvoice.emodb.sor.core.HistoryStore;
import com.bazaarvoice.emodb.sor.core.test.InMemoryHistoryStore;
import com.bazaarvoice.emodb.sor.db.DataReaderDAO;
import com.bazaarvoice.emodb.sor.db.DataWriterDAO;
import com.bazaarvoice.emodb.sor.db.Key;
import com.bazaarvoice.emodb.sor.db.MultiTableScanOptions;
import com.bazaarvoice.emodb.sor.db.MultiTableScanResult;
import com.bazaarvoice.emodb.sor.db.Record;
import com.bazaarvoice.emodb.sor.db.RecordEntryRawMetadata;
import com.bazaarvoice.emodb.sor.db.RecordUpdate;
import com.bazaarvoice.emodb.sor.db.ScanRange;
import com.bazaarvoice.emodb.sor.db.ScanRangeSplits;
import com.bazaarvoice.emodb.sor.delta.Delta;
import com.bazaarvoice.emodb.table.db.Table;
import com.bazaarvoice.emodb.table.db.TableSet;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * In-memory implementation of {@link DataWriterDAO}, for testing.
 */
public class InMemoryDataReaderDAO implements DataReaderDAO, DataWriterDAO {

    public final Map<String, NavigableMap<String, Map<UUID, Change>>> _contentChanges = Maps.newHashMap();
    private int _fullConsistencyDelayMillis = Integer.MAX_VALUE;
    private Long _fullConsistencyTimestamp;
    private int _columnBatchSize = 50;
    public HistoryStore _historyStore;

    public InMemoryDataReaderDAO() {
        _historyStore = new InMemoryHistoryStore();
    }

    public InMemoryDataReaderDAO setFullConsistencyDelayMillis(int fullConsistencyDelayMillis) {
        _fullConsistencyDelayMillis = fullConsistencyDelayMillis;
        _fullConsistencyTimestamp = null;
        return this;
    }

    public InMemoryDataReaderDAO setHistoryStore(HistoryStore historyStore) {
        _historyStore = requireNonNull(historyStore);
        return this;
    }

    public InMemoryDataReaderDAO setFullConsistencyTimestamp(long fullConsistencyTimestamp) {
        _fullConsistencyTimestamp = fullConsistencyTimestamp;
        return this;
    }

    @Override
    public long getFullConsistencyTimestamp(Table table) {
        return _fullConsistencyTimestamp != null ?
                _fullConsistencyTimestamp :
                System.currentTimeMillis() - _fullConsistencyDelayMillis;
    }

    @Override
    public long getRawConsistencyTimestamp(Table table) {
        return getFullConsistencyTimestamp(table);
    }

    public void setColumnBatchSize(int columnBatchSize) {
        checkArgument(columnBatchSize >= 1, "columnBatchSize < 1");
        _columnBatchSize = columnBatchSize;
    }

    @Override
    public long count(Table table, ReadConsistency consistency) {
        return safeGet(_contentChanges, table.getName()).size();
    }

    @Override
    public long count(Table table, @Nullable Integer limit, ReadConsistency consistency) {
        int actual = safeGet(_contentChanges, table.getName()).size();
        return limit == null ? actual : Math.min(limit, actual);
    }

    @Override
    public Record read(Key key, ReadConsistency ignored) {
        requireNonNull(key, "key");
        String table = key.getTable().getName();
        return newRecord(key, safeGet(_contentChanges, table, key.getKey()));
    }

    @Override
    public Iterator<Record> readAll(Collection<Key> keys, final ReadConsistency consistency) {
        return Iterators.transform(keys.iterator(), new Function<Key, Record>() {
            @Override
            public Record apply(Key key) {
                return read(key, consistency);
            }
        });
    }

    @Override
    public Iterator<Change> readTimeline(Key key, boolean includeContentData,
                                         UUID start, UUID end, boolean reversed, long limit, ReadConsistency consistency) {
        requireNonNull(key, "key");

        String table = key.getTable().getName();

        Ordering<UUID> ordering = reversed ? TimeUUIDs.ordering().reverse() : TimeUUIDs.ordering();
        NavigableMap<UUID, Change> map = Maps.newTreeMap(ordering);
        if (includeContentData) {
            map.putAll(safeGet(_contentChanges, table, key.getKey()));
        }
        if (start != null) {
            map = map.tailMap(start, true);
        }
        if (end != null) {
            map = map.headMap(end, true);
        }
        return Iterators.limit(map.values().iterator(), (int) Math.min(limit, Integer.MAX_VALUE));
    }

    @Override
    public Iterator<Change> getExistingHistories(Key key, UUID start, UUID end, ReadConsistency consistency) {
        return Collections.emptyIterator();
    }

    @Override
    public Iterator<Record> scan(final Table table, @Nullable String fromKeyExclusive, LimitCounter limit, ReadConsistency ignored) {
        NavigableMap<String, Map<UUID, Change>> map = safeGet(_contentChanges, table.getName())
                .tailMap(Strings.nullToEmpty(fromKeyExclusive), false);
        return Iterators.transform(map.entrySet().iterator(), new Function<Map.Entry<String, Map<UUID, Change>>, Record>() {
            @Override
            public Record apply(Map.Entry<String, Map<UUID, Change>> entry) {
                return newRecord(new Key(table, entry.getKey()), entry.getValue());
            }
        });
    }

    @Override
    public ScanRangeSplits getScanRangeSplits(String placement, int desiredRecordsPerSplit, Optional<ScanRange> subrange) {
        return ScanRangeSplits.builder()
                .addScanRange("dummy_group", "dummy_host", subrange.orElse(ScanRange.all()))
                .build();
    }

    @Override
    public String getPlacementCluster(String placement) {
        return "process";
    }

    @Override
    public Iterator<MultiTableScanResult> multiTableScan(MultiTableScanOptions query, TableSet tables, LimitCounter limit,
                                                         ReadConsistency consistency, @Nullable Instant cutoffTime) {
        // TODO:  Create a simulation for this method
        return Collections.emptyIterator();
    }

    @Override
    public List<String> getSplits(Table table, int recordsPerSplit, int localResplits) throws TimeoutException {

        List<String> splits = Lists.newArrayList();
        String start = "";
        int count = 0;
        int splitSize = recordsPerSplit / ((int) Math.pow(2, localResplits));
        for (String key : safeGet(_contentChanges, table.getName()).keySet()) {
            if (++count == splitSize) {
                splits.add(encodeSplit(start, key));
                start = key;
                count = 0;
            }
        }

        if (count > 0) {
            splits.add(encodeSplit(start, ""));
        }

        return splits;
    }

    @Override
    public Iterator<Record> getSplit(final Table table, String split, @Nullable String fromKeyExclusive, LimitCounter limit, ReadConsistency consistency) {
        checkArgument(split.startsWith("s"), "bad split");
        NavigableMap<String, Map<UUID, Change>> map = constrain(safeGet(_contentChanges, table.getName()),
                decodeSplitStart(split), Strings.emptyToNull(decodeSplitEnd(split)));
        return Iterators.transform(map.entrySet().iterator(), new Function<Map.Entry<String, Map<UUID, Change>>, Record>() {
            @Override
            public Record apply(Map.Entry<String, Map<UUID, Change>> entry) {
                return newRecord(new Key(table, entry.getKey()), entry.getValue());
            }
        });
    }

    private <V> NavigableMap<String, V> constrain(NavigableMap<String, V> map, String fromExclusive, @Nullable String toInclusive) {
        if (toInclusive == null) {
            return map.tailMap(fromExclusive, false);
        } else {
            return map.subMap(fromExclusive, false, toInclusive, true);
        }
    }

    private String encodeSplit(String start, String end) {
        return "S" + encodeHex(start) + "E" + encodeHex(end);
    }

    private String decodeSplitStart(String split) {
        checkArgument(split.startsWith("S") && split.contains("E"));
        return decodeHex(split.substring(1, split.indexOf('E')));
    }

    private String decodeSplitEnd(String split) {
        checkArgument(split.startsWith("S") && split.contains("E"));
        return decodeHex(split.substring(split.indexOf('E') + 1));
    }

    @Override
    public void updateAll(Iterator<RecordUpdate> updates, UpdateListener listener) {
        while (updates.hasNext()) {
            RecordUpdate update = updates.next();
            listener.beforeWrite(Collections.singleton(update));
            update(update.getTable(), update.getKey(), update.getChangeId(), update.getDelta(), update.getTags(), update.getConsistency());
            listener.afterWrite(Collections.singleton(update));
        }
    }

    private synchronized void update(Table table, String key, UUID changeId, Delta delta, Set<String> tags, WriteConsistency ignored) {
        requireNonNull(table, "table");
        requireNonNull(key, "key");
        requireNonNull(changeId, "changeId");
        requireNonNull(delta, "delta");

        safePut(_contentChanges, table.getName(), key, changeId, ChangeBuilder.just(changeId, delta, tags));
    }

    @Override
    public synchronized void compact(Table table, String key, UUID compactionKey, Compaction compaction,
                                     UUID changeId, Delta delta, Collection<DeltaClusteringKey> changesToDelete, List<History> historyList, WriteConsistency consistency) {
        requireNonNull(table, "table");
        requireNonNull(key, "key");
        requireNonNull(compactionKey, "compactionKey");
        requireNonNull(compaction, "compaction");
        requireNonNull(changeId, "changeId");
        requireNonNull(delta, "delta");
        requireNonNull(changesToDelete, "changesToDelete");

        Map<UUID, Change> changes = safePut(_contentChanges, table.getName(), key);

        deleteDeltas(changesToDelete, changes);

        // add the compaction record and update the last content of the last delta
        addCompaction(compactionKey, compaction, changeId, delta, changes);

        // Add delta histories
        if (historyList != null && !historyList.isEmpty()) {
            _historyStore.putDeltaHistory(table.getName(), key, historyList);
        }
    }

    public synchronized void deleteDeltasOnly(Table table, String key, UUID compactionKey, Compaction compaction,
                                              UUID changeId, Delta delta, Collection<DeltaClusteringKey> changesToDelete, List<History> historyList, WriteConsistency consistency) {
        requireNonNull(table, "table");
        requireNonNull(key, "key");
        requireNonNull(compactionKey, "compactionKey");
        requireNonNull(compaction, "compaction");
        requireNonNull(changeId, "changeId");
        requireNonNull(delta, "delta");
        requireNonNull(changesToDelete, "changesToDelete");

        Map<UUID, Change> changes = safePut(_contentChanges, table.getName(), key);

        // delete the old deltas & compaction records
        deleteDeltas(changesToDelete, changes);

    }

    public synchronized void addCompactionOnly(Table table, String key, UUID compactionKey, Compaction compaction,
                                               UUID changeId, Delta delta, Collection<DeltaClusteringKey> changesToDelete, List<History> historyList, WriteConsistency consistency) {
        requireNonNull(table, "table");
        requireNonNull(key, "key");
        requireNonNull(compactionKey, "compactionKey");
        requireNonNull(compaction, "compaction");
        requireNonNull(changeId, "changeId");
        requireNonNull(delta, "delta");
        requireNonNull(changesToDelete, "changesToDelete");

        Map<UUID, Change> changes = safePut(_contentChanges, table.getName(), key);

        // add the compaction record and update the last content of the last delta
        addCompaction(compactionKey, compaction, changeId, delta, changes);

        // Add delta histories
        if (historyList != null && !historyList.isEmpty()) {
            _historyStore.putDeltaHistory(table.getName(), key, historyList);
        }

    }

    protected void addCompaction(UUID compactionKey, Compaction compaction, UUID changeId, Delta delta, Map<UUID, Change> changes) {
        changes.put(compactionKey, ChangeBuilder.just(compactionKey, compaction));
    }

    protected void deleteDeltas(Collection<DeltaClusteringKey> changesToDelete, Map<UUID, Change> changes) {
        for (DeltaClusteringKey change : changesToDelete) {
            changes.remove(change.getChangeId());
        }
    }

    @Override
    public void storeCompactedDeltas(Table tbl, String key, List<History> histories, WriteConsistency consistency) {

    }

    @Override
    public void purgeUnsafe(Table table) {
        _contentChanges.remove(table.getName());
    }

    private NavigableMap<String, Map<UUID, Change>> safeGet(Map<String, NavigableMap<String, Map<UUID, Change>>> map, String key) {
        NavigableMap<String, Map<UUID, Change>> map2 = map.get(key);
        return (map2 != null) ? map2 : ImmutableSortedMap.of();
    }

    private Map<UUID, Change> safeGet(Map<String, NavigableMap<String, Map<UUID, Change>>> map, String key1, String key2) {
        Map<UUID, Change> map2 = safeGet(map, key1).get(key2);
        return (map2 != null) ? map2 : Collections.emptyMap();
    }

    private void safePut(Map<String, NavigableMap<String, Map<UUID, Change>>> map,
                         String key1, String key2, UUID key3, Change value) {
        safePut(map, key1, key2).put(key3, value);
    }

    protected Map<UUID, Change> safePut(Map<String, NavigableMap<String, Map<UUID, Change>>> map, String key1, String key2) {
        NavigableMap<String, Map<UUID, Change>> map2 = map.get(key1);
        if (map2 == null) {
            map.put(key1, map2 = Maps.newTreeMap());
        }
        Map<UUID, Change> map3 = map2.get(key2);
        if (map3 == null) {
            map2.put(key2, map3 = Maps.newHashMap());
        }
        return map3;
    }

    private Record newRecord(final Key key, Map<UUID, Change> changes) {
        // Detach from the original change map so that we don't see changes to the shared map made after this call.
        final SortedMap<UUID, Change> sortedChanges = TimeUUIDs.sortedCopy(changes);

        return new Record() {
            @Override
            public Key getKey() {
                return key;
            }

            @Override
            public Iterator<Map.Entry<DeltaClusteringKey, Compaction>> passOneIterator() {
                return FluentIterable.from(getChangeIterable())
                        .transform(new Function<Map.Entry<UUID, Change>, Change>() {
                            @Override
                            public Change apply(Map.Entry<UUID, Change> entry) {
                                return entry.getValue();
                            }
                        })
                        .filter(new Predicate<Change>() {
                            @Override
                            public boolean apply(Change change) {
                                return change.getCompaction() != null;
                            }
                        })
                        .transform(new Function<Change, Map.Entry<DeltaClusteringKey, Compaction>>() {
                            @Override
                            public Map.Entry<DeltaClusteringKey, Compaction> apply(Change change) {
                                return Maps.immutableEntry(new DeltaClusteringKey(change.getId(), 1), change.getCompaction());
                            }
                        })
                        .iterator();
            }

            @Override
            public Iterator<Map.Entry<DeltaClusteringKey, Change>> passTwoIterator() {
                return Iterators.transform(getChangeIterable().iterator(), entry -> Maps.immutableEntry(new DeltaClusteringKey(entry.getKey(), 1), entry.getValue()));
            }

            @Override
            public Iterator<RecordEntryRawMetadata> rawMetadata() {
                Iterator<Map.Entry<UUID, Change>> changeIter = getChangeIterable().iterator();
                return Iterators.transform(changeIter, new Function<Map.Entry<UUID, Change>, RecordEntryRawMetadata>() {
                    @Override
                    public RecordEntryRawMetadata apply(Map.Entry<UUID, Change> entry) {
                        return new RecordEntryRawMetadata()
                                .withTimestamp(TimeUUIDs.getTimeMillis(entry.getKey()))
                                .withSize(1);  // Size is a measure of the raw data, which the in-memory implementation does not have.
                        // Return a constant value.
                    }
                });
            }

            private Iterable<Map.Entry<UUID, Change>> getChangeIterable() {
                // Simulate the AstyanaxDataReaderDAO's behavior of reading the first _columnBatchSize columns synchronously,
                // then lazily loading the remaining columns.
                if (sortedChanges.size() < _columnBatchSize) {
                    return sortedChanges.entrySet();
                }

                // Get the element at the "batch size" index position.
                final UUID exclusiveFrom = FluentIterable.from(Iterables.limit(sortedChanges.entrySet(), _columnBatchSize))
                        .get(_columnBatchSize - 1).getKey();
                return Iterables.concat(
                        Iterables.limit(sortedChanges.entrySet(), _columnBatchSize),
                        new Iterable<Map.Entry<UUID, Change>>() {
                            @Override
                            public Iterator<Map.Entry<UUID, Change>> iterator() {
                                Map<UUID, Change> currentChanges = safeGet(_contentChanges, key.getTable().getName(), key.getKey());
                                return FluentIterable.from(getSortedCopy(currentChanges).entrySet())
                                        .filter(new Predicate<Map.Entry<UUID, Change>>() {
                                            @Override
                                            public boolean apply(Map.Entry<UUID, Change> entry) {
                                                return TimeUUIDs.compare(entry.getKey(), exclusiveFrom) > 0;
                                            }
                                        })
                                        .iterator();
                            }
                        }
                );
            }

            private SortedMap<UUID, Change> getSortedCopy(Map<UUID, Change> changes) {
                SortedMap<UUID, Change> sortedCopy = null;
                while (sortedCopy == null) {
                    try {
                        sortedCopy = TimeUUIDs.sortedCopy(changes);
                    } catch (ConcurrentModificationException e) {
                        // Back off for a random number of ms and try again
                        try {
                            Thread.sleep((int) (Math.random() * 250));
                        } catch (InterruptedException e2) { /* ignore */ }
                    }
                }
                return sortedCopy;
            }

            @Override
            public String toString() {
                return key.toString(); // for debugging
            }
        };
    }

    private String encodeHex(String string) {
        return Hex.encodeHexString(string.getBytes(Charsets.UTF_8));
    }

    private String decodeHex(String string) {
        try {
            return new String(Hex.decodeHex(string.toCharArray()), Charsets.UTF_8);
        } catch (DecoderException e) {
            throw new IllegalArgumentException("Invalid hex string: " + string);
        }
    }
}
