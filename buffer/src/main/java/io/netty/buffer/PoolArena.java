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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.lang.Math.max;

/**
 * netty内存池管理:
 * Arena本身是指一块区域，在内存管理中，Memory Arena是指内存中的一大块连续的区域，PoolArena就是Netty的内存池实现类。
 * 管理是指对内存的分配和使用，即你怎么用这块内存。如果是你有这么一块内存，你是怎么使用它呢？如何分配，如果回收？
 * 因此，需要抽象出一套概念，来管理这一块内存。
 * (1)需要对内存分块，以便分给不同的对象使用。并且块分等级，这样可以实现不同大小内存块申请时，进行相关优化。
 *    块有Arena,Chunk,SubPage..然后这些可以构造链表，组成更大的块。
 *    Arena,Chunk,SubPage是从三个不同层面来实现对内存的抽象。但它们描述的都是同一块内存。
 *    netty总的内存池是一个数组，数组每一个成员是一个独立的内存池。相当于一个国家(netty)有多个省(poolArena)分别自治管理不同的地区。
 * (2)需要标识哪些内存被用，哪些没用，然后回收(最简单位图思想)
 *
 *
 * (99)参考jemalloc
 ------------------------------------------------------------

 * 1、PoolArena是一个抽象类，其子类为HeapArena和DirectArena对应堆内存(heap buffer)和堆外直接内存(direct buffer)，
 *  除了操作的内存(byte[]和ByteBuffer)不同外两个类完全一致）。
 * 2、该类的实现接口是PoolArenaMetric，是一些信息的统计分析。
 * 3、Netty对内存的组织和管理也就主要集中在如何管理和组织Chunk和Page。
 * 4、具体管理方式如下：
 *      PoolArena通过6个PoolChunkList来管理PoolChunk，而每个PoolChunk由N个PoolSubpage构成，即将PoolChunk的里面底层实现 T memory分成N段，
 *      每段就是一个PoolSubpage。
 *      当用户申请一个Buf时，使用Arena所拥有的chunk所管辖的page分配内存，内存分配的落地点为 T memory上。
 *
 * 5、Netty中的内存管理应该是借鉴了FreeBSD内存管理的思想——jemalloc，Netty内存分配过程中总体遵循以下规则：
 *    1>优先从缓存中分配
 *       2>如果缓存中没有的话，从内存池看看有没有剩余可用的
 *          3>如果已申请的没有的话，再真正申请内存
 *              4>分段管理，每个内存大小范围使用不同的分配策略
 * 6、分配策略：
 *   netty根据需要分配内存的大小使用不同的分配策略，主要分为以下几种情况(pageSize默认是8K, chunkSize默认是16m)：
 *      tiny: allocateSize<512，allocateSubpage
 *      small: pageSize>=allocateSize >=512，allocateSubpage
 *      normal: chunkSize >= allocateSize > pageSize ，allocateRun
 *      huge: allocateSize > chunkSize
 * 7、具体分配过程
 *     1、new一个ByteBuf，如果是direct则new：PooledUnsafeDirectByteBuf
 *     2、从缓存中查找，没有可用的缓存进行下一步
 *     3、从内存池中查找可用的内存，查找的方式如上所述（tiny、small、normal）
 *     4、如果找不到则重新申请内存，并将申请到的内存放入内存池
 *     5、使用申请到的内存初始化ByteBuf
 *  详细分配过程：
 *      以分配30B的内存为例：
 *      1)、PoolArena首先会判断目标内存在哪个内存范围，30 < 496byte，因而会将其交由tinySubpagePools进行申请；
 *      2)、由于tinySubpagePools中每个等级的内存块划分是以16byte为单位的，因而PoolArena会将目标内存扩容到大于其的第一个16的倍数，也就是32
 *          (如果申请的内存大小在smallSubpagePools或者PoolChunkList中，那么其扩容的方式则是查找大于其值的第一个2的指数次幂)。
 *          32对应的是tinySubpagePools的下标为2的PoolSubpage链表，这里就会取tinySubpagePools[2]，
 *      3)、然后从其头结点的下一个节点开始判断是否有足够的内存(头结点是不保存内存块的)，如果有则将申请到的内存块封装为一个ByteBuf对象返回；
 *
 *      4)、在初始状态时，tinySubpagePools数组元素都是空的，因而按照上述步骤将不会申请到对应的内存块，
 *           此时会将申请动作交由PoolChunkList进行。PoolArena首先会依次从qInit、q000、…、q100中申请内存，
 *           如果在某一个中申请到了，则将申请到的内存块封装为一个ByteBuf对象，并且将其返回；
 *      5)、初始时，每一个PoolChunkList都没有可用的PoolChunk对象，此时PoolArena会创建一个新的PoolChunk对象，每个PoolChunk对象维护的内存大小都是16M。
 *          然后内存申请动作就会交由PoolChunk进行，在PoolChunk申请到内存之后，PoolArena就会将创建的这个PoolChunk按照前面将的方式添加到qInit中，
 *          qInit会根据该PoolChunk已经使用的内存大小将其移动到对应使用率的PoolChunkList中；
 *      6)、关于PoolChunk申请内存的方式，这里需要说明的是，我们申请的是30byte内存，而PoolChunk内存申请最小值为8KB。
 *          因而这里在PoolChunk申请到8KB内存之后，PoolChunk会将其交由一个PoolSubpage进行维护，并且会设置该PoolSubpage维护的内存块大小为32byte，
 *          然后根据其维护的内存块大小，将其放到tinySubpagePools的对应位置，这里是tinySubpagePools[2]的PoolSubpage链表中。
 *          放到该链表之后，然后再在该PoolSubpage中申请目标内存扩容后的内存，也就是32byte，最后将申请到的内存封装为一个ByteBuf对象返回；
 *
 * 8、基本数据结构
 *      PoolSubpage：一个内存页，默认是8k
 *      PoolChunk：有多个PoolSubpage组成，默认包含2048个subpage，即默认大小是16m
 *      chunk内部包含一个byte数组memoryMap，默认包含4096个元素，memoryMap实际上是一棵完全二叉树，共有12层，也就是maxOrder默认是11（从0开始）
 *      ，所以这棵树总共有2048个叶子结点，每个叶子节点对应一个subpage，树中非叶子节点的内存大小由左子节点的内存大小加上右子节点的内存大小，
 *      memoryMap数组中存储的值是byte类型，其实就是该树节点在树中的深度（深度从0开始）。
 * 9、PoolSubpage表示一个内存页大小，还可以继续划分成更小的内存块，以便能充分利用每一个page。
 *      所以在分配内存的时候，如果分配的内存小于pageSIze（默认8k）大小，则会从PoolSubpage中分配；
 *      如果需要分配的内存大于pageSize且小于chunkSize（默认16m）的内存从chunk中分配
 *      如果大于chunkSize的内存则直接分配，Netty不做进一步管理。
 *
 * 10、由于netty通常应用于高并发系统，不可避免的有多线程进行同时内存分配，可能会极大的影响内存分配的效率，为了缓解线程竞争，
 *      可以通过创建多个poolArena细化锁的粒度，提高并发执行的效率。
 *
 * @param <T>
 */
abstract class PoolArena<T> implements PoolArenaMetric {
    /**
     *当前JVM平台是否存在sun.misc.Unsafe(可直接分配内存)是否可用，比如(IKVM.NET，Android等平台就不可用）。
     */
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    /**
     * 即不同大小的内存块，叫不同的名称，用于Chunk块中的是Normal，正好8k为1page，
     * 于是小于8k的内存块成为Tiny/Small，
     * 其中小于512B的为Tiny。同理，Chunk块存不下的内存块为Huge。
     *         enum SizeClass {
     *             Tiny,    // 16B, 32B,... 480B,496B(最大的内存块496Byte)
     *             Small,   // 512B,1KB,2KB,3KB,4KB（最大内存块8KB）
     *             Normal   // 8kB,16KB,...,8M,16M
     *             // 除此之外的请求为Huge,32M...64M
     *         }
     */
    enum SizeClass {
        Tiny,
        Small,
        Normal
    }
    // 该参数指定了tinySubpagePools数组的长度，由于tinySubpagePools每一个元素的内存块差值为16，
    // 因而数组长度是512/16 = 32 ，也即这里的512 >>> 4
    static final int numTinySubpagePools = 512 >>> 4;

    // 所属的PooledByteBufAllocator
    final PooledByteBufAllocator parent;

    // pool chunk是一个平衡相关满二叉树，此值设置了二叉树的高度,默认11
    private final int maxOrder;


    // pool chunk中每个叶子节点大小，即单个page的大小，默认8K
    final int pageSize;


    // 用于辅助计算：默认13
    // 指定了叶节点大小8KB是2的多少次幂，默认为13，该字段的主要作用是，在计算目标内存属于二叉树的
    // 第几层的时候，可以借助于其内存大小相对于pageShifts的差值，从而快速计算其所在层数
    final int pageShifts;

    // chunk的大小：默认16M(16777216 = 16 * 1024 * 1024)
    final int chunkSize;

    // 用于判断请求是否为Small/Tiny，即用于判断申请的内存大小与page之间的关系，是大于，还是小于.
    // 因为PoolSubpage 大小是 8KB=8196，而-8192=>=> 1111 1111 1111 1111 1110 0000 0000 0000
    // 可以通过与运算，如果结果是0，表明高位全部是0，则当前申请内存小于page。
    final int subpageOverflowMask;

    //用来分配small内存的数组长度：默认是4
    final int numSmallSubpagePools;
     /*
      * tinySubpagePools来缓存（或说是存储）用来分配tiny（小于512）内存的Page；
      * smallSubpagePools来缓存用来分配small（大于等于512且小于pageSize）内存的Page
      */
    final int directMemoryCacheAlignment;

    // 用于对齐内存
    final int directMemoryCacheAlignmentMask;
    /*
     * 第一部分内存池：tinySubpagePools,本质PoolSubpage数组(PoolSubpage本身是一个链表),默认长度32。
     * 初始状态，只有一个Head元素，没有链表。
     *  1、tinySubpagePools数组中主要是保存大小小于等于496byte的内存，其将0~496byte按照16个字节一个等级拆分成了31等，
     *     并且将其保存在了tinySubpagePools的1~31号位中。
     *
     *  2、由于PoolSubpage本身是一个链表，也就是说，在tinySubpagePools数组中，
     *     第1号位中存储的PoolSubpage维护的内存大小为16byte，
     *     第2号位中存储的PoolSubpage维护的内存大小为32byte，
     *     第3号位中存储的PoolSubpage维护的内存大小为48byte，
     *     依次类推，每次以16Byte为单位递增，当到第31号位中存储的PoolSubpage维护的内存大小为496byte。
     */
    private final PoolSubpage<T>[] tinySubpagePools;
    /*
     * 第二部分内存池：smallSubpagePools,本质PoolSubpage数组,默认长度为4，其维护的内存大小为496byte~8KB。
     *               初始状态每一个元素都是空的。
     *   1、smallSubpagePools中内存的划分则是按照2的指数次幂进行的，也就是说其每一个元素所维护的PoolSubpage的内存大小都是
     *      2的指数次幂：
     *      比如第0号位中存储的PoolSubpage维护的内存大小为512byte，
     *          第1号位为1024byte，
     *          第2号位为2048byte，
     *          第3号位为4096。
     *   需要注意的是，这里说的维护的内存大小指的是最大内存大小，比如申请的内存大小为5000 > 4096byte，
     *   那么PoolArena会将其扩展为8092，然后交由PoolChunk进行申请；
     */
    private final PoolSubpage<T>[] smallSubpagePools;
    /*
     * 第三部分内存池：一系列PoolChunkList,PoolChunkList本质是一个容器，其内部可以保存一系列的PoolChunk对象，
     *               并且，Netty会根据内存使用率的不同，将PoolChunkList分为不同等级的容器。
     *               初始时，PoolChunkList内部则没有保有任何的PoolChunk，是空的。
     *
     * 1、用来存储用来分配给Normal（超过一页）大小内存的PoolChunk，每个链表存放的是已经被分配过的chunk，不同使用率的chunk被存放在不同的链表中。
     * 2、ChunkList而且还有一个指向下一高等级使用率的PoolChunkList的指针，组成一个链qinit -> q000 <-> q025 <-> q050 <-> q075 <-> q100。
     *     PoolArena这么设计的原因在于，如果新建了一个PoolChunk，那么将其添加到PoolChunkList的时候，只需要将其添加到qInit中即可，
     *     其会根据当前PoolChunk的使用率将其依次往下传递，以保证将其归属到某个其使用率范围的PoolChunkList中；
     * 3、注意qinit和q000之间是单向，也就是说qinit的chunk可以move到q000，但是q000的chunk不能再向前move了。
     *
     * 4、内存分配：
     *  (1)PoolChunkList内部维护了一个PoolChunk的head指针，而PoolChunk本身就是一个单向链表，当有新的PoolChunk需要添加到当前PoolChunkList中时，
     *      其会将该PoolChunk添加到该链表的头部；
     *
     *  (2)PoolChunkList在维护PoolChunk时，还会对其进行移动操作，比如某个PoolChunk内存使用率为23%，当前正处于qInit中，
     *      在一次内存申请时，从该PoolChunk中申请了5%的内存，此时内存使用率达到了30%，已经不符合当前PoolChunkList(qInit->0~25%)的内存使用率了，
     *      此时，PoolChunkList就会将其交由其下一PoolChunkList进行处理，q000就会判断收到的PoolChunk使用率30%是符合当前PoolChunkList使用率定义的，
     *      因而会将其添加到当前PoolChunkList中。
     */

    // 使用率在50%-100%
    private final PoolChunkList<T> q050;
    // 使用率在25%-75%
    private final PoolChunkList<T> q025;
    // 使用率在1%-50%
    private final PoolChunkList<T> q000;
    // 使用率在0%-25%
    private final PoolChunkList<T> qInit;
    // 使用率在75%-100%
    private final PoolChunkList<T> q075;
    // 使用率100%
    private final PoolChunkList<T> q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    private long deallocationsTiny;
    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 1）初始化parent、pageSize、maxOrder、pageShifts等字段
     *
     * 2）实例化了如下两个数组，这两个数组相当重要，稍后将进行详细的介绍。
     *
     *     private final PoolSubpage<T>[] tinySubpagePools;
     *     private final PoolSubpage<T>[] smallSubpagePools;
     *
     * 3）创建了6个Chunk列表（PoolChunkList）来缓存用来分配给Normal（超过一页）大小内存的PoolChunk，每个PoolChunkList中用head字段维护一个PoolChunk链表的头部，每个PoolChunk中有prev,next字段。而PoolChunkList内部维护者一个PoolChunk链表头部。
     * 这6个PoolChunkList解释如下：
     * qInit：存储已使用内存在0-25%的chunk，即保存剩余内存在75%～100%的chunk
     * q000：存储已使用内存在1-50%的chunk
     * q025：存储已使用内存在25-75%的chunk
     * q050：存储已使用内存在50-100%个chunk
     * q075：存储已使用内存在75-100%个chunk
     * q100：存储已使用内存在100%chunk
     * 这六个PoolChunkList也通过链表串联，串联关系是：
     *  qInit->q000<->q025<->q050<->q075<->q100,且qInit.prevList = qInit
     *
     *  =>这样分配内存排序原因：随着chunk中page的不断分配和释放，会导致很多碎片内存段，大大增加了之后分配一段连续内存的失败率，
     *                  针对这种情况，可以把内存使用量较大的chunk放到PoolChunkList链表更后面，这样就便于内存的成功分配
     * @param parent
     * @param pageSize
     * @param maxOrder
     * @param pageShifts
     * @param chunkSize
     * @param cacheAlignment
     */
    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        //从PooledByteBufAllocator中传送过来的相关字段值。
        this.parent = parent;
        this.pageSize = pageSize;
        this.maxOrder = maxOrder;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        directMemoryCacheAlignment = cacheAlignment;
        directMemoryCacheAlignmentMask = cacheAlignment - 1;
        //该变量用于判断申请的内存大小与page之间的关系，是大于，还是小于
        subpageOverflowMask = ~(pageSize - 1);
        //构建tinySubpagePools的head元素（可以类比Map的key-value，当key冲突时就形成链表）
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);
        for (int i = 0; i < tinySubpagePools.length; i ++) {
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }
        // 构建smallSubpagePools，默认长度只有4，只有一个Head元素
        numSmallSubpagePools = pageShifts - 9;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }
        /*
         * 结果链：
         *  qInit->q000<->q025<->q050<->q075<->q100,且qInit.prevList = qInit
         */
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    /**
     * 构造tinySubpagePools和smallSubpagePools中的元素。此时数组中的每一个元素都只有一个Head节点，还没有形成链。
     * 可以类没有Map的节点中只保存了一个元素，而没有冲突。所以还没有形成链。一旦有同一hash值的Key存入，就会形成链。
     * @param pageSize
     * @return
     */
    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        // 这个构造函数是专门给Head使用的，poolSubpage其他变量均为null
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        // head形成回环可以判断这是一个空链
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    /**
     * ======>内存申请入口
     * @param cache
     * @param reqCapacity
     * @param maxCapacity
     * @return
     */
    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        // 此时，buf的readerIndex ,writerIndex和capacity都为0，还未初始化(未分配内存).
        // 同时真正的内存memory = null 以及 memoryAddress = 0
        // 所以此时还buf不能读写
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        // 使用对应的方式为创建的ByteBuf初始化相关内存数据，我们这里是以DirectArena进行讲解，因而这里
        // 是通过其allocate()方法申请内存
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    /**
     * 向右移4位即除以16。因为tiny块以16B大小为最小单位。所以当申请normCapacity 字节数据时，
     * 需要除以16得到需要tiny内存块数。即在tinyTable中的索引。
     * @param normCapacity
     * @return
     */
    static int tinyIdx(int normCapacity) {
        return normCapacity >>> 4;
    }

    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx ++;
        }
        return tableIdx;
    }

    // capacity < pageSize
    // subpageOverflowMask = ~(pageSize - 1);
    // 所以小于8k的是small或者tiny，结合tiny的范围，small的范围就是：512-8192
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    // 小于512的是tiny
    static boolean isTiny(int normCapacity) {
        //512 = 2的9次方。即0000 0000 0000 0000 0000 0010 0000 0000
        //      如果一个数与0xFFFFFE00 = 1111 1111 1111 1111 1111 1110 0000 0000相与为0，表示这个数高23位为0，即只有可能是低9位有
        // 不为0的值。而512即第9位为1.所以可以用此来判断这个数是不是比512大
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    /**
     * 分配内存入口
     *      1、首先会判断目标内存是在哪个内存层级的，比如tiny、small或者normal，
     *      2、然后根据目标层级的分配方式对目标内存进行扩容。
     *      3、接着首先会尝试从当前线程的缓存中申请目标内存，如果能够申请到，则直接返回，如果不能申请到，则在当前层级中申请。
     *      4、对于tiny和small层级的内存申请，如果无法申请到，则会将申请动作交由PoolChunkList进行。
     * @param cache
     * @param buf
     * @param reqCapacity
     */
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        //容量规整
        final int normCapacity = normalizeCapacity(reqCapacity);
        // 分支1 ： 小于pageSize(默认是8K)
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            int tableIdx;
            PoolSubpage<T>[] table;
            boolean tiny = isTiny(normCapacity);
            if (tiny) { // 小于512 < 512 (B,byte)
                // 这里首先从当前线程的缓存中尝试申请内存，如果申请到了，则直接返回，该方法中会使用申请到的
                // 内存对ByteBuf对象进行初始化
                // 使用缓存(此时还未分配内存,所以这个分支为false，继续执行下面流程)
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                //tinyTable每个位索引固定为16B大小，tableIdx是在tinyTable中索引位置。比如申请256B的内存，那么tableIdx  256/16= 16.
                tableIdx = tinyIdx(normCapacity);
                table = tinySubpagePools;
            } else {
                // 如果目标内存在512byte~8KB之间，则尝试从smallSubpagePools中申请内存。这里首先从
                // 当前线程的缓存中申请small级别的内存，如果申请到了，则直接返回
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                // 如果无法从当前线程的缓存中申请到small级别的内存，则尝试从smallSubpagePools中申请。
                // 这里smallIdx()方法就是计算目标内存块是在smallSubpagePools中的第几号元素中的
                tableIdx = smallIdx(normCapacity);
                //
                table = smallSubpagePools;
            }
            // 获取目标元素的头结点
            // 比如申请内存大小为256B,那么tableIdx = 16
            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             *   这里需要注意的是，由于对head进行了加锁，而在同步代码块中判断了s != head，
             *   也就是说PoolSubpage链表中是存在未使用的PoolSubpage的，因为如果该节点已经用完了，
             *   其是会被移除当前链表的。也就是说只要s != head，那么这里的allocate()方法就一定能够申请到所需要的内存块
             */
            synchronized (head) {
                final PoolSubpage<T> s = head.next;
                // 因为 table[tableIdx]中的元素初始化只有一个节点，还没有形成链。并且这个节点自身形成循环引用，所以此时head = head.next
                // s != head就证明当前PoolSubpage链表中存在可用的PoolSubpage，并且一定能够申请到内存，
                // 因为已经耗尽的PoolSubpage是会从链表中移除的
                if (s != head) {//第一次：false 如果第一次分配之后没有回收，那么后续为True
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    // 从PoolSubpage中申请内存,这里为什么一定可以找到可用的内存块（handle>=0）呢？
                    // 因为在io.netty.buffer.PoolSubpage#allocate的时候，如果可用内存块为0了会将该page从链表中remove，所以保证了head.next一定有可用的内存
                    long handle = s.allocate();
                    assert handle >= 0;
                    // 通过申请的内存对ByteBuf进行初始化
                    s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity);
                    // 对tiny类型的申请数进行更新
                    incTinySmallAllocation(tiny);
                    return;
                }
            }
            synchronized (this) {
                // 走到这里，说明目标PoolSubpage链表中无法申请到目标内存块，因而就尝试从PoolChunk中申请
                allocateNormal(buf, reqCapacity, normCapacity);
            }
            // 对tiny类型的申请数进行更新
            incTinySmallAllocation(tiny);
            return;
        }
        // 分支2：chunk内存分配,说明目标内存是大于8KB的，那么就判断目标内存是否大于16M，如果大于16M，
        //       则不使用内存池对其进行管理，如果小于16M，则到PoolChunkList中进行内存申请
        if (normCapacity <= chunkSize) {
            // 小于16M，首先到当前线程的缓存中申请，如果申请到了则直接返回，如果没有申请到，
            // 则到PoolChunkList中进行申请
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            synchronized (this) {
                // 在当前线程的缓存中无法申请到足够的内存，因而尝试到PoolChunkList中申请内存
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }
        }
        // 分支3:分配Huge内存：huge内存之外，其他内存申请都可能会调用到allocateNormal
        else {
            // 对于大于16M的内存，Netty不会对其进行维护，而是直接申请，然后返回给用户使用
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, reqCapacity);
        }
    }

    /**
     * Method must be called inside synchronized(this) { ... } block
     * 正常的分配内存:
     *      1、首先是按照一定的顺序分别在各个PoolChunkList中申请内存，如果申请到了，则直接返回，
     *      如果没申请到，则创建一个PoolChunk进行申请。
     *      2、这里需要说明的是，在PoolChunkList中申请内存时，本质上还是将申请动作交由其内部的PoolChunk进行申请，如果申请到了，
     *          其还会判断当前PoolChunk的内存使用率是否超过了当前PoolChunkList的阈值，
     *          如果超过了，则会将其移动到下一PoolChunkList中。
     */
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        // 内存池中获取需要的内存：
        // 将申请动作按照q050->q025->q000->qInit->q075的顺序依次交由各个PoolChunkList进行处理，
        // 如果在对应的PoolChunkList中申请到了内存，则直接返回
        if (q050.allocate(buf, reqCapacity, normCapacity)
                || q025.allocate(buf, reqCapacity, normCapacity)
                || q000.allocate(buf, reqCapacity, normCapacity)
                || qInit.allocate(buf, reqCapacity, normCapacity)
                || q075.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }
        // 第一次上面都为空，没有初始化，所以会进入到这里：
        // 由于在目标PoolChunkList中无法申请到内存，因而这里直接创建一个PoolChunk，
        // 然后在该PoolChunk中申请目标内存，最后将该PoolChunk添加到qInit中
        // Add a new chunk.
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        // 用申请的内存初始化buffer
        boolean success = c.allocate(buf, reqCapacity, normCapacity);
        assert success;
        // 刚刚初始化的chunk放在init链表中
        qInit.add(c);
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            allocationsTiny.increment();
        } else {
            allocationsSmall.increment();
        }
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    /**
     * 释放内存：
     * oolArena主要是分为两种情况，即池化和非池化，如果是非池化，则会直接销毁目标内存块，
     * 如果是池化的，则会将其添加到当前线程的缓存中
     * @param chunk
     * @param nioBuffer
     * @param handle
     * @param normCapacity
     * @param cache
     */
    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) {
            // 如果是非池化的，则直接销毁目标内存块，并且更新相关的数据
            int size = chunk.chunkSize();
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            // 如果是池化的，首先判断其是哪种类型的，即tiny，small或者normal，
            // 然后将其交由当前线程的缓存进行处理，如果添加成功，则直接返回
            SizeClass sizeClass = sizeClass(normCapacity);
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }
            // 如果当前线程的缓存已满，则将目标内存块返还给公共内存块进行处理
            freeChunk(chunk, handle, sizeClass, nioBuffer, false);
        }
    }

    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass, ByteBuffer nioBuffer, boolean finalizer) {
        final boolean destroyChunk;
        synchronized (this) {
            // We only call this if freeChunk is not called because of the PoolThreadCache finalizer as otherwise this
            // may fail due lazy class-loading in for example tomcat.
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    case Tiny:
                        ++deallocationsTiny;
                        break;
                    default:
                        throw new Error();
                }
            }
            destroyChunk = !chunk.parent.free(chunk, handle, nioBuffer);
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx;
        PoolSubpage<T>[] table;
        if (isTiny(elemSize)) { // < 512
            tableIdx = elemSize >>> 4;
            table = tinySubpagePools;
        } else {
            tableIdx = 0;
            elemSize >>>= 10;
            while (elemSize != 0) {
                elemSize >>>= 1;
                tableIdx ++;
            }
            table = smallSubpagePools;
        }

        return table[tableIdx];
    }

    /**
     * 申请内存大小的规整。Netty并不是申请多少就分配多少，会根据一定的规则分配大于等于需要内存的规整过的值(规范化申请的内存大小为2的指数次)。
     * 此方法就是具体的规范过程。具体规范规则如下：
     *   1. 如果目标容量小于16字节，则返回16；
     *   2. 如果目标容量大于16字节，小于512字节，则以16字节为单位，返回大于目标字节数的第一个16字节的倍数。
     *      比如申请的100字节，那么大于100的16的倍数是112，因而返回112个字节
     *   3. 如果目标容量大于512字节，则返回大于目标容量的第一个2的指数幂。
     *      比如申请的1000字节，那么返回的将是1024
     * @param reqCapacity
     * @return
     */
    // 对容量进行规范化:保证申请的内存大小为2的指数次方
    // io.netty.buffer.PoolArena#normalizeCapacity
    int normalizeCapacity(int reqCapacity) {
        checkPositiveOrZero(reqCapacity, "reqCapacity");
        // Huge 直接返回（直接内存需要对齐） 16777216
        if (reqCapacity >= chunkSize) {
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }
        // Small和Normal 规范化到大于2的n次方的最小值
        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled
            // 如果申请的内存大于512则规范化为大于reqCapacity的最近的2的指数次的值
            int normalizedCapacity = reqCapacity;
            // 当normalizedCapacity本身就是2的指数次的时候，取其本身
            // 这个时候防止下面的算法再向后查找，先将normalizedCapacity-1
            /*具体的位移过程
             if (!isTiny(reqCapacity))里面的位运算，由于要寻找的数是2的指数次，所以二进制表示除了最高位是1，后面的位都应该是0，假设寻找的是x ，
                x-1的二进制所有位都是1，所以变成了寻找比x少一位的二进制全1的数
                normalizedCapacity二进制表示的第一位肯定是1，右移1位之后，第二位变为了1，两者进行逻辑或的时候，前两位一定是1同理
                    继续右移2位之后，前4位肯定是1
                    继续右移4位之后，前8位肯定是1
                    继续右移8位之后，前16位肯定是1
                    继续右移16位之后，前32位一定是1
                这样就找到了全是1的数，然后再加上1就是2的指数次。
                总结上面的代码逻辑，规范化的过程是：
                    如果是huge，大于chunkSize直接返回reqCapacity
                    如果是small或者normal，大于512，则规范化为大于reqCapacity的最近的2的指数次的值
                    如果是tiny，小于512，则规范为16的倍数
                上面这个规范化的原因和每类内存申请的数据结构有密切的关系，我们这里先只关心normal类型的被规范化为2的指数次。
             *
             */
            normalizedCapacity --;
            normalizedCapacity |= normalizedCapacity >>>  1;
            normalizedCapacity |= normalizedCapacity >>>  2;
            normalizedCapacity |= normalizedCapacity >>>  4;
            normalizedCapacity |= normalizedCapacity >>>  8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity ++;
            // 如果上面的计算结果溢出了(如果reqCapacity是Integer.MAX_VALUE)，则去掉最高位
            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;

            return normalizedCapacity;
        }
        // directMemoryCacheAlignment = 0,默认第一次此处为false
        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);
        }

        // Quantum-spaced
        // 下面之所以是16的倍数是因为用来管理tiny内存tinySubpagePools数组的大小刚好是512>>>4，32个元素
        // 每个元素PoolSubpage本身会构成链表，也就是说每个元素（PoolSubpage）对应的链表内每个元素的内存块大小（elemSize）是相同的，数组内每个链表的elemSize依次是：
        // 16,32,48......480，496，512
        // 刚好是16的倍数(这个时候reqCapacity<512)
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }
        // 找到距离reqCapacity最近的下一个16的倍数
        return (reqCapacity & ~15) + 16;
    }

    /**
     * 对齐容量：TODO 位运算
     * @param reqCapacity
     * @return
     */
    int alignCapacity(int reqCapacity) {
        int delta = reqCapacity & directMemoryCacheAlignmentMask;
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;
        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        allocate(parent.threadCache(), buf, newCapacity);
        if (newCapacity > oldCapacity) {
            memoryCopy(
                    oldMemory, oldOffset,
                    buf.memory, buf.offset, oldCapacity);
        } else if (newCapacity < oldCapacity) {
            if (readerIndex < newCapacity) {
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
            } else {
                readerIndex = writerIndex = newCapacity;
            }
        }

        buf.setIndex(readerIndex, writerIndex);

        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
           .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolSubPages(tinySubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        // mark as package-private, only for unit test
        int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            int remainder = HAS_UNSAFE
                    ? (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask)
                    : 0;

            // offset = alignment - address & (alignment - 1)
            return directMemoryCacheAlignment - remainder;
        }

        /**
         * 创建一个Chunk块,大小为
         * @param pageSize
         * @param maxOrder
         * @param pageShifts
         * @param chunkSize
         * @return
         */
        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder,
                int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {// 默认true
                return new PoolChunk<ByteBuffer>(
                        this,//当前Chunky所属的PoolArena
                        allocateDirect(chunkSize)//通过sun.misc.Unsafe类生成真正的JDK ByteBuffer类型。用来保存数据
                        ,pageSize   // 8k = 8192
                        ,maxOrder   // 11
                        ,pageShifts // 13
                        ,chunkSize  //16M = 16777216
                        ,0);
            }
            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize,
                    maxOrder, pageShifts, chunkSize,
                    offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        /**
         * 分配直接内存：真正的内存，JDK层面或者系统层面的直接内存。
         * @param capacity
         * @return
         */
        private static ByteBuffer allocateDirect(int capacity) {
            // windows默认为true
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        /**
         * 创建PooledByteBuf实现例，里面泛型是JDK中的ByteBuffer
         * @param maxCapacity
         * @return
         */
        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {// 平台支持UNSAFE:正常windows和linux中的JDk都支持sun.misc.Unsafe。
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
