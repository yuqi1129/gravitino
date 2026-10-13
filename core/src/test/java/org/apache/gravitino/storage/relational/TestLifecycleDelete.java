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
package org.apache.gravitino.storage.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.cache.NoOpsCache;
import org.apache.gravitino.exceptions.CatalogInUseException;
import org.apache.gravitino.exceptions.MetalakeInUseException;
import org.apache.gravitino.exceptions.MetalakeNotInUseException;
import org.apache.gravitino.exceptions.NonEmptyEntityException;
import org.apache.gravitino.exceptions.OptimisticLockException;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.mapper.CatalogMetaMapper;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.mockito.Mockito;

/** Verifies lifecycle deletion against changes after the caller's preflight snapshot. */
public class TestLifecycleDelete extends TestJDBCBackend {
  private static final String METALAKE = "lifecycle_delete";
  private BaseMetalake metalake;
  private CatalogEntity catalog;
  private RelationalEntityStore store;

  @BeforeEach
  void prepare() throws Exception {
    metalake = createAndInsertMakeLake(METALAKE);
    CatalogEntity initial =
        createCatalog(
            RandomIdGenerator.INSTANCE.nextId(), Namespace.of(METALAKE), "catalog", AUDIT_INFO);
    catalog =
        CatalogEntity.builder()
            .withId(initial.id())
            .withName(initial.name())
            .withNamespace(initial.namespace())
            .withType(initial.getType())
            .withProvider(initial.getProvider())
            .withComment(initial.getComment())
            .withProperties(Map.of(Catalog.PROPERTY_IN_USE, "false"))
            .withAuditInfo(AUDIT_INFO)
            .build();
    backend.insert(catalog, false);
    store = new RelationalEntityStore();
    FieldUtils.writeField(store, "backend", backend, true);
    FieldUtils.writeField(store, "cache", new NoOpsCache(new Config(false) {}), true);
  }

  @TestTemplate
  void testEmptyCatalogDelete() throws IOException {
    assertTrue(store.deleteIfUnused(catalog, List.of()));
    assertFalse(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
    assertFalse(store.deleteIfUnused(catalog, List.of()));
  }

  @TestTemplate
  void testApprovedSchemaCleanup() throws IOException {
    SchemaEntity schema = schema("public");
    backend.insert(schema, false);
    assertTrue(store.deleteIfUnused(catalog, List.of(schema)));
    assertFalse(backend.exists(schema.nameIdentifier(), Entity.EntityType.SCHEMA));
  }

  @TestTemplate
  void testNewSchemaRejectsDelete() throws IOException {
    SchemaEntity schema = schema("new_schema");
    backend.insert(schema, false);
    assertThrows(NonEmptyEntityException.class, () -> store.deleteIfUnused(catalog, List.of()));
    assertTrue(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
    assertTrue(backend.exists(schema.nameIdentifier(), Entity.EntityType.SCHEMA));
  }

  @TestTemplate
  void testRecreatedApprovedSchemaRejectsDelete() throws IOException {
    SchemaEntity old = schema("public");
    backend.insert(old, false);
    backend.delete(old.nameIdentifier(), Entity.EntityType.SCHEMA, true);
    SchemaEntity replacement = schema("public");
    backend.insert(replacement, false);
    assertThrows(NonEmptyEntityException.class, () -> store.deleteIfUnused(catalog, List.of(old)));
    assertTrue(backend.exists(replacement.nameIdentifier(), Entity.EntityType.SCHEMA));
    assertTrue(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
  }

  @TestTemplate
  void testChangedApprovedSchemaRejectsDelete() throws IOException {
    SchemaEntity observed = schema("public");
    backend.insert(observed, false);
    backend.update(
        observed.nameIdentifier(),
        Entity.EntityType.SCHEMA,
        (SchemaEntity current) ->
            SchemaEntity.builder()
                .withId(current.id())
                .withNamespace(current.namespace())
                .withName(current.name())
                .withComment("changed")
                .withProperties(current.properties())
                .withAuditInfo(AUDIT_INFO)
                .build());
    assertThrows(
        OptimisticLockException.class, () -> store.deleteIfUnused(catalog, List.of(observed)));
    assertTrue(backend.exists(observed.nameIdentifier(), Entity.EntityType.SCHEMA));
  }

  @TestTemplate
  void testCatalogReenabledRejectsDelete() throws IOException {
    setCatalogEnabled(true);
    assertThrows(CatalogInUseException.class, () -> store.deleteIfUnused(catalog, List.of()));
    assertTrue(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
  }

  @TestTemplate
  void testParentDisabledRejectsCatalogDelete() throws IOException {
    setMetalakeEnabled(false);
    assertThrows(MetalakeNotInUseException.class, () -> store.deleteIfUnused(catalog, List.of()));
    assertTrue(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
  }

  @TestTemplate
  void testRecreatedCatalogRejectsDelete() throws IOException {
    backend.delete(catalog.nameIdentifier(), Entity.EntityType.CATALOG, true);
    CatalogEntity initial = catalog;
    CatalogEntity replacement =
        CatalogEntity.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName(initial.name())
            .withNamespace(initial.namespace())
            .withType(initial.getType())
            .withProvider(initial.getProvider())
            .withProperties(initial.getProperties())
            .withAuditInfo(AUDIT_INFO)
            .build();
    backend.insert(replacement, false);
    assertThrows(OptimisticLockException.class, () -> store.deleteIfUnused(catalog, List.of()));
    assertEquals(
        replacement.id(),
        backend.get(replacement.nameIdentifier(), Entity.EntityType.CATALOG).id());
  }

  @TestTemplate
  void testNonemptyMetalakeRejectsDelete() throws IOException {
    BaseMetalake observed = setMetalakeEnabled(false);
    assertThrows(NonEmptyEntityException.class, () -> store.deleteIfUnused(observed, List.of()));
    assertTrue(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
    assertTrue(backend.exists(metalake.nameIdentifier(), Entity.EntityType.METALAKE));
  }

  @TestTemplate
  void testMetalakeReenabledRejectsDelete() throws IOException {
    BaseMetalake observed = setMetalakeEnabled(false);
    setMetalakeEnabled(true);
    assertThrows(MetalakeInUseException.class, () -> store.deleteIfUnused(observed, List.of()));
  }

  @TestTemplate
  void testEmptyMetalakeDelete() throws IOException {
    backend.delete(catalog.nameIdentifier(), Entity.EntityType.CATALOG, true);
    BaseMetalake observed = setMetalakeEnabled(false);
    assertTrue(store.deleteIfUnused(observed, List.of()));
    assertFalse(store.deleteIfUnused(observed, List.of()));
  }

  @TestTemplate
  void testNewSchemaLeavesApprovedSchemaAndTableIntact() throws IOException {
    SchemaEntity approved = schema("public");
    backend.insert(approved, false);
    TableEntity table =
        createAndInsertTableEntity(
            Namespace.of(METALAKE, catalog.name(), approved.name()), "table");
    backend.insert(schema("late_schema"), false);
    assertThrows(
        NonEmptyEntityException.class, () -> store.deleteIfUnused(catalog, List.of(approved)));
    assertEquals(table.id(), backend.get(table.nameIdentifier(), Entity.EntityType.TABLE).id());
    assertTrue(backend.exists(approved.nameIdentifier(), Entity.EntityType.SCHEMA));
  }

  @TestTemplate
  void testRemovedApprovedSchemaAllowsDelete() throws IOException {
    SchemaEntity approved = schema("public");
    backend.insert(approved, false);
    backend.delete(approved.nameIdentifier(), Entity.EntityType.SCHEMA, true);
    assertTrue(store.deleteIfUnused(catalog, List.of(approved)));
  }

  @TestTemplate
  void testChangedCatalogRejectsDelete() throws IOException {
    backend.update(
        catalog.nameIdentifier(),
        Entity.EntityType.CATALOG,
        (CatalogEntity current) ->
            CatalogEntity.builder()
                .withId(current.id())
                .withName(current.name())
                .withNamespace(current.namespace())
                .withType(current.getType())
                .withProvider(current.getProvider())
                .withComment("changed")
                .withProperties(current.getProperties())
                .withAuditInfo(AUDIT_INFO)
                .build());
    assertThrows(OptimisticLockException.class, () -> store.deleteIfUnused(catalog, List.of()));
    assertTrue(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
  }

  @TestTemplate
  void testRecreatedMetalakeRejectsDelete() throws IOException {
    BaseMetalake observed = setMetalakeEnabled(false);
    backend.delete(metalake.nameIdentifier(), Entity.EntityType.METALAKE, true);
    BaseMetalake replacement =
        BaseMetalake.builder()
            .withId(RandomIdGenerator.INSTANCE.nextId())
            .withName(observed.name())
            .withVersion(observed.getVersion())
            .withComment(observed.comment())
            .withProperties(observed.properties())
            .withAuditInfo(AUDIT_INFO)
            .build();
    backend.insert(replacement, false);
    assertThrows(OptimisticLockException.class, () -> store.deleteIfUnused(observed, List.of()));
    assertEquals(
        replacement.id(),
        backend.get(replacement.nameIdentifier(), Entity.EntityType.METALAKE).id());
  }

  @TestTemplate
  void testSchemaCommittedWhileDeleteWaitsIsSeen() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    DeleteLockObserver observer = new DeleteLockObserver();
    SqlSessionFactoryHelper.getInstance()
        .getSqlSessionFactory()
        .getConfiguration()
        .addInterceptor(observer);
    Future<Boolean> deletion;
    try {
      SessionUtils.beginTransaction();
      try {
        SessionUtils.getWithoutCommit(
            CatalogMetaMapper.class, mapper -> mapper.selectCatalogMetaByIdForUpdate(catalog.id()));
        deletion =
            executor.submit(
                () -> {
                  observer.watchedThread = Thread.currentThread();
                  return store.deleteIfUnused(catalog, List.of());
                });
        assertTrue(observer.lockRequested.await(10, TimeUnit.SECONDS));
        backend.insert(schema("committed_while_waiting"), false);
        SessionUtils.commitTransaction();
      } catch (Throwable e) {
        SessionUtils.rollbackTransaction();
        throw e;
      }
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> deletion.get(15, TimeUnit.SECONDS));
      assertInstanceOf(NonEmptyEntityException.class, failure.getCause());
      assertTrue(backend.exists(catalog.nameIdentifier(), Entity.EntityType.CATALOG));
    } finally {
      observer.watchedThread = null;
      executor.shutdownNow();
    }
  }

  @TestTemplate
  void testUnsupportedStoreDoesNotFallBackToCascade() throws IOException {
    EntityStore unsupported = Mockito.mock(EntityStore.class, Mockito.CALLS_REAL_METHODS);
    assertThrows(
        UnsupportedOperationException.class, () -> unsupported.deleteIfUnused(catalog, List.of()));
    Mockito.verify(unsupported, Mockito.never())
        .delete(Mockito.any(), Mockito.any(), Mockito.anyBoolean());
  }

  @Intercepts(
      @Signature(
          type = Executor.class,
          method = "query",
          args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
  private static class DeleteLockObserver implements Interceptor {
    private final CountDownLatch lockRequested = new CountDownLatch(1);
    @Nullable private volatile Thread watchedThread;

    /** {@inheritDoc} */
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
      if (Thread.currentThread() == watchedThread
          && statement
              .getId()
              .equals(CatalogMetaMapper.class.getName() + ".selectCatalogMetaByIdForUpdate")) {
        // The initial read has already established the delete transaction's snapshot. The other
        // writer now commits a child while the delete is about to wait on the parent row.
        lockRequested.countDown();
      }
      return invocation.proceed();
    }
  }

  private SchemaEntity schema(String name) {
    return SchemaEntity.builder()
        .withId(RandomIdGenerator.INSTANCE.nextId())
        .withName(name)
        .withNamespace(Namespace.of(METALAKE, catalog.name()))
        .withComment("approved")
        .withProperties(Map.of())
        .withAuditInfo(AUDIT_INFO)
        .build();
  }

  private BaseMetalake setMetalakeEnabled(boolean enabled) throws IOException {
    return backend.update(
        metalake.nameIdentifier(),
        Entity.EntityType.METALAKE,
        (BaseMetalake current) ->
            BaseMetalake.builder()
                .withId(current.id())
                .withName(current.name())
                .withComment(current.comment())
                .withVersion(current.getVersion())
                .withProperties(Map.of(Catalog.PROPERTY_IN_USE, String.valueOf(enabled)))
                .withAuditInfo(AUDIT_INFO)
                .build());
  }

  private void setCatalogEnabled(boolean enabled) throws IOException {
    backend.update(
        catalog.nameIdentifier(),
        Entity.EntityType.CATALOG,
        (CatalogEntity current) ->
            CatalogEntity.builder()
                .withId(current.id())
                .withName(current.name())
                .withNamespace(current.namespace())
                .withType(current.getType())
                .withProvider(current.getProvider())
                .withComment(current.getComment())
                .withProperties(Map.of(Catalog.PROPERTY_IN_USE, String.valueOf(enabled)))
                .withAuditInfo(AUDIT_INFO)
                .build());
  }
}
