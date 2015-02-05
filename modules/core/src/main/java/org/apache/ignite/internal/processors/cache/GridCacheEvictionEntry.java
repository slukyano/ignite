/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.transactions.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.internal.processors.cache.GridCachePeekMode.*;

/**
 * Entry wrapper that never obscures obsolete entries from user.
 */
public class GridCacheEvictionEntry<K, V> implements CacheEntry<K, V>, Externalizable {
    /** */
    private static final long serialVersionUID = 0L;

    /** Static logger to avoid re-creation. */
    private static final AtomicReference<IgniteLogger> logRef = new AtomicReference<>();

    /** Logger. */
    protected static volatile IgniteLogger log;

    /** Cached entry. */
    @GridToStringInclude
    protected GridCacheEntryEx<K, V> cached;

    /**
     * Empty constructor required for {@link Externalizable}.
     */
    public GridCacheEvictionEntry() {
        // No-op.
    }

    /**
     * @param cached Cached entry.
     */
    @SuppressWarnings({"TypeMayBeWeakened"})
    protected GridCacheEvictionEntry(GridCacheEntryEx<K, V> cached) {
        this.cached = cached;

        log = U.logger(cached.context().kernalContext(), logRef, this);
    }

    /** {@inheritDoc} */
    @Override public CacheProjection<K, V> projection() {
        return cached.context().cache();
    }

    /** {@inheritDoc} */
    @Override public K getKey() throws IllegalStateException {
        return cached.key();
    }

    /** {@inheritDoc} */
    @Nullable
    @Override public V getValue() throws IllegalStateException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Nullable @Override public V setValue(V val) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public Object version() {
        try {
            return cached.version().drVersion();
        }
        catch (GridCacheEntryRemovedException ignore) {
            return cached.obsoleteVersion().drVersion();
        }
    }

    /** {@inheritDoc} */
    @Override public long expirationTime() {
        return cached.rawExpireTime();
    }

    /** {@inheritDoc} */
    @Override public boolean primary() {
        GridCacheContext<K, V> ctx = cached.context();

        return ctx.config().getCacheMode() == LOCAL ||
            ctx.nodeId().equals(ctx.affinity().primary(cached.key(), ctx.affinity().affinityTopologyVersion()).id());
    }

    /** {@inheritDoc} */
    @Override public boolean backup() {
        GridCacheContext<K, V> ctx = cached.context();

        return ctx.config().getCacheMode() != LOCAL &&
            ctx.affinity().backups(cached.key(), ctx.affinity().affinityTopologyVersion()).contains(ctx.localNode());
    }

    /** {@inheritDoc} */
    @Override public int partition() {
        return cached.partition();
    }

    /** {@inheritDoc} */
    @Override public V peek() {
        try {
            return peek0(SMART, null, cached.context().tm().localTxx());
        }
        catch (IgniteCheckedException e) {
            // Should never happen.
            throw new IgniteException("Unable to perform entry peek() operation.", e);
        }
    }

    /** {@inheritDoc} */
    @Override public V peek(@Nullable Collection<GridCachePeekMode> modes) throws IgniteCheckedException {
        return peek0(modes, CU.<K, V>empty(), cached.context().tm().localTxx());
    }

    /**
     * @param mode Peek mode.
     * @param filter Optional entry filter.
     * @param tx Transaction to peek at (if mode is TX).
     * @return Peeked value.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings({"unchecked"})
    @Nullable private V peek0(@Nullable GridCachePeekMode mode,
        @Nullable IgnitePredicate<CacheEntry<K, V>>[] filter, @Nullable IgniteInternalTx<K, V> tx)
        throws IgniteCheckedException {
        assert tx == null || tx.local();

        if (mode == null)
            mode = SMART;

        try {
            GridTuple<V> peek = cached.peek0(false, mode, filter, tx);

            return peek != null ? peek.get() : null;
        }
        catch (GridCacheEntryRemovedException ignore) {
            return null;
        }
        catch (GridCacheFilterFailedException e) {
            e.printStackTrace();

            assert false;

            return null;
        }
    }

    /**
     * @param modes Peek modes.
     * @param filter Optional entry filter.
     * @param tx Transaction to peek at (if modes contains TX value).
     * @return Peeked value.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable private V peek0(@Nullable Collection<GridCachePeekMode> modes,
        @Nullable IgnitePredicate<CacheEntry<K, V>>[] filter, IgniteInternalTx<K, V> tx) throws IgniteCheckedException {
        if (F.isEmpty(modes))
            return peek0(SMART, filter, tx);

        assert modes != null;

        for (GridCachePeekMode mode : modes) {
            V val = peek0(mode, filter, tx);

            if (val != null)
                return val;
        }

        return null;
    }

    /**
     * @return Unsupported exception.
     */
    private RuntimeException unsupported() {
        return new UnsupportedOperationException("Operation not supported during eviction.");
    }

    /** {@inheritDoc} */
    @Nullable @Override public V reload() throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<V> reloadAsync() {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public boolean evict() {
        GridCacheContext<K, V> ctx = cached.context();

        try {
            assert ctx != null;
            assert ctx.evicts() != null;

            return ctx.evicts().evict(cached, null, false, null);
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to evict entry from cache: " + cached, e);

            return false;
        }
    }

    /** {@inheritDoc} */
    @Override public boolean clear() {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public boolean compact() throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Nullable @Override public V get() throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<V> getAsync() {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Nullable @Override public V set(V val, IgnitePredicate<CacheEntry<K, V>>[] filter) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<V> setAsync(V val, IgnitePredicate<CacheEntry<K, V>>[] filter) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public boolean setx(V val, IgnitePredicate<CacheEntry<K, V>>[] filter) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Boolean> setxAsync(V val, IgnitePredicate<CacheEntry<K, V>>[] filter) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Nullable @Override public V replace(V val) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<V> replaceAsync(V val) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public boolean replace(V oldVal, V newVal) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Boolean> replaceAsync(V oldVal, V newVal) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public long timeToLive() {
        return cached.rawTtl();
    }

    /** {@inheritDoc} */
    @Override public void timeToLive(long ttl) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Nullable @Override public V setIfAbsent(V val) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<V> setIfAbsentAsync(V val) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public boolean setxIfAbsent(V val) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Boolean> setxIfAbsentAsync(V val) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public boolean replacex(V val) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Boolean> replacexAsync(V val) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Nullable @Override public V remove(IgnitePredicate<CacheEntry<K, V>>[] filter) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<V> removeAsync(IgnitePredicate<CacheEntry<K, V>>[] filter) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public boolean removex(IgnitePredicate<CacheEntry<K, V>>[] filter) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Boolean> removexAsync(IgnitePredicate<CacheEntry<K, V>>[] filter) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public boolean remove(V val) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Boolean> removeAsync(V val) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public <V> V addMeta(String name, V val) {
        return cached.addMeta(name, val);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public <V> V meta(String name) {
        return cached.meta(name);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public <V> V removeMeta(String name) {
        return cached.removeMeta(name);
    }

    /** {@inheritDoc} */
    @Override public <V> V putMetaIfAbsent(String name, V val) {
        return cached.putMetaIfAbsent(name, val);
    }

    /** {@inheritDoc} */
    @Override public <V> V putMetaIfAbsent(String name, Callable<V> c) {
        return cached.putMetaIfAbsent(name, c);
    }

    /** {@inheritDoc} */
    @Override public <V> boolean replaceMeta(String name, V curVal, V newVal) {
        return cached.replaceMeta(name, curVal, newVal);
    }

    /** {@inheritDoc} */
    @Override public <V> boolean removeMeta(String name, V val) {
        return cached.removeMeta(name, val);
    }

    /** {@inheritDoc} */
    @Override public boolean isLocked() {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public boolean isLockedByThread() {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public boolean lock(long timeout,
        @Nullable IgnitePredicate<CacheEntry<K, V>>[] filter) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Boolean> lockAsync(long timeout,
        @Nullable IgnitePredicate<CacheEntry<K, V>>[] filter) {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public void unlock(IgnitePredicate<CacheEntry<K, V>>[] filter) throws IgniteCheckedException {
        throw unsupported();
    }

    /** {@inheritDoc} */
    @Override public boolean isCached() {
        return !cached.obsolete();
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(cached.context());
        out.writeObject(cached.key());
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        GridCacheContext<K, V> ctx = (GridCacheContext<K, V>)in.readObject();
        K key = (K)in.readObject();

        cached = ctx.cache().entryEx(key);
    }

    /** {@inheritDoc} */
    @Override public int memorySize() throws IgniteCheckedException{
        return cached.memorySize();
    }

    /** {@inheritDoc} */
    @Override public <T> T unwrap(Class<T> clazz) {
        if(clazz.isAssignableFrom(getClass()))
            return clazz.cast(this);

        throw new IllegalArgumentException();
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return cached.key().hashCode();
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public boolean equals(Object obj) {
        if (obj == this)
            return true;

        if (obj instanceof GridCacheEvictionEntry) {
            GridCacheEvictionEntry<K, V> other = (GridCacheEvictionEntry<K, V>)obj;

            V v1 = peek();
            V v2 = other.peek();

            return
                cached.key().equals(other.cached.key()) &&
                F.eq(cached.context().cache().name(), other.cached.context().cache().name()) &&
                F.eq(v1, v2);
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridCacheEvictionEntry.class, this);
    }
}
