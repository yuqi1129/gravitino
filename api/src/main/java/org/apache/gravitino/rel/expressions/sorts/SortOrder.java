/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Referred from Apache Spark's connector/catalog implementation
// org/apache/spark/sql/connector/expressions/SortOrder.java

package org.apache.gravitino.rel.expressions.sorts;

import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.rel.expressions.Expression;

/** Represents a sort order in the public expression API. */
@Evolving
public interface SortOrder extends Expression {

  /** @return The sort expression. */
  Expression expression();

  /** @return The sort direction. */
  SortDirection direction();

  /** @return The null ordering. */
  NullOrdering nullOrdering();

  /** @return A {@link SortOrder} with the given sort direction. */
  @Override
  default Expression[] children() {
    return new Expression[] {expression()};
  }
}
