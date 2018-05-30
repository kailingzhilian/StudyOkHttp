/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3;

import java.lang.ref.Reference;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.RouteDatabase;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.Util.closeQuietly;

/**
 * Manages reuse of HTTP and HTTP/2 connections for reduced network latency. HTTP requests that
 * share the same {@link Address} may share a {@link Connection}. This class implements the policy
 * of which connections to keep open for future use.
 * <p>
 * 管理HTTP和HTTP / 2连接的重用以减少网络延迟。
 * 共享相同{@link地址}的HTTP请求可能共享{@link Connection}。
 * 这个类实现了哪些连接保持打开以供将来使用的策略。
 */
public final class ConnectionPool {
    /**
     * Background threads are used to cleanup expired connections. There will be at most a single
     * thread running per connection pool. The thread pool executor permits the pool itself to be
     * garbage collected.
     * 后台线程用于清理过期的连接。 每个连接池最多只能运行一个线程。 线程池执行器允许池本身被垃圾收集。
     */
    private static final Executor executor = new ThreadPoolExecutor(0 /* corePoolSize */,
            Integer.MAX_VALUE /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp ConnectionPool", true));
    //corePoolSize=0 maximumPoolSize=Integer.MAX_VALUE 使用SynchronousQueue直接提交队列,
    // 在执行execute会立马交由复用的线程或新创建线程执行任务

    /**
     * The maximum number of idle connections for each address.
     */
    //最大的空闲连接数--每个地址的最大空闲连接数
    private final int maxIdleConnections;
    //连接持续时间
    private final long keepAliveDurationNs;

    //清理连接，在线程池executor里调用。
    private final Runnable cleanupRunnable = new Runnable() {
        @Override
        public void run() {
            while (true) {
                //执行清理，并返回下次需要清理的时间。
                // 没有任何连接时,cleanupRunning = false;

                long waitNanos = cleanup(System.nanoTime());
                if (waitNanos == -1) return;
                if (waitNanos > 0) {
                    long waitMillis = waitNanos / 1000000L;
                    waitNanos -= (waitMillis * 1000000L);
                    synchronized (ConnectionPool.this) {
                        //在timeout时间内释放锁
                        try {
                            ConnectionPool.this.wait(waitMillis, (int) waitNanos);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }
    };

    private final Deque<RealConnection> connections = new ArrayDeque<>();
    final RouteDatabase routeDatabase = new RouteDatabase();
    boolean cleanupRunning;

    /**
     * Create a new connection pool with tuning parameters appropriate for a single-user application.
     * The tuning parameters in this pool are subject to change in future OkHttp releases. Currently
     * this pool holds up to 5 idle connections which will be evicted after 5 minutes of inactivity.
     */
    //默认每个地址的最大连接数是5个
    //默认每个连接的存活时间为5分钟
    public ConnectionPool() {
        this(5, 5, TimeUnit.MINUTES);
    }

    public ConnectionPool(int maxIdleConnections, long keepAliveDuration, TimeUnit timeUnit) {
        this.maxIdleConnections = maxIdleConnections;
        this.keepAliveDurationNs = timeUnit.toNanos(keepAliveDuration);

        // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
        if (keepAliveDuration <= 0) {
            throw new IllegalArgumentException("keepAliveDuration <= 0: " + keepAliveDuration);
        }
    }

    /**
     * Returns the number of idle connections in the pool.
     */
    public synchronized int idleConnectionCount() {
        int total = 0;
        for (RealConnection connection : connections) {
            if (connection.allocations.isEmpty()) total++;
        }
        return total;
    }

    /**
     * Returns total number of connections in the pool. Note that prior to OkHttp 2.7 this included
     * only idle connections and HTTP/2 connections. Since OkHttp 2.7 this includes all connections,
     * both active and inactive. Use {@link #idleConnectionCount()} to count connections not currently
     * in use.
     */
    public synchronized int connectionCount() {
        return connections.size();
    }

    /**
     * Returns a recycled connection to {@code address}, or null if no such connection exists. The
     * route is null if the address has not yet been routed.
     */
    @Nullable
    RealConnection get(Address address, StreamAllocation streamAllocation, Route route) {
        assert (Thread.holdsLock(this));
        for (RealConnection connection : connections) {
            if (connection.isEligible(address, route)) {
                streamAllocation.acquire(connection, true);
                return connection;
            }
        }
        return null;
    }

    /**
     * Replaces the connection held by {@code streamAllocation} with a shared connection if possible.
     * This recovers when multiple multiplexed connections are created concurrently.
     */
    @Nullable
    Socket deduplicate(Address address, StreamAllocation streamAllocation) {
        assert (Thread.holdsLock(this));
        for (RealConnection connection : connections) {
            if (connection.isEligible(address, null)
                    && connection.isMultiplexed()
                    && connection != streamAllocation.connection()) {
                return streamAllocation.releaseAndAcquire(connection);
            }
        }
        return null;
    }

    void put(RealConnection connection) {
        assert (Thread.holdsLock(this));
        //没有任何连接时,cleanupRunning = false;
        // 即没有任何链接时才会去执行executor.execute(cleanupRunnable);
        // 从而保证每个连接池最多只能运行一个线程。
        if (!cleanupRunning) {
            cleanupRunning = true;
            executor.execute(cleanupRunnable);
        }
        connections.add(connection);
    }

    /**
     * Notify this pool that {@code connection} has become idle. Returns true if the connection has
     * been removed from the pool and should be closed.
     */
    boolean connectionBecameIdle(RealConnection connection) {
        assert (Thread.holdsLock(this));
        if (connection.noNewStreams || maxIdleConnections == 0) {
            connections.remove(connection);
            return true;
        } else {
            notifyAll(); // Awake the cleanup thread: we may have exceeded the idle connection limit.
            return false;
        }
    }

    /**
     * Close and remove all idle connections in the pool.
     */
    public void evictAll() {
        List<RealConnection> evictedConnections = new ArrayList<>();
        synchronized (this) {
            for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
                RealConnection connection = i.next();
                if (connection.allocations.isEmpty()) {
                    connection.noNewStreams = true;
                    evictedConnections.add(connection);
                    i.remove();
                }
            }
        }

        for (RealConnection connection : evictedConnections) {
            closeQuietly(connection.socket());
        }
    }

    /**
     * Performs maintenance on this pool, evicting the connection that has been idle the longest if
     * either it has exceeded the keep alive limit or the idle connections limit.
     * <p>
     * <p>Returns the duration in nanos to sleep until the next scheduled call to this method. Returns
     * -1 if no further cleanups are required.
     */
    //对该池执行维护，如果它超出保持活动限制或空闲连接限制，则驱逐空闲时间最长的连接。
    //返回纳秒级的持续时间，直到下次预定调用此方法为止。 如果不需要进一步清理，则返回-1。
    long cleanup(long now) {
        int inUseConnectionCount = 0;
        int idleConnectionCount = 0;
        RealConnection longestIdleConnection = null;
        long longestIdleDurationNs = Long.MIN_VALUE;

        // Find either a connection to evict, or the time that the next eviction is due.
        synchronized (this) {
            //遍历所有的连接，标记处不活跃的连接。
            for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
                RealConnection connection = i.next();

                // If the connection is in use, keep searching.
                //1. 查询此连接内部的StreanAllocation的引用数量。
                if (pruneAndGetAllocationCount(connection, now) > 0) {
                    inUseConnectionCount++;
                    continue;
                }

                idleConnectionCount++;

                // If the connection is ready to be evicted, we're done.
                //2. 标记空闲连接。
                long idleDurationNs = now - connection.idleAtNanos;
                if (idleDurationNs > longestIdleDurationNs) {
                    longestIdleDurationNs = idleDurationNs;
                    longestIdleConnection = connection;
                }
            }

            if (longestIdleDurationNs >= this.keepAliveDurationNs
                    || idleConnectionCount > this.maxIdleConnections) {
                // We've found a connection to evict. Remove it from the list, then close it below (outside
                // of the synchronized block).
                //3. 如果空闲连接超过5个或者keepalive时间大于5分钟，则将该连接清理掉。
                connections.remove(longestIdleConnection);
            } else if (idleConnectionCount > 0) {
                // A connection will be ready to evict soon.
                //4. 返回此连接的到期时间，供下次进行清理。
                return keepAliveDurationNs - longestIdleDurationNs;
            } else if (inUseConnectionCount > 0) {
                // All connections are in use. It'll be at least the keep alive duration 'til we run again.
                //5. 全部都是活跃连接，5分钟时候再进行清理。
                return keepAliveDurationNs;
            } else {
                // No connections, idle or in use.
                //6. 没有任何连接，跳出循环。
                cleanupRunning = false;
                return -1;
            }
        }
        //7. 关闭连接，返回时间0，立即再次进行清理。
        closeQuietly(longestIdleConnection.socket());

        // Cleanup again immediately.
        return 0;
    }

    /**
     * Prunes any leaked allocations and then returns the number of remaining live allocations on
     * {@code connection}. Allocations are leaked if the connection is tracking them but the
     * application code has abandoned them. Leak detection is imprecise and relies on garbage
     * collection.
     * *修剪任何泄漏的分配，然后返回{@code connection}上剩余的实时分配数量。
     * 如果连接正在跟踪它们，但是应用程序代码已经放弃它们，则分配会泄漏。
     * 泄漏检测不准确，依靠垃圾收集。
     */


    private int pruneAndGetAllocationCount(RealConnection connection, long now) {
        //虚引用列表
        List<Reference<StreamAllocation>> references = connection.allocations;
        //遍历虚引用列表
        for (int i = 0; i < references.size(); ) {
            Reference<StreamAllocation> reference = references.get(i);
            //如果虚引用StreamAllocation正在被使用，则跳过进行下一次循环，
            if (reference.get() != null) {
                //引用计数
                i++;
                continue;
            }

            // We've discovered a leaked allocation. This is an application bug.
            StreamAllocation.StreamAllocationReference streamAllocRef =
                    (StreamAllocation.StreamAllocationReference) reference;
            String message = "A connection to " + connection.route().address().url()
                    + " was leaked. Did you forget to close a response body?";
            Platform.get().logCloseableLeak(message, streamAllocRef.callStackTrace);
            //否则移除该StreamAllocation引用
            references.remove(i);
            connection.noNewStreams = true;

            // If this was the last allocation, the connection is eligible for immediate eviction.
            // 如果所有的StreamAllocation引用都没有了，返回引用计数0
            if (references.isEmpty()) {
                connection.idleAtNanos = now - keepAliveDurationNs;
                return 0;
            }
        }
        //返回引用列表的大小，作为引用计数
        return references.size();
    }
}
