package io.zeebe.journal;

import org.agrona.DirectBuffer;

public interface Journal {

  long ASQN_IGNORE = -1L;
  /**
   * Appends a new {@link JournalRecord} that contains data. applicationSqNum is a sequence number
   * provided by the application. If applicationSqNum is not equal to ASQN_IGNORE, it must be
   * greater than the applicationSqNum of the previous record.
   *
   * @param applicationSqNum A sequence number provided by the application.
   * @param data
   * @return the journal record that was appended
   */
  JournalRecord append(long applicationSqNum, DirectBuffer data);

  /**
   * Appends a {@link JournalRecord}. If the index of the record is not the next expected index, the
   * append will fail.
   *
   * <p>TODO: Add specific exception for index mismatch, checksum violation etc.
   *
   * @param record
   * @throws Exception
   */
  void append(JournalRecord record) throws Exception;

  /**
   * Delete all records after indexExclusive. After a call to this method, {@link
   * Journal#getLastIndex()} should return indexExlusive.
   *
   * @param indexExclusive
   */
  void deleteAfter(long indexExclusive);

  /**
   * Attempts to delete all records until indexExclusive. The records may be immediately deleted or
   * marked to be deleted later depending on the implementation.
   *
   * @param indexExclusive the index until which will be deleted. The record at this index is not
   *     deleted.
   */
  void deleteUntil(long indexExclusive);

  /**
   * Delete all records in the journal and reset the next index to nextIndex. The following calls to
   * {@link Journal#append(long, DirectBuffer)} will append at index nextIndex.
   *
   * @param nextIndex
   */
  void reset(long nextIndex);

  /**
   * Returns the index of last appended record.
   *
   * @return
   */
  long getLastIndex();

  /**
   * Returns the index of the first record.
   *
   * @return
   */
  long getFirstIndex();

  /**
   * Check if the journal is empty.
   *
   * @return true if empty, false otherwise.
   */
  boolean isEmpty();

  /**
   * Depending on the implementation, appends to the journal may not be immediately pushed to the
   * persistent storage. A call to this method guarantees that all records written are safely stored
   * to the persistent storage backend.
   */
  void flush();

  /**
   * Opens a new {@link JournalReader}
   *
   * @return a journal reader
   */
  JournalReader openReader();

  /** Closes the journal. */
  void close();

  boolean isOpen();
}
