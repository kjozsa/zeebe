/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.db.impl.rocksdb;

import io.zeebe.db.ZeebeDbFactory;
import io.zeebe.db.impl.rocksdb.transaction.ZeebeTransactionDb;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.agrona.CloseHelper;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionPriority;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.DataBlockIndexType;
import org.rocksdb.IndexType;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Statistics;
import org.rocksdb.StatsLevel;
import org.rocksdb.TableFormatConfig;

public final class ZeebeRocksDbFactory<ColumnFamilyType extends Enum<ColumnFamilyType>>
    implements ZeebeDbFactory<ColumnFamilyType> {

  static {
    RocksDB.loadLibrary();
  }

  private final Class<ColumnFamilyType> columnFamilyTypeClass;
  private final Properties userProvidedColumnFamilyOptions;

  private ZeebeRocksDbFactory(
      final Class<ColumnFamilyType> columnFamilyTypeClass,
      final Properties userProvidedColumnFamilyOptions) {
    this.columnFamilyTypeClass = columnFamilyTypeClass;
    this.userProvidedColumnFamilyOptions = Objects.requireNonNull(userProvidedColumnFamilyOptions);
  }

  public static <ColumnFamilyType extends Enum<ColumnFamilyType>>
      ZeebeDbFactory<ColumnFamilyType> newFactory(
          final Class<ColumnFamilyType> columnFamilyTypeClass) {
    final var columnFamilyOptions = new Properties();
    return new ZeebeRocksDbFactory<>(columnFamilyTypeClass, columnFamilyOptions);
  }

  public static <ColumnFamilyType extends Enum<ColumnFamilyType>>
      ZeebeDbFactory<ColumnFamilyType> newFactory(
          final Class<ColumnFamilyType> columnFamilyTypeClass,
          final Properties userProvidedColumnFamilyOptions) {
    return new ZeebeRocksDbFactory<>(columnFamilyTypeClass, userProvidedColumnFamilyOptions);
  }

  @Override
  public ZeebeTransactionDb<ColumnFamilyType> createDb(final File pathName) {
    final ZeebeTransactionDb<ColumnFamilyType> db;
    final List<AutoCloseable> closeables = new ArrayList<>();
    try {
      // column family options have to be closed as last
      final var columnFamilyOptions = createColumnFamilyOptions(closeables);
      closeables.add(columnFamilyOptions);
      final var dbOptions = createDefaultDbOptions(closeables);
      closeables.add(dbOptions);

      final var options = new Options(dbOptions, columnFamilyOptions);
      closeables.add(options);

      db =
          ZeebeTransactionDb.openTransactionalDb(
              options, pathName.getAbsolutePath(), closeables, columnFamilyTypeClass);

    } catch (final RocksDBException e) {
      CloseHelper.quietCloseAll(closeables);
      throw new IllegalStateException("Unexpected error occurred trying to open the database", e);
    }
    return db;
  }

  private DBOptions createDefaultDbOptions(final List<AutoCloseable> closeables) {
    final var statistics = new Statistics();
    closeables.add(statistics);
    statistics.setStatsLevel(StatsLevel.ALL);

    return new DBOptions()
        .setErrorIfExists(false)
        .setCreateIfMissing(true)
        .setParanoidChecks(true)
        // 1 flush, 1 compaction
        .setMaxBackgroundJobs(2)
        // we only use the default CF
        .setCreateMissingColumnFamilies(false)
        // may not be necessary when WAL is disabled, but nevertheless recommended to avoid
        // many small SST files
        .setAvoidFlushDuringRecovery(true)
        // fsync is called asynchronously once we have at least 4Mb
        .setBytesPerSync(4 * 1024 * 1024L)
        // limit the size of the manifest (logs all operations), otherwise it will grow
        // unbounded
        .setMaxManifestFileSize(256 * 1024 * 1024L)
        // speeds up opening the DB
        .setSkipStatsUpdateOnDbOpen(true)
        // keep 1 hour of logs - completely arbitrary. we should keep what we think would be
        // a good balance between useful for performance and small for replication
        .setLogFileTimeToRoll(Duration.ofMinutes(30).toSeconds())
        .setKeepLogFileNum(2)
        // can be disabled when not profiling
        .setStatsDumpPeriodSec(20)
        .setStatistics(statistics);
  }

  /** @return Options which are used on all column families */
  ColumnFamilyOptions createColumnFamilyOptions(final List<AutoCloseable> closeables) {

    final var hasUserOptions = !userProvidedColumnFamilyOptions.isEmpty();

    if (hasUserOptions) {
      return createFromUserOptions(userProvidedColumnFamilyOptions);
    }

    return createDefaultColumnFamilyOptions(closeables);
  }

  private ColumnFamilyOptions createFromUserOptions(
      final Properties userProvidedColumnFamilyOptions) {
    final var columnFamilyOptions =
        ColumnFamilyOptions.getColumnFamilyOptionsFromProps(userProvidedColumnFamilyOptions);
    if (columnFamilyOptions == null) {
      throw new IllegalStateException(
          String.format(
              "Expected to create column family options for RocksDB, "
                  + "but one or many values are undefined in the context of RocksDB "
                  + "[User-provided ColumnFamilyOptions: %s]. "
                  + "See RocksDB's cf_options.h and options_helper.cc for available keys and values.",
              userProvidedColumnFamilyOptions));
    }
    return columnFamilyOptions;
  }

  private ColumnFamilyOptions createDefaultColumnFamilyOptions(
      final List<AutoCloseable> closeables) {
    final var columnFamilyOptions = new ColumnFamilyOptions();

    // given
    final var totalMemoryBudget = 512 * 1024 * 1024L; // TODO: make this configurable
    // recommended by RocksDB, but we could tweak it; keep in mind we're also caching the indexes
    // and filters into the block cache, so we don't need to account for more memory there
    final var blockCacheMemory = totalMemoryBudget / 3;
    // flushing the memtables is done asynchronously, so there may be multiple memtables in memory,
    // although only a single one is writable. once we have too many memtables, writes will stop.
    // since prefix iteration is our bread n butter, we will build an additional filter for each
    // memtable which takes a bit of memory which must be accounted for from the memtable's memory
    final var maxConcurrentMemtableCount = 6;
    // this is a current guess and candidate for further tuning
    // values can be between 0 and 0.25 (anything higher gets clamped to 0.25), we randomly picked
    // 0.15
    // prefix seek must be fast, so we allocate some extra memory of a single memtable budget to
    // create
    // a filter for each memtable, allowing us to skip the prefixes if possible
    final var memtablePrefixFilterMemory = 0.15;
    final var memtableMemory =
        Math.round(
            ((totalMemoryBudget - blockCacheMemory) / (double) maxConcurrentMemtableCount)
                * (1 - memtablePrefixFilterMemory));

    final var tableConfig = createTableFormatConfig(closeables, blockCacheMemory);

    return columnFamilyOptions
        // to extract our column family type (used as prefix) and seek faster
        .useFixedLengthPrefixExtractor(Long.BYTES)
        .setMemtablePrefixBloomSizeRatio(memtablePrefixFilterMemory)
        // memtables
        // merge at least 3 memtables per L0 file, otherwise all memtables are flushed as individual
        // files
        // this is also a candidate for tuning, it was a rough guess
        .setMinWriteBufferNumberToMerge(Math.min(3, maxConcurrentMemtableCount))
        .setMaxWriteBufferNumberToMaintain(maxConcurrentMemtableCount)
        .setMaxWriteBufferNumber(maxConcurrentMemtableCount)
        .setWriteBufferSize(memtableMemory)
        // compaction
        .setLevelCompactionDynamicLevelBytes(true)
        .setCompactionPriority(CompactionPriority.OldestSmallestSeqFirst)
        .setCompactionStyle(CompactionStyle.LEVEL)
        // L-0 means immediately flushed memtables
        .setLevel0FileNumCompactionTrigger(maxConcurrentMemtableCount)
        .setLevel0SlowdownWritesTrigger(
            maxConcurrentMemtableCount + (maxConcurrentMemtableCount / 2))
        .setLevel0StopWritesTrigger(maxConcurrentMemtableCount * 2)
        // configure 4 levels: L1 = 32mb, L2 = 320mb, L3 = 3.2Gb, L4 >= 3.2Gb
        // level 1 and 2 are uncompressed, level 3 and above are compressed using a CPU-cheap
        // compression algo. compressed blocks are stored in the OS page cache, and uncompressed in
        // the LRUCache created above. note L0 is always uncompressed
        .setNumLevels(4)
        .setMaxBytesForLevelBase(32 * 1024 * 1024L)
        .setMaxBytesForLevelMultiplier(10)
        .setCompressionPerLevel(
            List.of(
                CompressionType.NO_COMPRESSION,
                CompressionType.NO_COMPRESSION,
                CompressionType.LZ4_COMPRESSION,
                CompressionType.LZ4_COMPRESSION))
        // defines the desired SST file size (but not guaranteed, it is usually lower)
        // L0 => 8Mb, L1 => 16Mb, L2 => 32Mb, L3 => 64Mb
        // as levels get bigger, we want to have a good balance between the number of files and the
        // individual file sizes
        .setTargetFileSizeBase(8 * 1024 * 1024L)
        .setTargetFileSizeMultiplier(2)
        // misc
        .setTableFormatConfig(tableConfig);
  }

  private TableFormatConfig createTableFormatConfig(
      final List<AutoCloseable> closeables, final long blockCacheMemory) {
    // you can use the perf context to check if we're often blocked on the block cache mutex, in
    // which case we want to increase the number of shards (shard count == 2^shardBits)
    final var cache = new LRUCache(blockCacheMemory, 8, false, 0.15);
    closeables.add(cache);

    final var filter = new BloomFilter(10, false);
    closeables.add(filter);

    return new BlockBasedTableConfig()
        .setBlockCache(cache)
        // increasing block size means reducing memory usage, but increasing read iops
        .setBlockSize(32 * 1024L)
        // full and partitioned filters use a more efficient bloom filter implementation when
        // using format 5
        .setFormatVersion(5)
        .setFilterPolicy(filter)
        // caching and pinning indexes and filters is important to keep reads/seeks fast when we
        // have many memtables, and pinning them ensures they are never evicted from the block
        // cache
        .setCacheIndexAndFilterBlocks(true)
        .setPinL0FilterAndIndexBlocksInCache(true)
        .setCacheIndexAndFilterBlocksWithHighPriority(true)
        // default is binary search, but all of our scans are prefix based which is a good use
        // case for efficient hashing
        .setIndexType(IndexType.kHashSearch)
        .setDataBlockIndexType(DataBlockIndexType.kDataBlockBinaryAndHash)
        // RocksDB dev benchmarks show improvements when this is between 0.5 and 1, so let's
        // start with the middle and optimize later from there
        .setDataBlockHashTableUtilRatio(0.75)
        // while we mostly care about the prefixes, these are covered below by the
        // setMemtablePrefixBloomSizeRatio which will create a separate index for prefixes, so
        // keeping the whole keys in the prefixes is still useful for efficient gets. think of
        // it as a two-tiered index
        .setWholeKeyFiltering(true);
  }
}
