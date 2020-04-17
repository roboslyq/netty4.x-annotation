/*
 * Copyright 2013 The Netty Project
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

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import jdk.internal.org.objectweb.asm.Handle;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.Stack;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stacDefaultHandlek.
 * 1、轻量级的对象池化技术，基于Thread-Local技术栈。
 * 2、Recycler用来实现对象池，其中对应堆内存和直接内存的池化实现分别是PooledHeapByteBuf和PooledDirectByteBuf。
 *
 *    Recycler主要提供了3个方法：
         get():获取一个对象。
         recycle(T, Handle):收回一个对象，T为对象泛型。
         newObject(Handle):当没有可用对象时创建对象的实现方法。

 * 3、对象回收池
 *      1)、因为具有抽象方法，所以Recycler是抽象类
 *      2)、几乎不需要锁
 *      3)、线程A创建的对象X只能由线程A进行get，线程B无法get，但是假设线程A将对象X传递给线程B进行使用，线程B使用结束后，可以进行recycle到WeakOrderQueue中；
 *          之后线程A进行get的时候会将对象X转移到自己的Stack中
 *
 * 4、同步问题：
 *      （1）假设线程A进行get，线程B也进行get，无锁（二者各自从自己的stack或者从各自的weakOrderQueue中进行获取）
 *      （2）假设线程A进行get对象X，线程B进行recycle对象X，无锁（假设A无法直接从其Stack获取，从WeakOrderQueue进行获取，由于stack.head是volatile的，线程Brecycle的对象X可以被线程A立即获取）
 *      （3）假设线程C和线程Brecycle线程A的对象X，此时需要加锁（具体见Stack.setHead()）
 *
 * 5、使用方式
 *
 *    private static Recycler<HandledObject> newRecycler(int max) {
 *         return new Recycler<HandledObject>(max) {
 *             //如果当前缓冲池为空，训实例化一个新对象
 *             @Override
 *             protected HandledObject newObject(
 *                     Recycler.Handle<HandledObject> handle) {
 *                 return new HandledObject(handle);
 *             }
 *         };
 *     }
 * @param <T> the type of the pooled object
 *           泛型<T>是具体的缓存对象。
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);
    /**
     * 表示一个不需要回收的包装对象，用于在禁止使用Recycler功能时进行占位的功能
     * 仅当io.netty.recycler.maxCapacityPerThread<=0时用到
     */
    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    /**
     * 唯一ID生成器,用在两处：
     *      1、当前线程ID
     *      2、WeakOrderQueue的id
     */
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    /**
     * static变量, 生成并获取一个唯一id.
     * 用于pushNow()中的item.recycleId和item.lastRecycleId的设定
     */
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();

    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    /**
     * 每个Stack默认的最大容量
     * 注意：
     * 1、当io.netty.recycler.maxCapacityPerThread<=0时，禁用回收功能（在netty中，只有=0可以禁用，<0默认使用4k）
     * 2、Recycler中有且只有两个地方存储DefaultHandle对象（Stack和Link），
     * 最多可存储MAX_CAPACITY_PER_THREAD + 最大可共享容量 = 4k + 4k/2 = 6k
     *
     * 实际上，在netty中，Recycler提供了两种设置属性的方式
     *      第一种：-Dio.netty.recycler.ratio等jvm启动参数方式
     *      第二种：Recycler(int maxCapacityPerThread)构造器传入方式
     */
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    /**
     * 每个Stack默认的初始容量，默认为256
     * 后续根据需要进行扩容，直到<=MAX_CAPACITY_PER_THREAD
     */
    private static final int INITIAL_CAPACITY;
    /**
     * 最大可共享的容量因子。
     * 最大可共享的容量 = maxCapacity / maxSharedCapacityFactor，maxSharedCapacityFactor默认为2
     */
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    /**
     * 每个线程可拥有多少个WeakOrderQueue，默认为2*cpu核数
     * 实际上就是当前线程的Map<Stack<?>, WeakOrderQueue>的size最大值
     */
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    /**
     * WeakOrderQueue中的Link中的数组DefaultHandle<?>[] elements容量，默认为16，
     * 当一个Link中的DefaultHandle元素达到16个时，会新创建一个Link进行存储，这些Link组成链表，当然
     * 所有的Link加起来的容量要<=最大可共享容量。
     */
    private static final int LINK_CAPACITY;
    /**
     * 回收因子，默认为8。
     * 即默认每8个对象，允许回收一次，直接扔掉7个，可以让recycler的容量缓慢的增大，避免爆发式的请求
     */
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int ratioMask;
    private final int maxDelayedQueuesPerThread;
    /**
     * 完成Stack初始化。
     *
     *  1、每个Recycler类（而不是每一个Recycler对象）都有一个DELAYED_RECYCLED
     *   原因：可以根据一个Stack<T>对象唯一的找到一个WeakOrderQueue对象，所以此处不需要每个对象建立一个DELAYED_RECYCLED
     *  2、由于DELAYED_RECYCLED是一个类变量，所以需要包容多个T，此处泛型需要使用?
     *  3、WeakHashMap：当Stack没有强引用可达时，整个Entry{Stack<?>, WeakOrderQueue}都会加入相应的弱引用队列等待回收
     *
     */
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            /*
             * 完成Stack初始化
             */
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    /**
     * 获取对象
     * @return
     */
    @SuppressWarnings("unchecked")
    public final T get() {
        /*
         * 0、如果maxCapacityPerThread == 0，禁止回收功能
         * 创建一个对象，其Recycler.Handle<User> handle属性为NOOP_HANDLE，该对象的recycle(Object object)不做任何事情，即不做回收
         */
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        //1、获取当前线程的Stack<T>对象
        Stack<T> stack = threadLocal.get();
        //2、从Stack<T>对象中获取DefaultHandle<T>：核心方法Stack#pop()
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {//首次进入为true,需要创建对象
            //3、 新建一个DefaultHandle对象 -> 然后新建T对象 -> 存储到DefaultHandle对象
            //   此处会发现一个DefaultHandle对象对应一个Object对象，二者相互包含。
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     * 收回对象(将发出去给线程的对象收回回来，可以供其它线程重新使用)
     * 此方法已经过时，请直接使用 Handle#recycle(Object)方法：此方法能完成不同线程间的对象回收。
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }
        //收回对象o
        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    /**
     * 抽象方法，由具体的业务子类实现
     * @param handle
     * @return
     */
    protected abstract T newObject(Handle<T> handle);

    /**
     * 提供对象的回收功能，由子类进行复写
     * 目前该接口只有两个实现：NOOP_HANDLE和DefaultHandle
     * @param <T>
     */
    public interface Handle<T> {
        void recycle(T object);
    }

    /**
     * 1、原始对象T的包装类,使用value<Object>属性持有原始对象的引用
     * 2、使用栈Stack结构
     * @param <T>
     */
    static final class DefaultHandle<T> implements Handle<T> {
        /**
         * 1、在pushNow() 方法中，与 recycleId 一样，被赋值为：OWN_THREAD_ID
         * 2、在pushLater中的add(DefaultHandle handle)操作中 ，被赋值为id（当前的WeakOrderQueue的唯一ID）
         * 3、在poll()中被重置为0
         */
        private int lastRecycledId;
        /**
         * 1、在pushNow() 方法中，与 lastRecycledId 一样，被赋值为：OWN_THREAD_ID
         * 2、在poll()中重置为0
         */
        private int recycleId;
        /**
         * 当前对象是否已经被回收(因为当前DefaultHandler可能已经分配给其它线程使用了，所以需要回收)
         */
        boolean hasBeenRecycled;
        /**
         * 当前的DefaultHandle对象所属的Stack
         */
        private Stack<?> stack;
        /**
         * 原始被池化的对象
         */
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        /**
         * 收回对象
         * @param object
         */
        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }
            //收回对象，this指的是当前的DefaultHandle对象:核心方法Stack#push()
            stack.push(this);
        }
    }

    /**
     * 类属性，所有实例共享
     * Recycler <--1:1-->Thread
     * Recycler <--1:1-->Stack
     * Recycler <--1:N-->WeakOrderQueue (通过Map<Stack<?>, WeakOrderQueue>)实现。即一个JVM，Recycler对应N个WeakOrderQueue，并且这个WeakOrderQueue是共享的区域。
     *      但是，每个不同的线程访问WeakOrderQueue时，可以通过自己持有的Stack这个key找到具体的WeakOrderQueue.
     */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate(适当的) guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    //====================================WeakOrderQueue start=======================================================

    /**
     * 多线程共享的队列:
     * 存储其它线程收回到分配线程的对象，当某个线程从Stack中获取不到对象时会从WeakOrderQueue中获取对象。
     */
    private static final class WeakOrderQueue {
        /**
         * 如果DELAYED_RECYCLED中的key-value对已经达到了maxDelayedQueues，
         * 对于后续的Stack，其对应的WeakOrderQueue设置为DUMMY，
         * 后续如果检测到DELAYED_RECYCLED中对应的Stack的value是WeakOrderQueue.DUMMY时，直接返回，不做存储操作
         */
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        // 收回对象存储在链表某个Link节点里，当Link节点存储的收回对象满了时会新建1个Link放在Link链表尾。
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            private int readIndex;
            Link next;
        }

        // This act as a place holder for the head Link but also will reclaim space once finalized.
        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        static final class Head {
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
            @Override
            protected void finalize() throws Throwable {
                try {
                    super.finalize();
                } finally {
                    Link head = link;
                    link = null;
                    while (head != null) {
                        reclaimSpace(LINK_CAPACITY);
                        Link next = head.next;
                        // Unlink to help GC and guard against GC nepotism.
                        head.next = null;
                        head = next;
                    }
                }
            }

            void reclaimSpace(int space) {
                assert space >= 0;
                availableSharedCapacity.addAndGet(space);
            }

            boolean reserveSpace(int space) {
                return reserveSpace(availableSharedCapacity, space);
            }

            static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
                assert space >= 0;
                for (;;) {
                    int available = availableSharedCapacity.get();
                    if (available < space) {
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet(available, available - space)) {
                        return true;
                    }
                }
            }
        }
        //Link数组
        // chain of data items
        private final Head head;
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        //向同一堆栈的另一个延迟项队列的指针
        private WeakOrderQueue next;
        /**
         * 当前WeakReference对应的拥有者(Thread)
         * 1、why WeakReference？与Stack相同。
         * 2、作用是在poll的时候，如果owner不存在了，则需要将该线程所包含的WeakOrderQueue的元素释放，然后从链表中删除该Queue。
         */
        private final WeakReference<Thread> owner;
        //WeakOrderQueue的唯一标记
        private final int id = ID_GENERATOR.getAndIncrement();

        private WeakOrderQueue() {
            owner = null;
            head = new Head(null);
        }

        /**
         * 构造函数
         * @param stack
         * @param thread
         */
        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            //创建tail
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            //创建head<注意,这里我们没有保存stack对象本身,而是仅仅保存>
            head = new Head(stack.availableSharedCapacity);

            head.link = tail;
            owner = new WeakReference<Thread>(thread);
        }
        /**
         * 正常情况，不同线程收回资源，会以此方法为入口。
         * 此方法注意构造函数的两个关键参数:
         * @param stack     对应Recycler中的中stack(此stack与一个具体的Thread A 绑定，即创建Recycler所在的线程)
         * @param thread    当前线程Thread B，即Thread A将生产出来的对象实例(被包装为一个具体的Handle)给当前线程Thread B使用。然后B发起recycle()操作。
         * 所以WeakOrderQueue是由两个元素确定唯一的：另一个线程生产的stack 和 当前收回线程 thread B。所以如果生产线程Thread A生产多个Handle分别给Thread B, Thread C等
         *                  多个线程使用，那么，这个WeakOrderQueue会有多个，并且最终组成一个link链表结构。
         */
        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            //构造函数
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);

            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         * 分配一个新的WeakOrderQueue
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            // 如果该stack的可用共享空间还能再容下1个WeakOrderQueue，那么创建1个WeakOrderQueue，否则返回null
            return Head.reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? newQueue(stack, thread) : null;
        }

        void add(DefaultHandle<?> handle) {
            handle.lastRecycledId = id;

            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                if (!head.reserveSpace(LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = new Link();

                writeIndex = tail.get();
            }
            tail.elements[writeIndex] = handle;
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            Link head = this.head.link;
            if (head == null) {
                return false;
            }

            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                this.head.link = head = head.next;
            }

            final int srcStart = head.readIndex;
            int srcEnd = head.get();
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;

            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;

                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.reclaimSpace(LINK_CAPACITY);
                    this.head.link = head.next;
                }

                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }
    //====================================WeakOrderQueue end=======================================================

    /**
     * Netty为了池化自己实现的Stack数据结构: 对象池的真正的 “池”
     * @param <T>
     */
    static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        /**
         * 该Stack所属的线程
         * why WeakReference?
         * 假设该线程对象在外界已经没有强引用了，那么实际上该线程对象就可以被回收了。但是如果此处用的是强引用，那么虽然外界不再对该线程有强引用，
         * 但是该stack对象还持有强引用（假设用户存储了DefaultHandle对象，然后一直不释放，而DefaultHandle对象又持有stack引用），导致该线程对象无法释放。
         *
         */
        final WeakReference<Thread> threadRef;

        /**
         * 可用的共享内存大小，默认为maxCapacity/maxSharedCapacityFactor = 4k/2 = 2k = 2048
         * 假设当前的Stack是线程A的，则其他线程B~X等去回收线程A创建的对象时，可回收最多A创建的多少个对象
         * 注意：那么实际上线程A创建的对象最终最多可以被回收maxCapacity + availableSharedCapacity个，默认为6k个
         * why AtomicInteger?
         * 当线程B和线程C同时创建线程A的WeakOrderQueue的时候，会同时分配内存，需要同时操作availableSharedCapacity
         * 具体见：WeakOrderQueue.allocate
         */
        final AtomicInteger availableSharedCapacity;
        //DELAYED_RECYCLED中最多可存储的{Stack，WeakOrderQueue}键值对个数
        final int maxDelayedQueues;
        //elements最大的容量：默认最大为4k，4096
        private final int maxCapacity;
        //默认为8-1=7，即2^3-1，控制每8个元素只有一个可以被recycle，其余7个被扔掉
        private final int ratioMask;
        //Stack底层数据结构，真正的用来存储数据
        private DefaultHandle<?>[] elements;
        //elements中的元素个数，同时也可作为操作数组的下标
        private int size;
        /**
         * 每有一个元素将要被回收, 则该值+1，例如第一个被回收的元素的handleRecycleCount=handleRecycleCount+1=0
         * 与ratioMask配合，用来决定当前的元素是被回收还是被drop。
         * 例如 ++handleRecycleCount & ratioMask（7），其实相当于 ++handleRecycleCount % 8，
         * 则当 ++handleRecycleCount = 0/8/16/...时，元素被回收，其余的元素直接被drop
         */
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.
        private WeakOrderQueue cursor, prev;

        /**
         * 该值是当线程B回收线程A创建的对象时，线程B会为线程A的Stack对象创建一个WeakOrderQueue对象，
         * 该WeakOrderQueue指向这里的head，用于后续线程A对对象的查找操作
         * Q: why volatile?
         * A: 假设线程A正要读取对象X，此时需要从其他线程的WeakOrderQueue中读取，假设此时线程B正好创建Queue，并向Queue中放入一个对象X；假设恰好次Queue就是线程A的Stack的head
         * 使用volatile可以立即读取到该queue。
         *
         * 对于head的设置，具有同步问题。具体见此处的volatile和synchronized void setHead(WeakOrderQueue queue)
         */
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.ratioMask = ratioMask;
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        /**
         * 假设线程B和线程C同时回收线程A的对象时，有可能会同时newQueue，就可能同时setHead，所以这里需要加锁
         * 以head==null的时候为例，
         * 加锁：
         * 线程B先执行，则head = 线程B的queue；之后线程C执行，此时将当前的head也就是线程B的queue作为线程C的queue的next，组成链表，之后设置head为线程C的queue
         * 不加锁：
         * 线程B先执行queue.setNext(head);此时线程B的queue.next=null->线程C执行queue.setNext(head);线程C的queue.next=null
         * -> 线程B执行head = queue;设置head为线程B的queue -> 线程C执行head = queue;设置head为线程C的queue
         *
         * 注意：此时线程B和线程C的queue没有连起来，则之后的poll()就不会从B进行查询。（B就是资源泄露）
         */
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        /**
         * Stack获取元素
         * @return
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            if (size == 0) {
                // 如果当前Stack没有多余的元素，则从WeakOrderQueue中获取
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
            }
            size --;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            /*
             * 清空当前线程ID，因为recycleId 或 lastRecycledId保存的是当前线程的ID,如果ID不为空，
             * 表示Handle已经被回收，不能再发起回收操作。
             * 所以，在使用对象前，需要将这个“回收”标识清零，以便后续能正常归还。
             * 详情见push()方法
             */
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            this.size = size;
            return ret;
        }

        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                cursor = head;
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.next;
                if (cursor.owner.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        /**
         * 收回对象到Stack中
         * @param item
         */
        void push(DefaultHandle<?> item) {
            //获取当前线程
            Thread currentThread = Thread.currentThread();
            //如果当前线程是Stack对应的线程threadRef,则直接pushnow()
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                //如果该stack就是本线程的stack，那么直接把DefaultHandle放到该stack的数组里
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                //如果该stack不是本线程的stack，那么把该DefaultHandle放到该stack的WeakOrderQueue中
                pushLater(item, currentThread);
            }
        }

        /**
         * 直接把DefaultHandle放到stack的数组里，如果数组满了那么扩展该数组为当前2倍大小
         * @param item
         */
        private void pushNow(DefaultHandle<?> item) {
            //如果对象已经回收，不需要继续回收，抛出异常
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            //记录 recycleId
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            //扩容(2的倍数)
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }
            //存值
            elements[size] = item;
            this.size = size + 1;
        }

        /**
         * 非当前线程回收:先将item元素加入WeakOrderQueue，后续再从WeakOrderQueue中将元素压入Stack中
         * @param item
         * @param thread
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            /*
             * Recycler有1个stack->WeakOrderQueue映射，每个stack会映射到1个WeakOrderQueue，这个WeakOrderQueue是该stack关联的其它线程WeakOrderQueue链表的head WeakOrderQueue。
             * 当其它线程回收对象到该stack时会创建1个WeakOrderQueue中并加到stack的WeakOrderQueue链表中。
             */
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            // this = Stack，如果当前Stack对应的WeakOrderQueue为空，则进行初始化
            WeakOrderQueue queue = delayedRecycled.get(this);
            //如果queue为空，进行初始化
            if (queue == null) {
                // 如果DELAYED_RECYCLED中的key-value对已经达到了maxDelayedQueues，则后续的无法回收 - 内存保护
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    // 如果delayedRecycled满了那么将1个伪造的WeakOrderQueue（DUMMY）放到delayedRecycled中，并丢弃该对象（DefaultHandle）
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                // 创建1个WeakOrderQueue
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }
            //将对象放入到该stack对应的WeakOrderQueue中
            queue.add(item);
        }
        /**
         * 两个drop的时机
         * 1、pushNow：当前线程将数据push到Stack中
         * 2、transfer：将其他线程的WeakOrderQueue中的数据转移到当前的Stack中
         */
        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                if ((++handleRecycleCount & ratioMask) != 0) {
                    // Drop the object.
                    return true;
                }
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
