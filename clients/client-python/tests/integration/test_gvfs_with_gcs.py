# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import os
from random import randint

from fsspec.implementations.arrow import ArrowFSWrapper
from pyarrow.fs import GcsFileSystem

from tests.integration.test_gvfs_with_hdfs import TestGvfsWithHDFS
from gravitino import (
    gvfs,
    GravitinoClient,
    Catalog,
    Fileset,
)


class TestGvfsWithGCS(TestGvfsWithHDFS):
    key_file = "/home/ec2-user/silken-physics-431108-g3-30ab3d97bb60.json"
    bucket_name = "example_qazwsx"
    metalake_name: str = "TestGvfsWithGCS_metalake" + str(randint(1, 10000))

    def setUp(self):
        self.options = {"gravitino.bypass.gcs.service-account-key-path": self.key_file}

    def tearDown(self):
        self.options = {}

    @classmethod
    def setUpClass(cls):
        cls._get_gravitino_home()

        cls.hadoop_conf_path = f"{cls.gravitino_home}/catalogs/hadoop/conf/hadoop.conf"

        # append the hadoop conf to server
        # restart the server
        cls.restart_server()
        # create entity
        cls._init_test_entities()

    @classmethod
    def tearDownClass(cls):
        cls._clean_test_data()
        # reset server conf
        cls._reset_conf(cls.config, cls.hadoop_conf_path)
        # restart server
        cls.restart_server()

    @classmethod
    def _init_test_entities(cls):
        cls.gravitino_admin_client.create_metalake(
            name=cls.metalake_name, comment="", properties={}
        )
        cls.gravitino_client = GravitinoClient(
            uri="http://localhost:8090", metalake_name=cls.metalake_name
        )

        cls.config = {}
        cls.conf = {}
        catalog = cls.gravitino_client.create_catalog(
            name=cls.catalog_name,
            catalog_type=Catalog.Type.FILESET,
            provider=cls.catalog_provider,
            comment="",
            properties={
                "filesystem-providers-classnames": "org.apache.gravitino.fileset.gcs.GCSFileSystemProvider",
                "gravitino.bypass.fs.gs.auth.service.account.enable": "true",
                "gravitino.bypass.fs.gs.auth.service.account.json.keyfile": cls.key_file,
            },
        )
        catalog.as_schemas().create_schema(
            schema_name=cls.schema_name, comment="", properties={}
        )

        cls.fileset_storage_location: str = (
            f"gs://{cls.bucket_name}/{cls.catalog_name}/{cls.schema_name}/{cls.fileset_name}"
        )
        cls.fileset_gvfs_location = (
            f"gvfs://fileset/{cls.catalog_name}/{cls.schema_name}/{cls.fileset_name}"
        )
        catalog.as_fileset_catalog().create_fileset(
            ident=cls.fileset_ident,
            fileset_type=Fileset.Type.MANAGED,
            comment=cls.fileset_comment,
            storage_location=cls.fileset_storage_location,
            properties=cls.fileset_properties,
        )

        os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = cls.key_file
        arrow_gcs_fs = GcsFileSystem()
        cls.fs = ArrowFSWrapper(arrow_gcs_fs)

    def test_modified(self):
        modified_dir = self.fileset_gvfs_location + "/test_modified"
        modified_actual_dir = self.fileset_storage_location + "/test_modified"
        fs = gvfs.GravitinoVirtualFileSystem(
            server_uri="http://localhost:8090",
            metalake_name=self.metalake_name,
            options=self.options,
            **self.conf,
        )
        self.fs.mkdir(modified_actual_dir)
        self.assertTrue(self.fs.exists(modified_actual_dir))
        self.assertTrue(fs.exists(modified_dir))

        # Disable the following test case as it is not working for GCS
        # >>> gcs.mkdir('example_qazwsx/catalog/schema/fileset3')
        # >>> r = gcs.modified('example_qazwsx/catalog/schema/fileset3')
        # >>> print(r)
        # None
        # self.assertIsNotNone(fs.modified(modified_dir))
