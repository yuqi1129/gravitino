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

import static org.apache.gravitino.catalog.CapabilityHelpers.applyCaseSensitive;
import static org.apache.gravitino.catalog.CapabilityHelpers.applyCaseSensitiveOnName;
import static org.apache.gravitino.catalog.CapabilityHelpers.withCapability;

import java.util.Arrays;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.exceptions.NoSuchPartitionException;
import org.apache.gravitino.exceptions.PartitionAlreadyExistsException;
import org.apache.gravitino.rel.partitions.Partition;

public class PartitionNormalizeDispatcher implements PartitionDispatcher {
  private final CatalogManager catalogManager;
  private final PartitionDispatcher dispatcher;

  public PartitionNormalizeDispatcher(
      PartitionDispatcher dispatcher, CatalogManager catalogManager) {
    this.dispatcher = dispatcher;
    this.catalogManager = catalogManager;
  }

  @Override
  public String[] listPartitionNames(NameIdentifier tableIdent) {
    String[] partitionNames = dispatcher.listPartitionNames(normalizeTableIdent(tableIdent));
    return withCapability(
        tableIdent,
        catalogManager,
        cap ->
            Arrays.stream(partitionNames)
                .map(
                    partitionName ->
                        applyCaseSensitiveOnName(Capability.Scope.PARTITION, partitionName, cap))
                .toArray(String[]::new));
  }

  @Override
  public Partition[] listPartitions(NameIdentifier tableIdent) {
    Partition[] partitions = dispatcher.listPartitions(normalizeTableIdent(tableIdent));
    return withCapability(tableIdent, catalogManager, cap -> applyCaseSensitive(partitions, cap));
  }

  @Override
  public Partition getPartition(NameIdentifier tableIdent, String partitionName)
      throws NoSuchPartitionException {
    return dispatcher.getPartition(
        normalizeTableIdent(tableIdent), normalizePartitionName(tableIdent, partitionName));
  }

  @Override
  public Partition addPartition(NameIdentifier tableIdent, Partition partition)
      throws PartitionAlreadyExistsException {
    return dispatcher.addPartition(
        normalizeTableIdent(tableIdent), normalizePartition(tableIdent, partition));
  }

  @Override
  public boolean dropPartition(NameIdentifier tableIdent, String partitionName) {
    return dispatcher.dropPartition(
        normalizeTableIdent(tableIdent), normalizePartitionName(tableIdent, partitionName));
  }

  @Override
  public boolean purgePartition(NameIdentifier tableIdent, String partitionName)
      throws UnsupportedOperationException {
    return dispatcher.purgePartition(
        normalizeTableIdent(tableIdent), normalizePartitionName(tableIdent, partitionName));
  }

  private NameIdentifier normalizeTableIdent(NameIdentifier tableIdent) {
    return withCapability(
        tableIdent,
        catalogManager,
        cap -> applyCaseSensitive(tableIdent, Capability.Scope.TABLE, cap));
  }

  private String normalizePartitionName(NameIdentifier tableIdent, String partitionName) {
    return withCapability(
        tableIdent,
        catalogManager,
        cap -> applyCaseSensitiveOnName(Capability.Scope.PARTITION, partitionName, cap));
  }

  private Partition normalizePartition(NameIdentifier tableIdent, Partition partition) {
    return withCapability(tableIdent, catalogManager, cap -> applyCaseSensitive(partition, cap));
  }
}
