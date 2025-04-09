package org.apache.gravitino.cache;

import com.github.benmanes.caffeine.cache.Cache;

public class CaffeineBidirectionalCacheService<K, V> implements BidirectionalCacheService<K, V> {

  private final Cache<K, V> cache;
  private final Cache<V, K> reverseCache;

  // Init cache and reverseCache
  public CaffeineBidirectionalCacheService(CacheConfig config) {
    this.cache = null;
    this.reverseCache = null;
  }

  @Override
  public V get(K key) {
    return null;
  }

  @Override
  public void put(K key, V value) {

  }

  @Override
  public void remove(K key) {

  }

  @Override
  public boolean containsKey(K key) {
    return false;
  }

  @Override
  public int size() {
    return 0;
  }

  @Override
  public long deleteByPrefix(String prefix) {
    return 0;
  }

  @Override
  public void link(K key, V value) throws IllegalArgumentException {

  }

  @Override
  public K getKeyByValue(V value) {
    return null;
  }

  @Override
  public void unlink(V value) {

  }
}
