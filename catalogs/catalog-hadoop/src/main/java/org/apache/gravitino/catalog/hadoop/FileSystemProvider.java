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

package org.apache.gravitino.catalog.hadoop;

import java.io.IOException;
import java.util.Map;
import org.apache.hadoop.fs.FileSystem;

public interface FileSystemProvider {

  /**
   * Get the FileSystem instance according to the configuration and the path.
   *
   * <p>Compared to the FileSystem.get method, this method allows the provider to create a
   * FileSystem instance with a specific configuration and path and do further initialization if
   * needed.
   *
   * <p>For example, we can check endpoint configurations for S3AFileSystem, or set the default one.
   *
   * @param config The configuration for the FileSystem instance.
   * @return The FileSystem instance.
   * @throws IOException If the FileSystem instance cannot be created.
   */
  FileSystem getFileSystem(Map<String, String> config) throws IOException;

  /**
   * Get the scheme of this FileSystem provider. file for LocalFileSystem, hdfs for HDFS, s3a for
   * S3AFileSystem, etc.
   *
   * @return The scheme of this FileSystem provider.
   */
  String getScheme();
}
