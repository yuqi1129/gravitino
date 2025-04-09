package org.apache.gravitino.cache;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

public class CacheFactory {
  private static final Map<String, CacheProvider> PROVIDERS = new ConcurrentHashMap<>();

  static {
    ServiceLoader<CacheProvider> loader = ServiceLoader.load(CacheProvider.class);
    for (CacheProvider provider : loader) {
      PROVIDERS.put(provider.name(), provider);
    }
  }

  public static BidirectionalCacheService<?, ?> getBidirectionalCacheService(String name, CacheConfig config) {
    CacheProvider provider = PROVIDERS.get(name);
    if (provider == null) {
      throw new IllegalArgumentException("No such cache provider: " + name);
    }
    return provider.getBidirectionalCacheService(config);
  }
}
