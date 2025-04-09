package org.apache.gravitino.cache;

import org.apache.gravitino.NameIdentifier;

public class CaffeineCacheProvider implements CacheProvider {
  @Override
  public String name() {
    return "Caffeine";
  }

  @Override
  public BidirectionalCacheService<?, ?> getBidirectionalCacheService(CacheConfig config) {
    return new CaffeineBidirectionalCacheService<NameIdentifier, Long>(config);
  }
}
