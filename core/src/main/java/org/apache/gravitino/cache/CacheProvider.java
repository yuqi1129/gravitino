package org.apache.gravitino.cache;

public interface CacheProvider {
  String name();

  BidirectionalCacheService<?, ?> getBidirectionalCacheService(CacheConfig config);
}
