/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.performance;

import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

public class TestPerformance {
  public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private final OkHttpClient client = new OkHttpClient();

  private static final String CREATE_TABLE_URL =
      "http://localhost:8090/api/metalakes/test/catalogs/hive_catalog/schemas/db1/tables";
  private static final String LIST_METALAKE_URL = "http://localhost:8090/api/metalakes";
  private static final String LOAD_CATALOG_URL =
      "http://localhost:8090/api/metalakes/test/catalogs/hive_catalog";

  private static final String LOAD_SCHEMA_URL =
      "http://localhost:8090/api/metalakes/test/catalogs/hive_catalog/schemas/db1";

  private static final String ALTER_CATALOG_URL =
      "http://localhost:8090/api/metalakes/test/catalogs/hive_catalog";

  private static final String LIST_TABLE_URL =
      "http://localhost:8090/api/metalakes/test/catalogs/hive_catalog/schemas/db1/tables";

  private static final String LOAD_TABLE_URL =
      "http://localhost:8090/api/metalakes/test/catalogs/hive_catalog/schemas/db1/tables/";

  private static final Multimap<String, Long> timeMap =
      MultimapBuilder.linkedHashKeys().arrayListValues().build();
  private static final Map<String, Long> failedMap = Maps.newConcurrentMap();
  private static final int repeatTimes = 50;

  static {
    failedMap.put("listMetalakes", 0L);
    failedMap.put("loadCatalog", 0L);
    failedMap.put("loadSchema", 0L);
    failedMap.put("alterCatalog", 0L);
    failedMap.put("listTable", 0L);
    failedMap.put("createTable", 0L);
    failedMap.put("loadTable", 0L);
  }

  private void listMetalakes() {
    Request request = new Request.Builder().url(LIST_METALAKE_URL).build();

    Call call = client.newCall(request);

    try (Response response = call.execute()) {
      if (200 != response.code()) {
        increment("listMetalakes");
      }
    } catch (Exception e) {
      increment("listMetalakes");
    }
  }

  private void loadCatalog() {
    Request request = new Request.Builder().url(LOAD_CATALOG_URL).build();

    Call call = client.newCall(request);

    try (Response response = call.execute()) {
      if (response.code() != 200) {
        increment("loadCatalog");
      }
    } catch (Exception e) {
      increment("loadCatalog");
    }
  }

  private void loadSchema() {
    Request request = new Request.Builder().url(LOAD_SCHEMA_URL).build();

    Call call = client.newCall(request);

    try (Response response = call.execute()) {
      if (response.code() != 200) {
        increment("loadSchema");
      }
    } catch (Exception e) {
      increment("loadSchema");
    }
  }

  public void alterCatalog() {
    String data =
        "{\n"
            + "  \"updates\": [\n"
            + "    {\n"
            + "      \"@type\": \"setProperty\",\n"
            + "      \"property\": \"key3\",\n"
            + "      \"value\": \"value3\"\n"
            + "    }\n"
            + "  ]\n"
            + "}";
    RequestBody body = RequestBody.create(data, JSON);
    Request request = new Request.Builder().url(ALTER_CATALOG_URL).put(body).build();

    try (Response response = client.newCall(request).execute()) {
      if (200 != response.code()) {
        increment("alterCatalog");
      }
    } catch (Exception e) {
      increment("alterCatalog");
    }
  }

  @Test
  void testListTable() {
    long start = System.currentTimeMillis();
    for (int i = 0; i < 500; i++) {
      long listTableStart = System.currentTimeMillis();
      listTable();
      timeMap.put("listTable", System.currentTimeMillis() - listTableStart);
    }
    System.out.println(
        "end to list table 500 times, take: " + (System.currentTimeMillis() - start));
  }

  public void listTable() {
    Request request = new Request.Builder().url(LIST_TABLE_URL).build();

    Call call = client.newCall(request);

    try (Response response = call.execute()) {
      if (200 != response.code()) {
        increment("listTable");
      }
    } catch (Exception e) {
      increment("listTable");
    }
  }

  private void createTable(String tableName) {
    String data =
        String.format(
            "{\"name\":\"%s\",\"comment\":\"my test table\",\"columns\":[{\"name\":\"integer1\",\"type\":\"integer\",\"comment\":\"id column comment\",\"nullable\":true},{\"name\":\"long1\",\"type\":\"long\",\"comment\":\"name column comment\",\"nullable\":true},{\"name\":\"float1\",\"type\":\"float\",\"comment\":\"name column comment\",\"nullable\":true},{\"name\":\"double1\",\"type\":\"double\",\"comment\":\"name column comment\",\"nullable\":true},{\"name\":\"decimal1\",\"type\":\"decimal(20, 4)\",\"comment\":\"name column comment\",\"nullable\":true},{\"name\":\"date1\",\"type\":\"date\",\"comment\":\"name column comment\",\"nullable\":true},{\"name\":\"string1\",\"type\":\"string\",\"comment\":\"name column comment\",\"nullable\":true},{\"name\":\"binary1\",\"type\":\"binary\",\"comment\":\"name column comment\",\"nullable\":true}],\"properties\":{}}",
            tableName);
    RequestBody body = RequestBody.create(data, JSON);
    Request request = new Request.Builder().url(CREATE_TABLE_URL).post(body).build();

    try (Response response = client.newCall(request).execute()) {
      if (200 != response.code()) {
        increment("createTable");
      }
    } catch (Exception e) {
      increment("createTable");
    }
  }

  private void loadTable(String tableName) {
    Request request = new Request.Builder().url(LOAD_TABLE_URL + tableName).build();

    Call call = client.newCall(request);

    try (Response response = call.execute()) {
      if (200 != response.code()) {
        increment("loadTable");
      }
    } catch (Exception e) {
      increment("loadTable");
    }
  }

  @Test
  void testLoadTable() {
    loadTable("test_table_1_1");
  }

  private void increment(String type) {
    failedMap.compute(
        type,
        (k, v) -> {
          if (v == null) {
            return 1L;
          } else {
            return v + 1;
          }
        });
  }

  @Test
  void testCreateTable() {
    createTable("test_table_1");
  }

  private CompletionService<Integer> createCompletionService() {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            20,
            20,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("performance-test-%d")
                .build());

    CompletionService<Integer> completionService = new ExecutorCompletionService(executor);
    return completionService;
  }

  @Test
  public void testPerformance() throws IOException, InterruptedException, ExecutionException {
    CompletionService<Integer> completionService = createCompletionService();
    for (int i = 0; i < 1000; i++) {
      // pre-load catalog
      loadCatalog();
    }

    // One thread to list metalakes for 100 times;
    Future<Integer> listMetalakeFuture =
        completionService.submit(
            () -> {
              try {
                Thread.sleep(20000);
              } catch (Exception e) {
                e.printStackTrace();
              }

              long start = System.currentTimeMillis();
              for (int i = 0; i < repeatTimes; i++) {
                long listStart = System.currentTimeMillis();
                listMetalakes();
                synchronized (this) {
                  timeMap.put("listMetalakes", System.currentTimeMillis() - listStart);
                }
                try {
                  Thread.sleep(5000);
                } catch (InterruptedException e) {
                  e.printStackTrace();
                }
              }
              System.out.println(
                  "end to list metalakes, take: " + (System.currentTimeMillis() - start));
              return 0;
            });

    // One thread to load catalog for 500 times;
    Future<Integer> loadCatalogFuture =
        completionService.submit(
            () -> {
              long start = System.currentTimeMillis();
              for (int i = 0; i < repeatTimes * 10; i++) {
                long loadCatalogStart = System.currentTimeMillis();
                loadCatalog();
                synchronized (this) {
                  timeMap.put("loadCatalog", System.currentTimeMillis() - loadCatalogStart);
                }

                try {
                  Thread.sleep(500);
                } catch (InterruptedException e) {
                  e.printStackTrace();
                }
              }
              System.out.println(
                  "end to load catalog, take: " + (System.currentTimeMillis() - start));
              return 0;
            });

    // One thread to alter catalog for 500 times;
    Future<Integer> alterCatalogFuture =
        completionService.submit(
            () -> {
              try {
                Thread.sleep(20000);
              } catch (Exception e) {
                e.printStackTrace();
              }

              long start = System.currentTimeMillis();
              for (int i = 0; i < repeatTimes; i++) {
                long alterCatalogStart = System.currentTimeMillis();
                alterCatalog();

                synchronized (this) {
                  timeMap.put("alterCatalog", System.currentTimeMillis() - alterCatalogStart);
                }

                try {
                  Thread.sleep(1000);
                } catch (InterruptedException e) {
                  e.printStackTrace();
                }
              }
              System.out.println(
                  "end to alter catalog, take: " + (System.currentTimeMillis() - start));
              return 0;
            });

    // Load schema
    Future<Integer> loadSchemaFuture =
        completionService.submit(
            () -> {
              long start = System.currentTimeMillis();
              for (int i = 0; i < repeatTimes * 50; i++) {
                long loadSchemaStart = System.currentTimeMillis();
                loadSchema();

                synchronized (this) {
                  timeMap.put("loadSchema", System.currentTimeMillis() - loadSchemaStart);
                }
              }
              System.out.println(
                  "end to load schema, take: " + (System.currentTimeMillis() - start));
              return 0;
            });

    // List table
    Future<Integer> listTableFuture =
        completionService.submit(
            () -> {
              try {
                Thread.sleep(3000);
              } catch (InterruptedException e) {
                e.printStackTrace();
              }

              long start = System.currentTimeMillis();
              for (int i = 0; i < repeatTimes * 5; i++) {
                long listTableStart = System.currentTimeMillis();
                listTable();
                synchronized (this) {
                  timeMap.put("listTable", System.currentTimeMillis() - listTableStart);
                }

                try {
                  Thread.sleep(500);
                } catch (InterruptedException e) {
                  e.printStackTrace();
                }
              }
              System.out.println(
                  "end to list table, take: " + (System.currentTimeMillis() - start));
              return 0;
            });

    long start = System.currentTimeMillis();
    CountDownLatch countDownLatch = new CountDownLatch(2);
    for (int i = 0; i < 2; i++) {
      int finalI = i;
      completionService.submit(
          () -> {
            for (int j = 0; j < repeatTimes * 50; j++) {
              long createTableStart = System.currentTimeMillis();
              try {
                createTable(String.format("test_table_%s_%s", finalI, j));
              } catch (Exception e) {
                increment("createTable");
              }

              synchronized (this) {
                timeMap.put("createTable", System.currentTimeMillis() - createTableStart);
              }
            }
            countDownLatch.countDown();
            return 0;
          });
    }

    ThreadLocalRandom random = ThreadLocalRandom.current();
    CountDownLatch loadTableCountDownLatch = new CountDownLatch(6);
    for (int i = 0; i < 6; i++) {
      int finalI = random.nextInt(0, 2);
      completionService.submit(
          () -> {
            try {
              Thread.sleep(15000);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }

            for (int j = 0; j < repeatTimes * 50; j++) {
              long loadTableStart = System.currentTimeMillis();
              try {
                loadTable(String.format("test_table_%s_%s", finalI, j));
              } catch (Exception e) {
                increment("loadTable");
              }

              synchronized (this) {
                timeMap.put("loadTable", System.currentTimeMillis() - loadTableStart);
              }

              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                e.printStackTrace();
              }
            }
            loadTableCountDownLatch.countDown();
            return 0;
          });
    }

    loadTableCountDownLatch.await();
    countDownLatch.await();
    System.out.println("end to load tables, take: " + (System.currentTimeMillis() - start - 10000));

    listMetalakeFuture.get();
    listTableFuture.get();
    loadCatalogFuture.get();
    alterCatalogFuture.get();
    loadSchemaFuture.get();

    Collection<Long> listMetalake = timeMap.get("listMetalakes");
    Collection<Long> loadCatalog = timeMap.get("loadCatalog");
    Collection<Long> alterCatalog = timeMap.get("alterCatalog");
    Collection<Long> loadSchema = timeMap.get("loadSchema");
    Collection<Long> listTable = timeMap.get("listTable");
    Collection<Long> createTable = timeMap.get("createTable");
    Collection<Long> loadTable = timeMap.get("loadTable");

    printTime(listMetalake, "listMetalakes");
    printTime(loadCatalog, "loadCatalog");
    printTime(alterCatalog, "alterCatalog");
    printTime(loadSchema, "loadSchema");
    printTime(listTable, "listTable");
    printTime(createTable, "createTable");
    printTime(loadTable, "loadTable");
  }

  private void printTime(Collection<Long> times, String type) {
    if (Objects.isNull(times) || times.isEmpty()) {
      System.out.println(type + " no time");
      return;
    }

    long sum = 0;
    for (Long time : times) {
      sum += time == null ? 0 : (long) time;
    }

    long max = times.stream().mapToLong(Long::longValue).max().getAsLong();
    long min = times.stream().mapToLong(Long::longValue).min().getAsLong();
    long avg = sum / times.size();
    System.out.println(
        type
            + " sum: "
            + sum
            + ", max: "
            + max
            + ", min: "
            + min
            + ", avg: "
            + avg
            + ", failed num: "
            + failedMap.get(type)
            + ", total: "
            + times.size());
  }
}
