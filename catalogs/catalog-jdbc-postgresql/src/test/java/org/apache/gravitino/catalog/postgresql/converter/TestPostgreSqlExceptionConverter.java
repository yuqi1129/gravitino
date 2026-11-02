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
package org.apache.gravitino.catalog.postgresql.converter;

import java.sql.SQLException;
import java.util.stream.Stream;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tests PostgreSQL error conversion preserves the original SQL exception and message. */
public class TestPostgreSqlExceptionConverter {

  /**
   * SQL errors retain their cause, suppressed rollback errors and literal percent characters.
   *
   * @param state the PostgreSQL SQL state, or null for a driver without a state
   * @param expectedType the expected connector exception type
   * @param message the original driver message
   */
  @ParameterizedTest
  @MethodSource("errorCases")
  public void testPreservesSqlException(
      String state, Class<? extends GravitinoRuntimeException> expectedType, String message) {
    SQLException sql = new SQLException(message, state);
    SQLException rollback = new SQLException("Rollback failed");
    sql.addSuppressed(rollback);
    GravitinoRuntimeException result = new PostgreSqlExceptionConverter().toGravitinoException(sql);
    Assertions.assertEquals(expectedType, result.getClass());
    Assertions.assertEquals(message, result.getMessage());
    Assertions.assertSame(sql, result.getCause());
    Assertions.assertArrayEquals(new Throwable[] {rollback}, result.getCause().getSuppressed());
  }

  private static Stream<Arguments> errorCases() {
    String message = "SQL failure at 100%: missing %s marker";
    return Stream.of(
        Arguments.of("42P04", SchemaAlreadyExistsException.class, message),
        Arguments.of("42P06", SchemaAlreadyExistsException.class, message),
        Arguments.of("42P07", TableAlreadyExistsException.class, message),
        Arguments.of("3D000", NoSuchSchemaException.class, message),
        Arguments.of("3F000", NoSuchSchemaException.class, message),
        Arguments.of("42P01", NoSuchTableException.class, message),
        Arguments.of("08006", ConnectionFailedException.class, message),
        Arguments.of("22023", GravitinoRuntimeException.class, message),
        Arguments.of(null, GravitinoRuntimeException.class, message),
        Arguments.of(
            null, ConnectionFailedException.class, "password authentication failed: 100%"));
  }
}
