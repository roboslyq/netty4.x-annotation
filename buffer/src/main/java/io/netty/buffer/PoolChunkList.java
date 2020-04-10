/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import io.netty.util.internal.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.lang.Math.*;

import java.nio.ByteBuffer;

/**
 * PoolChunkList负责管理多个chunk的生命周期，在此基础上对内存分配进行进一步的优化。
 * 每个PoolChunkList实例维护了一个PoolChunk链表，自身也形成一个链表
 * @param <T>
 */
final class PoolChunkList<T> implements PoolChunkListMetric {
    private static final Iterator<PoolChunkMetric> EMPTY_METRICS = Collections.<PoolChunkMetric>emptyList().iterator();
    private final PoolArena<T> arena;
    private final PoolChunkList<T> nextList;
    private final int minUsage;
    private final int maxUsage;
    private final int maxCapacity;
    private PoolChunk<T> head;

    // This is only update once when create the linked like list of PoolChunkList in PoolArena constructor.
    private PoolChunkList<T> prevList;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunkList(PoolArena<T> arena, PoolChunkList<T> nextList, int minUsage, int maxUsage, int chunkSize) {
        assert minUsage <= maxUsage;
        this.arena = arena;
        this.nextList = nextList;
        this.minUsage = minUsage;
        this.maxUsage = maxUsage;
        maxCapacity = calculateMaxCapacity(minUsage, chunkSize);
    }

    /**
     * Calculates the maximum capacity of a buffer that will ever be possible to allocate out of the {@link PoolChunk}s
     * that belong to the {@link PoolChunkList} with the given {@code minUsage} and {@code maxUsage} settings.
     */
    private static int calculateMaxCapacity(int minUsage, int chunkSize) {
        minUsage = minUsage0(minUsage);

        if (minUsage == 100) {
            // If the minUsage is 100 we can not allocate anything out of this list.
            return 0;
        }

        // Calculate the maximum amount of bytes that can be allocated from a PoolChunk in this PoolChunkList.
        //
        // As an example:
        // - If a PoolChunkList has minUsage == 25 we are allowed to allocate at most 75% of the chunkSize because
        //   this is the maximum amount available in any PoolChunk in this PoolChunkList.
        return  (int) (chunkSize * (100L - minUsage) / 100L);
    }

    void prevList(PoolChunkList<T> prevList) {
        assert this.prevList == null;
        this.prevList = prevList;
    }

    /**
     * 随着chunk中page的不断分配和释放，会导致很多碎片内存段，大大增加了之后分配一段连续内存的失败率，针对这种情况，
     * 可以把内存使用率较大的chunk放到PoolChunkList链表更后面。
     * 假设poolChunkList中已经存在多个chunk。当分配完内存后，如果当前chunk的使用量超过maxUsage，则把该chunk从当前链表中删除，添加到下一个链表中。
     * @param buf
     * @param reqCapacity
     * @param normCapacity
     * @return
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        if (normCapacity > maxCapacity) {
            // Either this PoolChunkList is empty or the requested capacity is larger then the capacity which can
            // be handled by the PoolChunks that are contained in this PoolChunkList.
            return false;
        }

        for (PoolChunk<T> cur = head; cur != null; cur = cur.next) {
            if (cur.allocate(buf, reqCapacity, normCapacity)) {
                if (cur.usage() >= maxUsage) {
                    remove(cur);
                    nextList.add(cur);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 随便chunk中内存的释放，其内存使用率也会随着下降，当下降到minUsage时，该chunk会移动到前一个列表中
     * 从poolChunkList的实现可以看出，每个chunkList的都有一个上下限：minUsage和maxUsage，两个相邻的chunkList，前一个的maxUsage和后一个的minUsage必须有一段交叉值进行缓冲，否则会出现某个chunk的usage处于临界值，而导致不停的在两个chunk间移动。
     *
     * 所以chunk的生命周期不会固定在某个chunkList中，随着内存的分配和释放，根据当前的内存使用率，在chunkList链表中前后移动
     * @param chunk
     * @param handle
     * @param nioBuffer
     * @return
     */
    boolean free(PoolChunk<T> chunk, long handle, ByteBuffer nioBuffer) {
        chunk.free(handle, nioBuffer);
        if (chunk.usage() < minUsage) {
            remove(chunk);
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }
        return true;
    }

    private boolean move(PoolChunk<T> chunk) {
        assert chunk.usage() < maxUsage;

        if (chunk.usage() < minUsage) {
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }

        // PoolChunk fits into this PoolChunkList, adding it here.
        add0(chunk);
        return true;
    }

    /**
     * Moves the {@link PoolChunk} down the {@link PoolChunkList} linked-list so it will end up in the right
     * {@link PoolChunkList} that has the correct minUsage / maxUsage in respect to {@link PoolChunk#usage()}.
     */
    private boolean move0(PoolChunk<T> chunk) {
        if (prevList == null) {
            // There is no previous PoolChunkList so return false which result in having the PoolChunk destroyed and
            // all memory associated with the PoolChunk will be released.
            assert chunk.usage() == 0;
            return false;
        }
        return prevList.move(chunk);
    }

    void add(PoolChunk<T> chunk) {
        if (chunk.usage() >= maxUsage) {
            nextList.add(chunk);
            return;
        }
        add0(chunk);
    }

    /**
     * Adds the {@link PoolChunk} to this {@link PoolChunkList}.
     */
    void add0(PoolChunk<T> chunk) {
        chunk.parent = this;
        if (head == null) {
            head = chunk;
            chunk.prev = null;
            chunk.next = null;
        } else {
            chunk.prev = null;
            chunk.next = head;
            head.prev = chunk;
            head = chunk;
        }
    }

    private void remove(PoolChunk<T> cur) {
        if (cur == head) {
            head = cur.next;
            if (head != null) {
                head.prev = null;
            }
        } else {
            PoolChunk<T> next = cur.next;
            cur.prev.next = next;
            if (next != null) {
                next.prev = cur.prev;
            }
        }
    }

    @Override
    public int minUsage() {
        return minUsage0(minUsage);
    }

    @Override
    public int maxUsage() {
        return min(maxUsage, 100);
    }

    private static int minUsage0(int value) {
        return max(1, value);
    }

    @Override
    public Iterator<PoolChunkMetric> iterator() {
        synchronized (arena) {
            if (head == null) {
                return EMPTY_METRICS;
            }
            List<PoolChunkMetric> metrics = new ArrayList<PoolChunkMetric>();
            for (PoolChunk<T> cur = head;;) {
                metrics.add(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
            }
            return metrics.iterator();
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        synchronized (arena) {
            if (head == null) {
                return "none";
            }

            for (PoolChunk<T> cur = head;;) {
                buf.append(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
                buf.append(StringUtil.NEWLINE);
            }
        }
        return buf.toString();
    }

    void destroy(PoolArena<T> arena) {
        PoolChunk<T> chunk = head;
        while (chunk != null) {
            arena.destroyChunk(chunk);
            chunk = chunk.next;
        }
        head = null;
    }
}
