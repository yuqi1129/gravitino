/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.lock;

import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.Namespace;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.Random;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

public class TestLockManager {
  private static final String[] ENTITY_NAMES = {
    "entity1",
    "entity2",
    "entity3",
    "entity4",
    "entity5",
    "entity6",
    "entity7",
    "entity8",
    "entity9",
    "entity10"
  };

  private static final LockManager lockManager = new LockManager();

  private CompletionService<Integer> createCompletionService() {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            10,
            10,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadFactoryBuilder().setDaemon(true).build());

    CompletionService<Integer> completionService = new ExecutorCompletionService(executor);
    return completionService;
  }

  private NameIdentifier randomNameIdentifier() {
    Random random = new Random();
    int level = random.nextInt(10);
    NameIdentifier nameIdentifier = null;
    switch (level) {
      case 0:
        nameIdentifier = NameIdentifier.ROOT;
        break;
      case 1:
        nameIdentifier = NameIdentifier.of(Namespace.of(), ENTITY_NAMES[random.nextInt(1)]);
        break;
      case 2:
        nameIdentifier =
            NameIdentifier.of(
                Namespace.of(ENTITY_NAMES[random.nextInt(1)]), ENTITY_NAMES[random.nextInt(3)]);
        break;
      case 3:
        nameIdentifier =
            NameIdentifier.of(
                Namespace.of(ENTITY_NAMES[random.nextInt(1)], ENTITY_NAMES[random.nextInt(3)]),
                ENTITY_NAMES[random.nextInt(5)]);
        break;
      default:
        nameIdentifier =
            NameIdentifier.of(
                Namespace.of(
                    ENTITY_NAMES[random.nextInt(1)],
                    ENTITY_NAMES[random.nextInt(3)],
                    ENTITY_NAMES[random.nextInt(5)]),
                ENTITY_NAMES[random.nextInt(10)]);
    }

    return nameIdentifier;
  }

  @Test
  void multipleThreadTestLockManager() throws InterruptedException, ExecutionException {
    CompletionService<Integer> completionService = createCompletionService();
    for (int i = 0; i < 10; i++) {
      completionService.submit(() -> this.testLockManager(lockManager));
    }

    for (int i = 0; i < 10; i++) {
      completionService.take().get();
    }
  }

  int testNormalLock() throws InterruptedException {
    ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();
    ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock();
    for (int i = 0; i < 10000; i++) {
      NameIdentifier identifier = randomNameIdentifier();
      int num = threadLocalRandom.nextInt(5);
      LockType lockType = num >= 4 ? LockType.WRITE : LockType.READ;
      //      LockType lockType = LockType.values()[threadLocalRandom.nextInt(2)];
      if (lockType == LockType.WRITE) {
        reentrantReadWriteLock.writeLock().lock();
        // App logic here...
        Thread.sleep(5);
        reentrantReadWriteLock.writeLock().unlock();
      } else {
        reentrantReadWriteLock.readLock().lock();
        // App logic here...
        Thread.sleep(5);
        reentrantReadWriteLock.readLock().unlock();
      }
    }

    return 0;
  }

  int testLockManager(LockManager lockManager) throws InterruptedException {
    ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();
    for (int i = 0; i < 10000; i++) {
      NameIdentifier identifier = randomNameIdentifier();
      int num = threadLocalRandom.nextInt(5);
      LockType lockType = num >= 4 ? LockType.WRITE : LockType.READ;
      try {
        lockManager.lockResourcePath(identifier, lockType);
        // App logic here...
        Thread.sleep(5);
      } catch (Exception e) {
        if (e.getMessage().contains("mock")) {
          return 0;
        }
        throw e;
      } finally {
        lockManager.unlockResourcePath();
      }
    }

    return 0;
  }

  @Test
  void testLockWithError() {
    LockManager lockManager = new LockManager();
    LockManager spy = Mockito.spy(lockManager);

    // one fifth (2 /10 = 0.2) of tests will fail
    Mockito.doThrow(new RuntimeException("mock"))
        .when(spy)
        .lockResourcePath(
            Mockito.eq(NameIdentifier.of(Namespace.of(), ENTITY_NAMES[0])), Mockito.any());
    Mockito.doThrow(new RuntimeException("mock"))
        .when(spy)
        .lockResourcePath(
            Mockito.eq(NameIdentifier.of(Namespace.of(), ENTITY_NAMES[1])), Mockito.any());

    CompletionService<Integer> completionService = createCompletionService();
    for (int i = 0; i < 10; i++) {
      completionService.submit(() -> this.testLockManager(spy));
    }

    for (int i = 0; i < 10; i++) {
      Assertions.assertDoesNotThrow(() -> completionService.take().get());
    }
  }

  @Disabled
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4, 8, 10})
  void compare(int threadCount) throws InterruptedException, ExecutionException {
    LockManager lockManager = new LockManager();
    CompletionService<Integer> completionService = createCompletionService();
    for (int i = 0; i < 2; i++) {
      completionService.submit(() -> this.testLockManager(lockManager));
    }
    for (int i = 0; i < 2; i++) {
      completionService.take().get();
    }

    long start = System.currentTimeMillis();
    for (int i = 0; i < 10; i++) {
      completionService.submit(() -> this.testLockManager(lockManager));
    }
    for (int i = 0; i < 10; i++) {
      completionService.take().get();
    }
    System.out.println("LockManager use tree lock: " + (System.currentTimeMillis() - start) + "ms");

    start = System.currentTimeMillis();
    for (int i = 0; i < 10; i++) {
      completionService.submit(() -> this.testNormalLock());
    }

    for (int i = 0; i < 10; i++) {
      completionService.take().get();
    }
    System.out.println(
        "LockManager use normal lock: " + (System.currentTimeMillis() - start) + "ms");
  }

  @Test
  void testLockCleaner() throws InterruptedException, ExecutionException {
    LockManager lockManager = new LockManager();
    Random random = new Random();

    for (int i = 0; i < 10000; i++) {
      NameIdentifier nameIdentifier = randomNameIdentifier();
      lockManager.lockResourcePath(nameIdentifier, LockType.READ);
      lockManager.unlockResourcePath();
    }

    Thread.sleep(1000);
    lockManager
        .treeLockRootNode
        .getAllChildren()
        .forEach(
            child -> {
              lockManager.evictStaleNodes(500, child, lockManager.treeLockRootNode);
            });

    Assertions.assertTrue(lockManager.treeLockRootNode.getAllChildren().isEmpty());

    CompletionService<Integer> service = createCompletionService();
    for (int i = 0; i < 10; i++) {
      service.submit(
          () -> {
            for (int j = 0; j < 10000; j++) {
              NameIdentifier nameIdentifier = randomNameIdentifier();
              lockManager.lockResourcePath(
                  nameIdentifier, random.nextInt(2) == 0 ? LockType.READ : LockType.WRITE);
              lockManager.unlockResourcePath();
            }
            return 0;
          });
    }

    Thread.sleep(1000);

    for (int i = 0; i < 5; i++) {
      service.submit(
          () -> {
            for (int j = 0; j < 10000; j++) {
              NameIdentifier nameIdentifier = randomNameIdentifier();
              lockManager.lockResourcePath(nameIdentifier, LockType.READ);
              lockManager.unlockResourcePath();
            }
            return 0;
          });
    }

    for (int i = 0; i < 5; i++) {
      service.submit(
          () -> {
            lockManager
                .treeLockRootNode
                .getAllChildren()
                .forEach(
                    child -> {
                      lockManager.evictStaleNodes(500, child, lockManager.treeLockRootNode);
                    });
            return 0;
          });
    }

    for (int i = 0; i < 10; i++) {
      service.take().get();
    }
  }
}
