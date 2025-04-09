package org.apache.gravitino.cache;

public interface BidirectionalCacheService<K, V> extends CacheService<K, V> {

  /**
   * Link the key and value together in the reverse direction.
   * @param key
   * @param value
   * @throws IllegalArgumentException
   */
  void link(K key, V value) throws IllegalArgumentException;

  K getKeyByValue(V value);

  /**
   * Unlink the value from the key.
   * @param value The value to unlink.
   */
  void unlink(V value);
}
