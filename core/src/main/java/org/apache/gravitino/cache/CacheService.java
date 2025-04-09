package org.apache.gravitino.cache;

public interface CacheService<K, V> {

  V get(K key);
  void put(K key, V value);
  void remove(K key);
  boolean containsKey(K key);
  int size();

  /**
   * Delete all entries in the cache with the given prefix.
   * @param prefix The prefix to delete.
   * @return The number of entries deleted.
   */
  long deleteByPrefix(String prefix);
}
