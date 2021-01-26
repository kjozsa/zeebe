/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.journal.file;

import static org.assertj.core.api.Assertions.assertThat;

import io.atomix.utils.serializer.Namespace;
import io.atomix.utils.serializer.Namespaces;
import io.zeebe.journal.JournalRecord;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SegmentedJournalTest {

  @TempDir Path directory;
  final DirectBuffer data = new UnsafeBuffer();
  private SegmentedJournal journal;
  private byte[] entry;
  private int entriesPerSegment;

  @BeforeEach
  public void setup() {
    final var namespace =
        new Namespace.Builder()
            .register(Namespaces.BASIC)
            .nextId(Namespaces.BEGIN_USER_CUSTOM_ID)
            .register(PersistedJournalRecord.class)
            .register(UnsafeBuffer.class)
            .name("Journal")
            .build();

    entry = "TestData".getBytes();
    data.wrap(entry);

    final var entrySize =
        namespace.serialize(new PersistedJournalRecord(1, 1, Integer.MAX_VALUE, data)).length
            + Integer.BYTES;
    entriesPerSegment = 10;

    journal =
        SegmentedJournal.builder()
            .withDirectory(directory.resolve("data").toFile())
            .withMaxSegmentSize(entriesPerSegment * entrySize + JournalSegmentDescriptor.BYTES)
            .build();
  }

  static Stream<Integer> entrySizeProvider() {
    return IntStream.range(1, 16).boxed();
  }

  @Test
  public void shouldBeEmpty() {
    // when-then
    assertThat(journal.isEmpty()).isTrue();
  }

  @Test
  public void shouldNotBeEmpty() {
    // given
    journal.append(1, data);

    // when-then
    assertThat(journal.isEmpty()).isFalse();
  }

  @Test
  public void shouldAppendData() {
    // given
    final var recordAppended = journal.append(1, data);
    assertThat(recordAppended.index()).isEqualTo(1);

    // when
    final var recordRead = journal.openReader().next();

    // then
    assertThat(recordAppended.index()).isEqualTo(recordRead.index());
    assertThat(recordAppended.asqn()).isEqualTo(recordRead.asqn());
    assertThat(recordAppended.checksum()).isEqualTo(recordRead.checksum());
  }

  @Test
  public void shouldAppendMultipleRecords() {
    for (int i = 0; i < 10; i++) {
      final var recordAppended = journal.append(i + 10, data);
      assertThat(recordAppended.index()).isEqualTo(i + 1);
    }

    final var reader = journal.openReader();
    for (int i = 0; i < 10; i++) {
      assertThat(reader.hasNext()).isTrue();
      final var recordRead = reader.next();
      assertThat(recordRead.index()).isEqualTo(i + 1);
      final byte[] data = new byte[recordRead.data().capacity()];
      recordRead.data().getBytes(0, data);
      assertThat(recordRead.asqn()).isEqualTo(i + 10);
      assertThat(data).containsExactly(entry);
    }
  }

  @Test
  public void shouldAppendAndReadMultipleRecords() {
    final var reader = journal.openReader();
    for (int i = 0; i < 10; i++) {
      // given
      entry = ("TestData" + i).getBytes();
      data.wrap(entry);

      // when
      final var recordAppended = journal.append(i + 10, data);
      assertThat(recordAppended.index()).isEqualTo(i + 1);

      // then
      assertThat(reader.hasNext()).isTrue();
      final var recordRead = reader.next();
      assertThat(recordRead.index()).isEqualTo(i + 1);
      final byte[] data = new byte[recordRead.data().capacity()];
      recordRead.data().getBytes(0, data);
      assertThat(recordRead.asqn()).isEqualTo(i + 10);
      assertThat(data).containsExactly(entry);
    }
  }

  @Test
  public void shouldReset() {
    // given
    long asqn = 1;
    assertThat(journal.getLastIndex()).isEqualTo(0);
    journal.append(asqn++, data);
    journal.append(asqn++, data);

    // when
    journal.reset(2);

    // then
    assertThat(journal.getLastIndex()).isEqualTo(1);
    final var record = journal.append(asqn++, data);
    assertThat(record.index()).isEqualTo(2);
  }

  @Test
  public void shouldResetWhileReading() {
    // given
    final var reader = journal.openReader();
    long asqn = 1;
    assertThat(journal.getLastIndex()).isEqualTo(0);
    journal.append(asqn++, data);
    journal.append(asqn++, data);
    final var record1 = reader.next();
    assertThat(record1.index()).isEqualTo(1);

    // when
    journal.reset(2);

    // then
    assertThat(journal.getLastIndex()).isEqualTo(1);
    final var record = journal.append(asqn++, data);
    assertThat(record.index()).isEqualTo(2);

    // then
    assertThat(reader.hasNext()).isTrue();
    final var record2 = reader.next();
    assertThat(record2.index()).isEqualTo(2);
    assertThat(record2.asqn()).isEqualTo(record.asqn());
  }

  @Test
  public void shouldTruncate() {
    // given
    final var reader = journal.openReader();
    assertThat(journal.getLastIndex()).isEqualTo(0);
    journal.append(1, data);
    journal.append(2, data);
    journal.append(3, data);
    final var record1 = reader.next();
    assertThat(record1.index()).isEqualTo(1);

    // when
    journal.deleteAfter(2);

    // then - should write after the truncated index
    assertThat(journal.getLastIndex()).isEqualTo(2);
    final var record = journal.append(4, data);
    assertThat(record.index()).isEqualTo(3);

    // then
    assertThat(reader.hasNext()).isTrue();
    final var record2 = reader.next();
    assertThat(record2.index()).isEqualTo(2);
    assertThat(record2.asqn()).isEqualTo(2);
    assertThat(reader.hasNext()).isTrue();

    // then - should not read truncated entry
    final var record3 = reader.next();
    assertThat(record3.index()).isEqualTo(3);
    assertThat(record3.asqn()).isEqualTo(4);
  }

  @Test
  public void shouldNotReadTruncatedEntries() {
    // given
    final int totalWrites = 10;
    final int truncateIndex = 5;
    int asqn = 1;
    final Map<Integer, JournalRecord> written = new HashMap<>();

    final var reader = journal.openReader();

    int writerIndex;
    for (writerIndex = 1; writerIndex <= totalWrites; writerIndex++) {
      final var record = journal.append(asqn++, data);
      assertThat(record.index()).isEqualTo(writerIndex);
      written.put(writerIndex, record);
    }

    int readerIndex;
    for (readerIndex = 1; readerIndex <= truncateIndex; readerIndex++) {
      assertThat(reader.hasNext()).isTrue();
      final var record = reader.next();
      assertThat(record.index()).isEqualTo(readerIndex);
      assertThat(record.asqn()).isEqualTo(written.get(readerIndex).asqn());
    }

    // when
    journal.deleteAfter(truncateIndex);

    for (writerIndex = truncateIndex + 1; writerIndex <= totalWrites; writerIndex++) {
      final var record = journal.append(asqn++, data);
      assertThat(record.index()).isEqualTo(writerIndex);
      written.put(writerIndex, record);
    }

    for (; readerIndex <= totalWrites; readerIndex++) {
      assertThat(reader.hasNext()).isTrue();
      final var record = reader.next();
      assertThat(record.index()).isEqualTo(readerIndex);
      assertThat(record.asqn()).isEqualTo(written.get(readerIndex).asqn());
    }
  }

  @Test
  public void shouldReadAfterCompact() {
    // given
    final var reader = journal.openReader();
    long asqn = 1;

    for (int i = 1; i <= entriesPerSegment * 5; i++) {
      assertThat(journal.append(asqn++, data).index()).isEqualTo(i);
    }
    assertThat(reader.hasNext()).isTrue();

    // when - compact up to the first index of segment 3
    final int indexToCompact = entriesPerSegment * 2 + 1;
    journal.deleteUntil(indexToCompact);

    // then
    assertThat(reader.hasNext()).isTrue();
    assertThat(reader.next().index()).isEqualTo(indexToCompact);
  }
}
