/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.lock;

import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.utils.Executable;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Utility class for tree locks. */
public class TreeLockUtils {
  private static final ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock();

  private TreeLockUtils() {
    // Prevent instantiation.
  }

  /**
   * Execute the given executable with the given tree lock.
   *
   * @param identifier The identifier of resource path that the lock attempts to lock.
   * @param lockType The type of lock to use.
   * @param executable The executable to execute.
   * @return The result of the executable.
   * @param <R> The type of the result.
   * @param <E> The type of the exception.
   * @throws E If the executable throws an exception.
   */
  public static <R, E extends Exception> R doWithTreeLock(
      NameIdentifier identifier, LockType lockType, Executable<R, E> executable) throws E {
    //    TreeLock lock = GravitinoEnv.getInstance().getLockManager().createTreeLock(identifier);
    //    try {
    //      lock.lock(lockType);
    //      return executable.execute();
    //    } finally {
    //      lock.unlock();
    //    }
    if (lockType == LockType.READ) {
      reentrantReadWriteLock.readLock().lock();
    } else {
      reentrantReadWriteLock.writeLock().lock();
    }

    try {
      return executable.execute();
    } finally {
      // Do nothing
      if (lockType == LockType.READ) {
        reentrantReadWriteLock.readLock().unlock();
      } else {
        reentrantReadWriteLock.writeLock().unlock();
      }
    }
  }

  /**
   * Execute the given executable with the root tree lock.
   *
   * @param lockType The type of lock to use.
   * @param executable The executable to execute.
   * @return The result of the executable.
   * @param <R> The type of the result.
   * @param <E> The type of the exception.
   * @throws E If the executable throws an exception.
   */
  public static <R, E extends Exception> R doWithRootTreeLock(
      LockType lockType, Executable<R, E> executable) throws E {
    return doWithTreeLock(LockManager.ROOT, lockType, executable);
  }
}
