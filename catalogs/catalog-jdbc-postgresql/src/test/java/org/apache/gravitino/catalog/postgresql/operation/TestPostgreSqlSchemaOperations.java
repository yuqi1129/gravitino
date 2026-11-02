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
package org.apache.gravitino.catalog.postgresql.operation;

import com.google.common.collect.Maps;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.catalog.jdbc.utils.DataSourceUtils;
import org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
import org.apache.gravitino.utils.RandomNameUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.Driver;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("gravitino-docker-test")
public class TestPostgreSqlSchemaOperations extends TestPostgreSql {

  @Test
  void testDependencyCommittedWhileNativeDropWaitsIsPreserved() throws Exception {
    String schema = RandomNameUtils.genRandomName("cascade_waiting");
    String other = RandomNameUtils.genRandomName("cascade_waiting_other");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection observer = DATA_SOURCE.getConnection();
        Connection creator = DATA_SOURCE.getConnection();
        BasicDataSource source = newDataSource()) {
      source.setMaxTotal(1);
      int dropPid;
      try (Connection pooled = source.getConnection();
          Statement statement = pooled.createStatement();
          ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
        Assertions.assertTrue(rows.next());
        dropPid = rows.getInt(1);
        pooled.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      }
      JdbcConnectorUtils.executeUpdate(observer, "CREATE SCHEMA " + schema);
      JdbcConnectorUtils.executeUpdate(observer, "CREATE SCHEMA " + other);
      JdbcConnectorUtils.executeUpdate(observer, "CREATE VIEW " + schema + ".v AS SELECT 1 AS id");
      creator.setAutoCommit(false);
      try {
        // The uncommitted view holds a lock on the source, but its dependency is not yet visible
        // to a snapshot taken when the drop transaction starts.
        JdbcConnectorUtils.executeUpdate(
            creator, "CREATE VIEW " + other + ".v AS SELECT * FROM " + schema + ".v");
        PostgreSqlSchemaOperations operations = newOperations(source, observer.getCatalog());
        Future<Boolean> drop = executor.submit(() -> operations.delete(schema, true));
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () -> {
                  try (Statement statement = observer.createStatement();
                      ResultSet rows =
                          statement.executeQuery(
                              "SELECT wait_event_type FROM pg_stat_activity WHERE pid = "
                                  + dropPid)) {
                    return rows.next() && "Lock".equals(rows.getString(1));
                  }
                });
        creator.commit();
        ExecutionException failure =
            Assertions.assertThrows(ExecutionException.class, () -> drop.get(10, TimeUnit.SECONDS));
        Assertions.assertInstanceOf(NonEmptySchemaException.class, failure.getCause());
        Assertions.assertTrue(failure.getCause().getMessage().contains(other));
        try (Statement statement = observer.createStatement();
            ResultSet rows = statement.executeQuery("SELECT id FROM " + other + ".v")) {
          Assertions.assertTrue(rows.next());
          Assertions.assertEquals(1, rows.getInt(1));
        }
        try (Connection pooled = source.getConnection()) {
          Assertions.assertEquals(
              Connection.TRANSACTION_REPEATABLE_READ, pooled.getTransactionIsolation());
        }
      } finally {
        creator.rollback();
        executor.shutdownNow();
        Assertions.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        JdbcConnectorUtils.executeUpdate(observer, "DROP SCHEMA IF EXISTS " + other + " CASCADE");
        JdbcConnectorUtils.executeUpdate(observer, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  @Test
  void testValidationOnDifferentServerRollsBackDrop() throws Exception {
    String schema = RandomNameUtils.genRandomName("cascade_wrong_server");
    try (PostgreSQLContainer<?> otherServer =
            new PostgreSQLContainer<>(DEFAULT_POSTGRES_IMAGE)
                .withDatabaseName(TEST_DB_NAME)
                .withUsername(CONTAINER.getUsername())
                .withPassword(CONTAINER.getPassword());
        Connection connection = DATA_SOURCE.getConnection();
        BasicDataSource source = newDataSource()) {
      otherServer.start();
      AtomicReference<String> target = new AtomicReference<>(CONTAINER.getJdbcUrl());
      source.setDriver(
          new Driver() {
            /** {@inheritDoc} */
            @Override
            public Connection connect(String url, Properties properties) throws SQLException {
              return super.connect(target.get(), properties);
            }
          });
      // Warm the pooled DDL connection on the first server, then route independent connections to
      // another server, as a multi-host JDBC URL or a failover could do.
      try (Connection pooled = source.getConnection()) {
        Assertions.assertFalse(pooled.isClosed());
      }
      target.set(otherServer.getJdbcUrl());
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + schema);
      try {
        JdbcConnectorUtils.executeUpdate(connection, "CREATE TABLE " + schema + ".t (body text)");
        PostgreSqlSchemaOperations operations = newOperations(source, connection.getCatalog());
        GravitinoRuntimeException error =
            Assertions.assertThrows(
                GravitinoRuntimeException.class, () -> operations.delete(schema, true));
        SQLException cause = Assertions.assertInstanceOf(SQLException.class, error.getCause());
        Assertions.assertEquals("22023", cause.getSQLState());
        Assertions.assertTrue(cause.getMessage().contains("snapshot identifier"));
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT * FROM " + schema + ".t")) {
          Assertions.assertFalse(rows.next(), "Failed snapshot import must roll back the DROP");
        }
      } finally {
        JdbcConnectorUtils.executeUpdate(
            connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"name", "MixedCase", ".name"})
  void testCascadeDropQuotesSchemaIdentifier(String suffix) throws SQLException {
    String schema = RandomNameUtils.genRandomName("cascade_quoted") + suffix;
    String quoted = "\"" + schema.replace("\"", "\"\"") + "\"";
    try (Connection connection = DATA_SOURCE.getConnection()) {
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + quoted);
      try {
        JdbcConnectorUtils.executeUpdate(connection, "CREATE TABLE " + quoted + ".t (body text)");
        Assertions.assertTrue(DATABASE_OPERATIONS.delete(schema, true));
        Assertions.assertFalse(DATABASE_OPERATIONS.delete(schema, true));
      } finally {
        JdbcConnectorUtils.executeUpdate(
            connection, "DROP SCHEMA IF EXISTS " + quoted + " CASCADE");
      }
    }
  }

  @Test
  void testCascadeDropPreservesExternalExtensionMembers() throws SQLException {
    String schema = RandomNameUtils.genRandomName("cascade_extension_member");
    String other = RandomNameUtils.genRandomName("cascade_extension_owner");
    try (Connection connection = DATA_SOURCE.getConnection()) {
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + schema);
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + other);
      try {
        JdbcConnectorUtils.executeUpdate(
            connection, "CREATE EXTENSION hstore WITH SCHEMA " + other);
        JdbcConnectorUtils.executeUpdate(connection, "CREATE TABLE " + schema + ".t (id int)");
        JdbcConnectorUtils.executeUpdate(
            connection, "ALTER EXTENSION hstore ADD TABLE " + schema + ".t");
        NonEmptySchemaException error =
            Assertions.assertThrows(
                NonEmptySchemaException.class, () -> DATABASE_OPERATIONS.delete(schema, true));
        Assertions.assertTrue(error.getMessage().contains(other));
        try (Statement statement = connection.createStatement();
            ResultSet rows =
                statement.executeQuery("SELECT " + other + ".hstore('key', 'value')")) {
          Assertions.assertTrue(rows.next());
          Assertions.assertEquals("\"key\"=>\"value\"", rows.getString(1));
        }
      } finally {
        JdbcConnectorUtils.executeUpdate(connection, "DROP EXTENSION IF EXISTS hstore CASCADE");
        JdbcConnectorUtils.executeUpdate(connection, "DROP SCHEMA IF EXISTS " + other + " CASCADE");
        JdbcConnectorUtils.executeUpdate(
            connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testCascadeDropRespectsPartitionSchemaBoundaries(boolean externalChild) throws SQLException {
    String schema = RandomNameUtils.genRandomName("cascade_partition");
    String other = RandomNameUtils.genRandomName("cascade_partition_other");
    try (Connection connection = DATA_SOURCE.getConnection()) {
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + schema);
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + other);
      try {
        String parent = (externalChild ? schema : other) + ".parent";
        String child = (externalChild ? other : schema) + ".child";
        JdbcConnectorUtils.executeUpdate(
            connection, "CREATE TABLE " + parent + " (id int) PARTITION BY RANGE (id)");
        JdbcConnectorUtils.executeUpdate(
            connection,
            "CREATE TABLE " + child + " PARTITION OF " + parent + " FOR VALUES FROM (0) TO (100)");
        if (externalChild) {
          NonEmptySchemaException error =
              Assertions.assertThrows(
                  NonEmptySchemaException.class, () -> DATABASE_OPERATIONS.delete(schema, true));
          Assertions.assertTrue(error.getMessage().contains(other));
          JdbcConnectorUtils.executeUpdate(connection, "INSERT INTO " + parent + " VALUES (1)");
          try (Statement statement = connection.createStatement();
              ResultSet rows = statement.executeQuery("SELECT id FROM " + child)) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals(1, rows.getInt(1));
          }
        } else {
          Assertions.assertTrue(DATABASE_OPERATIONS.delete(schema, true));
          try (Statement statement = connection.createStatement();
              ResultSet rows = statement.executeQuery("SELECT * FROM " + parent)) {
            Assertions.assertFalse(rows.next(), "The external partition parent must remain usable");
          }
        }
      } finally {
        JdbcConnectorUtils.executeUpdate(connection, "DROP SCHEMA IF EXISTS " + other + " CASCADE");
        JdbcConnectorUtils.executeUpdate(
            connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  @Test
  void testCascadeDropPreservesExternalColumnDefault() throws SQLException {
    String schema = RandomNameUtils.genRandomName("cascade_sequence");
    String other = RandomNameUtils.genRandomName("cascade_default");
    try (Connection connection = DATA_SOURCE.getConnection()) {
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + schema);
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + other);
      try {
        JdbcConnectorUtils.executeUpdate(connection, "CREATE SEQUENCE " + schema + ".seq");
        JdbcConnectorUtils.executeUpdate(
            connection,
            "CREATE TABLE " + other + ".child (id bigint DEFAULT nextval('" + schema + ".seq'))");
        NonEmptySchemaException error =
            Assertions.assertThrows(
                NonEmptySchemaException.class, () -> DATABASE_OPERATIONS.delete(schema, true));
        Assertions.assertTrue(error.getMessage().contains(other));
        try (Statement statement = connection.createStatement();
            ResultSet rows =
                statement.executeQuery(
                    "INSERT INTO " + other + ".child DEFAULT VALUES RETURNING id")) {
          Assertions.assertTrue(rows.next());
          Assertions.assertEquals(1, rows.getLong(1), "The external default must still be active");
        }
        JdbcConnectorUtils.executeUpdate(connection, "DROP SCHEMA " + other + " CASCADE");
        Assertions.assertTrue(DATABASE_OPERATIONS.delete(schema, true));
      } finally {
        JdbcConnectorUtils.executeUpdate(connection, "DROP SCHEMA IF EXISTS " + other + " CASCADE");
        JdbcConnectorUtils.executeUpdate(
            connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  @Test
  void testSchemaOwnerCanDropAnotherRolesTableWithOneSlotPool() throws SQLException {
    String schema = RandomNameUtils.genRandomName("cascade_owned");
    String schemaOwner = RandomNameUtils.genRandomName("schema_owner");
    String tableOwner = RandomNameUtils.genRandomName("table_owner");
    try (Connection connection = DATA_SOURCE.getConnection()) {
      JdbcConnectorUtils.executeUpdate(connection, "CREATE ROLE " + schemaOwner);
      JdbcConnectorUtils.executeUpdate(connection, "CREATE ROLE " + tableOwner);
      try {
        JdbcConnectorUtils.executeUpdate(
            connection, "CREATE SCHEMA " + schema + " AUTHORIZATION " + schemaOwner);
        JdbcConnectorUtils.executeUpdate(
            connection, "GRANT CREATE ON SCHEMA " + schema + " TO " + tableOwner);
        JdbcConnectorUtils.executeUpdate(connection, "SET ROLE " + tableOwner);
        try {
          JdbcConnectorUtils.executeUpdate(connection, "CREATE TABLE " + schema + ".t (body text)");
        } finally {
          JdbcConnectorUtils.executeUpdate(connection, "RESET ROLE");
        }
        try (BasicDataSource ownerSource = newDataSource()) {
          ownerSource.setMaxTotal(1);
          ownerSource.setConnectionInitSqls(List.of("SET ROLE " + schemaOwner));
          try (Connection owner = ownerSource.getConnection();
              Statement statement = owner.createStatement();
              ResultSet rows =
                  statement.executeQuery(
                      "SELECT current_user, rolsuper, has_table_privilege(current_user, '"
                          + schema
                          + ".t', 'SELECT') FROM pg_roles WHERE rolname = current_user")) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals(schemaOwner, rows.getString(1));
            Assertions.assertFalse(rows.getBoolean(2));
            Assertions.assertFalse(rows.getBoolean(3));
          }
          PostgreSqlSchemaOperations operations =
              newOperations(ownerSource, connection.getCatalog());
          Assertions.assertTrue(operations.delete(schema, true));
          Assertions.assertEquals(0, ownerSource.getNumActive());
        }
      } finally {
        JdbcConnectorUtils.executeUpdate(connection, "RESET ROLE");
        JdbcConnectorUtils.executeUpdate(
            connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        JdbcConnectorUtils.executeUpdate(connection, "DROP ROLE " + tableOwner);
        JdbcConnectorUtils.executeUpdate(connection, "DROP ROLE " + schemaOwner);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testCascadeDropPreservesConcurrentViewDependency(boolean newSourceView) throws Exception {
    String schema = RandomNameUtils.genRandomName("cascade_concurrent");
    String other = RandomNameUtils.genRandomName("cascade_concurrent_other");
    CountDownLatch paused = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection connection = DATA_SOURCE.getConnection();
        BasicDataSource dropSource = newDataSource()) {
      dropSource.setMaxTotal(1);
      try (Connection pooled = dropSource.getConnection()) {
        pooled.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      }
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + schema);
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + other);
      JdbcConnectorUtils.executeUpdate(
          connection, "CREATE VIEW " + schema + ".v AS SELECT 1 AS id");
      PostgreSqlSchemaOperations operations = pausedOperations(dropSource, paused, release, false);
      Future<Boolean> drop = executor.submit(() -> operations.delete(schema, true));
      try {
        Assertions.assertTrue(paused.await(10, TimeUnit.SECONDS));
        String source = schema + ".v";
        if (newSourceView) {
          source = schema + ".new_v";
          JdbcConnectorUtils.executeUpdate(
              connection, "CREATE VIEW " + source + " AS SELECT 2 AS id");
        }
        JdbcConnectorUtils.executeUpdate(
            connection, "CREATE VIEW " + other + ".v AS SELECT * FROM " + source);
        release.countDown();
        ExecutionException failure =
            Assertions.assertThrows(ExecutionException.class, () -> drop.get(10, TimeUnit.SECONDS));
        Assertions.assertInstanceOf(NonEmptySchemaException.class, failure.getCause());
        Assertions.assertTrue(failure.getCause().getMessage().contains(other));
        try (Connection pooled = dropSource.getConnection()) {
          Assertions.assertEquals(
              Connection.TRANSACTION_REPEATABLE_READ, pooled.getTransactionIsolation());
        }
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT id FROM " + other + ".v")) {
          Assertions.assertTrue(rows.next());
          Assertions.assertEquals(newSourceView ? 2 : 1, rows.getInt(1));
        }
      } finally {
        release.countDown();
        executor.shutdownNow();
        Assertions.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        JdbcConnectorUtils.executeUpdate(connection, "DROP SCHEMA IF EXISTS " + other + " CASCADE");
        JdbcConnectorUtils.executeUpdate(
            connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  @Test
  void testNativeDropLocksBlockNewExternalReferencesUntilCommit() throws Exception {
    String schema = RandomNameUtils.genRandomName("cascade_locked");
    String other = RandomNameUtils.genRandomName("cascade_locked_other");
    CountDownLatch paused = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection connection = DATA_SOURCE.getConnection();
        BasicDataSource dropSource = newDataSource();
        Connection creator = DATA_SOURCE.getConnection()) {
      dropSource.setMaxTotal(1);
      try (Connection pooled = dropSource.getConnection()) {
        pooled.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      }
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + schema);
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + other);
      JdbcConnectorUtils.executeUpdate(
          connection, "CREATE VIEW " + schema + ".v AS SELECT 1 AS id");
      int creatorPid;
      try (Statement statement = creator.createStatement();
          ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
        Assertions.assertTrue(rows.next());
        creatorPid = rows.getInt(1);
      }
      PostgreSqlSchemaOperations operations = pausedOperations(dropSource, paused, release, true);
      Future<Boolean> drop = executor.submit(() -> operations.delete(schema, true));
      try {
        Assertions.assertTrue(paused.await(10, TimeUnit.SECONDS));
        Future<Integer> create =
            executor.submit(
                () ->
                    JdbcConnectorUtils.executeUpdate(
                        creator, "CREATE VIEW " + other + ".v AS SELECT * FROM " + schema + ".v"));
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () -> {
                  try (Statement statement = connection.createStatement();
                      ResultSet rows =
                          statement.executeQuery(
                              "SELECT wait_event_type FROM pg_stat_activity WHERE pid = "
                                  + creatorPid)) {
                    return rows.next() && "Lock".equals(rows.getString(1));
                  }
                });
        Assertions.assertFalse(create.isDone());
        release.countDown();
        Assertions.assertTrue(drop.get(10, TimeUnit.SECONDS));
        try (Connection pooled = dropSource.getConnection()) {
          Assertions.assertEquals(
              Connection.TRANSACTION_SERIALIZABLE, pooled.getTransactionIsolation());
        }
        ExecutionException failure =
            Assertions.assertThrows(
                ExecutionException.class, () -> create.get(10, TimeUnit.SECONDS));
        Assertions.assertInstanceOf(SQLException.class, failure.getCause());
        try (Statement statement = connection.createStatement();
            ResultSet rows =
                statement.executeQuery(
                    "SELECT to_regnamespace('" + schema + "'), to_regclass('" + other + ".v')")) {
          Assertions.assertTrue(rows.next());
          Assertions.assertNull(rows.getObject(1));
          Assertions.assertNull(rows.getObject(2));
        }
      } finally {
        release.countDown();
        executor.shutdownNow();
        Assertions.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        JdbcConnectorUtils.executeUpdate(connection, "DROP SCHEMA IF EXISTS " + other + " CASCADE");
        JdbcConnectorUtils.executeUpdate(
            connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  @Test
  void testCascadeDropPreservesExternalDependents() throws SQLException {
    String schema = RandomNameUtils.genRandomName("cascade_source");
    String other = RandomNameUtils.genRandomName("cascade_other");
    try (Connection connection = DATA_SOURCE.getConnection()) {
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + schema);
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + other);
      try {
        JdbcConnectorUtils.executeUpdate(
            connection, "CREATE TABLE " + schema + ".parent (id int PRIMARY KEY, payload text)");
        JdbcConnectorUtils.executeUpdate(
            connection, "CREATE VIEW " + other + ".v AS SELECT * FROM " + schema + ".parent");
        JdbcConnectorUtils.executeUpdate(
            connection,
            "CREATE TABLE " + other + ".child (id int REFERENCES " + schema + ".parent)");
        NonEmptySchemaException error =
            Assertions.assertThrows(
                NonEmptySchemaException.class, () -> DATABASE_OPERATIONS.delete(schema, true));
        Assertions.assertTrue(error.getMessage().contains(other));
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT * FROM " + other + ".v")) {
          Assertions.assertFalse(rows.next());
        }
        Assertions.assertThrows(
            SQLException.class,
            () ->
                JdbcConnectorUtils.executeUpdate(
                    connection, "INSERT INTO " + other + ".child VALUES (123)"));
        JdbcConnectorUtils.executeUpdate(connection, "DROP SCHEMA " + other + " CASCADE");
        Assertions.assertTrue(DATABASE_OPERATIONS.delete(schema, true));
      } finally {
        JdbcConnectorUtils.executeUpdate(connection, "DROP SCHEMA IF EXISTS " + other + " CASCADE");
        JdbcConnectorUtils.executeUpdate(
            connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  @Test
  void testCascadeDropIncludesOwnedToastObjects() throws SQLException {
    String schema = RandomNameUtils.genRandomName("cascade_toast");
    try (Connection connection = DATA_SOURCE.getConnection()) {
      JdbcConnectorUtils.executeUpdate(connection, "CREATE SCHEMA " + schema);
      try {
        JdbcConnectorUtils.executeUpdate(
            connection, "CREATE TABLE " + schema + ".payload (id int PRIMARY KEY, body text)");
        long toastId;
        try (Statement statement = connection.createStatement();
            ResultSet rows =
                statement.executeQuery(
                    "SELECT reltoastrelid FROM pg_catalog.pg_class WHERE oid = '"
                        + schema
                        + ".payload'::regclass")) {
          Assertions.assertTrue(rows.next());
          toastId = rows.getLong(1);
          Assertions.assertTrue(toastId > 0, "The test table must have an internal TOAST table");
        }

        Assertions.assertTrue(DATABASE_OPERATIONS.delete(schema, true));
        Assertions.assertThrows(
            NoSuchSchemaException.class, () -> DATABASE_OPERATIONS.load(schema));
        try (Statement statement = connection.createStatement();
            ResultSet rows =
                statement.executeQuery(
                    "SELECT COUNT(*) FROM pg_catalog.pg_class WHERE oid = " + toastId)) {
          Assertions.assertTrue(rows.next());
          Assertions.assertEquals(0, rows.getInt(1));
        }
      } finally {
        JdbcConnectorUtils.executeUpdate(
            connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  @Test
  public void testBaseOperationSchema() {
    String databaseName = RandomNameUtils.genRandomName("ct_db");
    Map<String, String> properties = new HashMap<>();
    // PostgreSql does not support filling in comments directly when creating a table.
    String comment = null;
    List<String> initDatabases = DATABASE_OPERATIONS.listDatabases();

    ((PostgreSqlSchemaOperations) DATABASE_OPERATIONS)
        .createSysDatabaseNameSet()
        .forEach(
            sysPgDatabaseName -> Assertions.assertFalse(initDatabases.contains(sysPgDatabaseName)));

    testBaseOperation(databaseName, properties, comment);
    // delete database.
    Assertions.assertDoesNotThrow(() -> DATABASE_OPERATIONS.delete(databaseName, true));

    Assertions.assertThrows(
        NoSuchSchemaException.class, () -> DATABASE_OPERATIONS.load(databaseName));
    List<String> databases = DATABASE_OPERATIONS.listDatabases();
    Assertions.assertFalse(databases.contains(databaseName));
  }

  @Test
  public void testCreateMultipleSchema() throws SQLException {
    String testDbName = RandomNameUtils.genRandomName("test_db_");
    try (Connection connection = DATA_SOURCE.getConnection()) {
      JdbcConnectorUtils.executeUpdate(connection, "CREATE DATABASE " + testDbName);
    }
    HashMap<String, String> properties = Maps.newHashMap();
    properties.put(JdbcConfig.JDBC_DRIVER.getKey(), CONTAINER.getDriverClassName());
    String jdbcUrl =
        StringUtils.substring(CONTAINER.getJdbcUrl(), 0, CONTAINER.getJdbcUrl().lastIndexOf("/"));
    properties.put(JdbcConfig.JDBC_URL.getKey(), jdbcUrl + "/" + testDbName);
    properties.put(JdbcConfig.USERNAME.getKey(), CONTAINER.getUsername());
    properties.put(JdbcConfig.PASSWORD.getKey(), CONTAINER.getPassword());
    DataSource dataSource = DataSourceUtils.createDataSource(properties);
    PostgreSqlSchemaOperations postgreSqlSchemaOperations = new PostgreSqlSchemaOperations();
    Map<String, String> config =
        new HashMap<String, String>() {
          {
            put(JdbcConfig.JDBC_DATABASE.getKey(), testDbName);
          }
        };
    postgreSqlSchemaOperations.initialize(dataSource, JDBC_EXCEPTION_CONVERTER, config);

    String schema_1 = "schema_multiple_1";
    DATABASE_OPERATIONS.create(schema_1, null, null);

    List<String> schemaNames = postgreSqlSchemaOperations.listDatabases();
    Assertions.assertFalse(schemaNames.contains(schema_1));

    String schema_2 = "schema_multiple_2";
    postgreSqlSchemaOperations.create(schema_2, null, null);

    schemaNames = DATABASE_OPERATIONS.listDatabases();
    Assertions.assertFalse(schemaNames.contains(schema_2));

    postgreSqlSchemaOperations.delete(schema_2, true);

    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () -> {
          postgreSqlSchemaOperations.load(schema_2);
        });

    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () -> {
          DATABASE_OPERATIONS.load(schema_2);
        });

    postgreSqlSchemaOperations.create(schema_1, null, null);
    JdbcSchema load = postgreSqlSchemaOperations.load(schema_1);
    Assertions.assertEquals(schema_1, load.name());
  }

  private PostgreSqlSchemaOperations pausedOperations(
      DataSource source, CountDownLatch paused, CountDownLatch release, boolean afterDrop)
      throws SQLException {
    PostgreSqlSchemaOperations operations =
        new PostgreSqlSchemaOperations() {
          /** {@inheritDoc} */
          @Override
          protected Connection getConnection() throws SQLException {
            Connection delegate = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      try {
                        Object result = method.invoke(delegate, args);
                        if (!method.getName().equals("createStatement")) {
                          return result;
                        }
                        Statement statement = (Statement) result;
                        return Proxy.newProxyInstance(
                            Statement.class.getClassLoader(),
                            new Class<?>[] {Statement.class},
                            (statementProxy, statementMethod, statementArgs) -> {
                              boolean drop =
                                  statementMethod.getName().equals("executeUpdate")
                                      && ((String) statementArgs[0]).startsWith("DROP SCHEMA");
                              if (drop && !afterDrop) {
                                awaitDropRelease(paused, release);
                              }
                              try {
                                Object value = statementMethod.invoke(statement, statementArgs);
                                if (drop && afterDrop) {
                                  awaitDropRelease(paused, release);
                                }
                                return value;
                              } catch (InvocationTargetException e) {
                                throw e.getCause();
                              }
                            });
                      } catch (InvocationTargetException e) {
                        throw e.getCause();
                      }
                    });
          }
        };
    try (Connection connection = source.getConnection()) {
      operations.initialize(
          source,
          JDBC_EXCEPTION_CONVERTER,
          Map.of(JdbcConfig.JDBC_DATABASE.getKey(), connection.getCatalog()));
    }
    return operations;
  }

  private static void awaitDropRelease(CountDownLatch paused, CountDownLatch release)
      throws SQLException {
    paused.countDown();
    try {
      if (!release.await(10, TimeUnit.SECONDS)) {
        throw new SQLException("Timed out waiting to release the DROP statement");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SQLException("Interrupted while waiting to release the DROP statement", e);
    }
  }

  private static BasicDataSource newDataSource() {
    Map<String, String> properties = new HashMap<>();
    properties.put(JdbcConfig.JDBC_DRIVER.getKey(), CONTAINER.getDriverClassName());
    properties.put(JdbcConfig.JDBC_URL.getKey(), CONTAINER.getJdbcUrl());
    properties.put(JdbcConfig.USERNAME.getKey(), CONTAINER.getUsername());
    properties.put(JdbcConfig.PASSWORD.getKey(), CONTAINER.getPassword());
    return (BasicDataSource) DataSourceUtils.createDataSource(properties);
  }

  private static PostgreSqlSchemaOperations newOperations(DataSource source, String database) {
    PostgreSqlSchemaOperations operations = new PostgreSqlSchemaOperations();
    operations.initialize(
        source, JDBC_EXCEPTION_CONVERTER, Map.of(JdbcConfig.JDBC_DATABASE.getKey(), database));
    return operations;
  }
}
