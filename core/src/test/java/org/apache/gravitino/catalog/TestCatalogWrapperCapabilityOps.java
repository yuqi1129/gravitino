/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.catalog;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;
import org.apache.gravitino.utils.IsolatedClassLoader;
import org.apache.gravitino.utils.ThrowableFunction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestCatalogWrapperCapabilityOps {

  @Test
  void testCloseWaitsForCapabilityOps() throws Exception {
    BaseCatalog mockCatalog = Mockito.mock(BaseCatalog.class);
    Mockito.when(mockCatalog.capability()).thenReturn(Capability.DEFAULT);
    IsolatedClassLoader mockClassLoader = mockClassLoader();
    CatalogManager.CatalogWrapper wrapper =
        new CatalogManager.CatalogWrapper(mockCatalog, mockClassLoader);

    CountDownLatch capabilityStarted = new CountDownLatch(1);
    CountDownLatch releaseCapability = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Void> capabilityFuture =
          executor.submit(
              () -> {
                wrapper.doWithCapabilityOps(
                    capability -> {
                      capabilityStarted.countDown();
                      releaseCapability.await(5, TimeUnit.SECONDS);
                      return null;
                    });
                return null;
              });

      Assertions.assertTrue(capabilityStarted.await(5, TimeUnit.SECONDS));
      Future<Void> closeFuture =
          executor.submit(
              () -> {
                wrapper.close();
                return null;
              });

      Assertions.assertFalse(closeFuture.isDone());
      releaseCapability.countDown();
      capabilityFuture.get(5, TimeUnit.SECONDS);
      closeFuture.get(5, TimeUnit.SECONDS);

      Assertions.assertThrows(
          IllegalStateException.class, () -> wrapper.doWithCapabilityOps(capability -> null));
      Mockito.verify(mockClassLoader, Mockito.times(1)).close();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void testWithCapabilityRetriesClosedWrapper() throws Exception {
    IsolatedClassLoader firstClassLoader = mockClassLoader();
    BaseCatalog firstCatalog = Mockito.mock(BaseCatalog.class);
    CatalogManager.CatalogWrapper closedWrapper =
        new CatalogManager.CatalogWrapper(firstCatalog, firstClassLoader);
    closedWrapper.close();

    IsolatedClassLoader secondClassLoader = mockClassLoader();
    BaseCatalog secondCatalog = Mockito.mock(BaseCatalog.class);
    Mockito.when(secondCatalog.capability()).thenReturn(Capability.DEFAULT);
    CatalogManager.CatalogWrapper openWrapper =
        new CatalogManager.CatalogWrapper(secondCatalog, secondClassLoader);

    CatalogManager catalogManager = Mockito.mock(CatalogManager.class);
    NameIdentifier catalogIdent = NameIdentifier.of("metalake", "catalog");
    Mockito.when(catalogManager.loadCatalogAndWrap(catalogIdent))
        .thenReturn(closedWrapper, openWrapper);

    CapabilityResult result =
        CapabilityHelpers.withCapability(
            NameIdentifier.of("metalake", "catalog", "schema"),
            catalogManager,
            Capability::columnNotNull);

    Assertions.assertTrue(result.supported());
    Mockito.verify(catalogManager, Mockito.times(2)).loadCatalogAndWrap(catalogIdent);
  }

  private IsolatedClassLoader mockClassLoader() throws Exception {
    IsolatedClassLoader mockClassLoader = Mockito.mock(IsolatedClassLoader.class);
    Mockito.when(mockClassLoader.withClassLoader(Mockito.any()))
        .thenAnswer(
            invocation ->
                ((ThrowableFunction<ClassLoader, ?>) invocation.getArgument(0))
                    .apply(Thread.currentThread().getContextClassLoader()));
    return mockClassLoader;
  }
}
