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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 *
 * 1、Netty中内存管理的数据一块，一个Chunk块由多个Page(PoolSubpage)组成
 * 2、Chunk主要用来组织和管理多个Page的内存分配和释放。在Netty中，Chunk中的Page被构建成一颗二叉树。
 *  PoolChunk默认申请的内存大小是16M，在结构上，其会将这16M内存组织成为一颗平衡二叉树，二叉树的每一层每个节点所代表的内存大小都是均等的，
 *  并且每一层节点所代表的内存大小总和加起来都是16M，整颗二叉树的总层数为12，层号从0开始。
 *---------------------------------------------------------------------------------------------
 *  depth map     nodenum                 memeryMap(存储内存使用情况)
 *    0              1       |                        16M-0                           |
 *    1              2       |            8M-1          |            8M-1             |
 *    2              4       |     4M-2    |     4M-2   |     4M-2     |       4M-2   |
 *    3              8       | 2M-3 | 2M-3 | 2M-3 | 2M-3| 2M-3  | 2M-3 | 2M-3 | 2M-3  |
 *                                  .....
 *    11           2048      |8k-11|8k-11|8k-11|        ... ...     |8k-11|8k-11|8k-11|
 *
 *    ---图中的16M-0,8M-1的0和1表示当前节点存储的值
 *---------------------------------------------------------------------------------------------
 * 1)一个PoolChunk占用的总内存是16M，其会按照当前二叉树所在的层级将16M内存进行划分，比如第1层将其划分为两个8M，第二层将其划分为4个4M等等，
 *    整颗二叉树最多有12层，因而每个叶节点的内存大小为8KB，也就是说，PoolChunk能够分配的内存最少为8KB，最多为16M；
 * 2)上图中的二叉树叶子节点(没有子节点的节点，即最底层)有2^11=2048个，因而整颗树的节点数有4095个。PoolChunk将这4095个节点平铺到了一个长度为4096的数组上，
 * 其第1号位存储了0，第2~3号位存储了1，第4~7号位存储了2，依次类推，整体上其实就是将这棵以层号表示的二叉树存入了一个数组中。
 * 3) 这里的数组就是左边的depthMap，通过这棵二叉树，可以快速通过下标得到其层数，比如2048号位置的值为11表示其在二叉树的第11层。
 * 4) depthMap的结构（数组）如
 *   |0|0|1|1|2|2|2|2|3|3|3|3|3|3|3|3|... ... |11|11|11|
 *
 *   a)depthMap长度为4096个，比4095个节点多一个，用第1位0表示这个多出的节点。
 *   b)第2个0表示第1层，只有一个节点，第2和和3位的1，表示在第1层的2个节点。依此类推，最后的11表示第n位的节点属于第11层。
 *   c)图中二叉树的每个节点上，我们为当前节点所代表的内存大小标记了一个数字，这个数字其实就表示了当前节点所能够分配的内存大小，
 *     比如0代表16M，1代表了8M等等。这些数字就是由memoryMap来存储的，表示二叉树中每个节点代表的可分配内存大小，
 *     其数据结构与depthMap完全一样。
 *     图中，每一个父节点所代表的可分配内存大小都等于两个子节点的和，如果某个子节点的内存已经被分配了，那么该节点就会被标记为12，
 *     表示已分配，而它们的父节点则会被更新为另一个子节点的值，表示父节点可分配的内存就是其两个子节点所能提供的内存之和；
 *
 * 5、内存分配流程：
 *      对于PoolChunk对内存的申请和释放的整体流程，我们以申请的是9KB的内存进行讲述：
 *     1、首先将9KB=9126拓展为大于其的第一个2的指数，也就是2<<13=16384，由于叶节点8KB=2 << 12，其对应的层数为11，
 *        因而16384所在的层数为10，也就是说从内存池中找到一个第10层的未分配的节点即可；
 *     2、得出目标节点在第10层后，这里就会从头结点开始比较，如果头结点所存储的值比10要小，
 *        那么就说明其有足够的内存用于分配目标内存，然后就会将其左子节点与10进行比较，
 *        如果左子节点比10要大(一般此时左子节点已经被分配出去了，其值为12，因而会比10大)，
 *        那么就会将右子节点与10进行比较，此时右子节点肯定比10小，那么就会从右子节点的左子节点开始继续上面的比较；
 *        当比较到某一个时刻，有一个节点的数字与10相等，就说明这个位置就是我们所需要的内存块，那么就会将其标注为12，
 *        然后递归的回溯，将其父节点所代表的内存值更新为其另一个子节点的值；
 *    3、关于内存的分配，这里需要说明的最后一个问题就是，通过上面的计算方式，我们可以找到一个节点作为我们目标要分配的节点，
 *      此时就需要将此节点所代表的内存起始地址和长度返回。由于我们只有整个PoolChunk所申请的16M内存的地址值，
 *      而通过目标节点所在的层号和其是该层第几个节点就可以计算出该节点相对于整个内存块起始地址的偏移量，
 *      从而就可以得到该节点的起始地址值；关于该节点所占用的内存长度，直观的感觉可以理解为一个映射，比如11代表8KB长度，
 *      10代表16KB长度等等。当然这里的起始地址和偏移量的计算，PoolChunk并不是通过这种算法直接实现的，
 *      而是通过更高效的位运算来实现的。
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;
    /**
     * 表示该PoolChunk所属的PoolArena(netty内存池总的数据结构)
     */
    final PoolArena<T> arena;
    /**
     * 真正保存数据的地方：
     * 当前申请的内存块，比如对于堆内存，T就是一个byte[]数组，对于直接内存，T就是ByteBuffer(JDK)，
     * 但无论是哪种形式，其内存大小都默认是16M。
     */
    final T memory;
    /**
     * 是否是可重用的，unpooled=false表示可重用
     */
    final boolean unpooled;
    /**
     *  表示当前申请的内存块中有多大一部分是用于站位使用的，整个内存块的大小是16M+offset，默认该值为0
     */
    final int offset;
    /**
     * 存储了当前代表内存池的二叉树的各个节点的内存使用情况，该数组长度为4096，二叉树的头结点在该数组的
     * 第1号位，存储的值为0；两个一级子节点在该数组的第2号位和3号位，存储的值为1，依次类推。二叉树的叶节点
     * 个数为2048，因而总节点数为4095。在进行内存分配时，会从头结点开始比较，然后比较左子节点，然后比较右
     *  子节点，直到找到能够代表目标内存块的节点。
     *  当某个节点所代表的内存被申请之后，该节点的值就会被标记为12, 表示该节点已经被占用。
     */
    private final byte[] memoryMap;

    /**
     *  这里depthMap存储的数据结构与memoryMap是完全一样的，只不过其值在初始化之后一直不会发生变化。
     *  该数据的主要作用在于通过目标索引位置值找到其在整棵树中对应的层数。
     */
    private final byte[] depthMap;
    /**
     * 这里每一个PoolSubPage代表了二叉树的一个叶节点(最底层的具体的内存分配，即memeryMap中的第11层，共2048个节点)，
     * 也就是说，当二叉树叶节点内存被分配之后，
     * 其会使用一个PoolSubPage对其进行封装。
     */
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    // 其值为-8192，二进制表示为11111111111111111110000000000000，它的后面0的个数正好为12，而2^12=8192，
    // 因而将其与用户希望申请的内存大小进行“与操作“，如果其值不为0，就表示用户希望申请的内存在8192之上，从而
    // 就可以快速判断其是在通过PoolSubPage的方式进行申请还是通过内存计算的方式。
    private final int subpageOverflowMask;
    //每个PoolSubpage的大小，默认为8192个字节（8K)
    private final int pageSize;
    // 页节点所代表的偏移量，默认为13，主要作用是计算目标内存在内存池中是在哪个层中，具体的计算公式为：
    // int d = maxOrder - (log2(normCapacity) - pageShifts);
    // 比如9KB，经过log2(9KB)得到14，maxOrder为11，计算就得到10，表示9KB内存在内存池中为第10层的数据
    private final int pageShifts;
    //maxOrder = 11,即根据maxSubpageAllocs = 1 << maxOrder可得一个PoolChunk默认情况下由2^11=2048个SubPage构成，
    // 而默认情况下一个page默认大小为8k，即pageSize=8K。
    private final int maxOrder;
    // 记录了当前整个PoolChunk申请的内存大小，默认为16M
    private final int chunkSize;
    // 将chunkSize取2的对数，默认为24
    private final int log2ChunkSize;
    // 指定了代表叶节点的PoolSubPage数组所需要初始化的长度
    private final int maxSubpageAllocs;

    /** Used to mark memory as unusable */
    // 指定了某个节点如果已经被申请，那么其值将被标记为unusable所指定的值
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    // 对创建的ByteBuffer进行缓存的一个队列
    private final Deque<ByteBuffer> cachedNioBuffers;
    // 剩余可分配内存：记录了当前PoolChunk中还剩余的可申请的字节数
    private int freeBytes;
    /**
     * 一个PoolChunk内存默认大小为16M,太小了，所以需要将PoolChunk构建链表形式。
     */
    //一个PoolChunk分配后，会根据使用率挂在PoolArena的一个PoolChunkList中
    PoolChunkList<T> parent;
    // PoolChunk本身设计为一个链表结构，在PoolChunkList中当前PoolChunk的前置节点
    PoolChunk<T> prev;
    // 在PoolChunkList中当前PoolChunk的后置节点
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * PoolChunk构造函数初始化： PoolChunk会涉及到具体的内存,泛型T表示byte[](堆内存)、或java.nio.ByteBuffer(堆外内存)
     * @param arena
     * @param memory
     * @param pageSize
     * @param maxOrder
     * @param pageShifts
     * @param chunkSize
     * @param offset
     */
    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        // memoryMap初始化：
//        memoryMap数组中每个位置保存的是该节点所在的层数，有什么作用？对于节点512，其层数是9，则：
//        1、如果memoryMap[512] = 9，则表示其本身到下面所有的子节点都可以被分配；
//        2、如果memoryMap[512] = 10， 则表示节点512下有子节点已经分配过，则该节点不能直接被分配，而其子节点中的第10层还存在未分配的节点;
//        3、如果memoryMap[512] = 12 (即总层数 + 1）, 可分配的深度已经大于总层数, 则表示该节点下的所有子节点都已经被分配。

        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        subpages = newSubpageArray(maxSubpageAllocs);
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    /**
     * 申请一段内存(位图索引):
     *  1、对于内存的分配，主要会判断其是否大于8KB，如果大于8KB，则会直接在PoolChunk的二叉树中年进行分配，
     *     如果小于8KB，则会直接申请一个8KB的内存，然后将8KB的内存交由一个PoolSubpage进行维护。
     *  2、 在PoolSubpage中，其会将整个内存块大小切分为一系列的16字节大小，这里就是8KB，
     *      也就是说，它将被切分为512 = 8KB / 16byte份。 为了标识这每一份是否被占用，PoolSubpage使用了一个long型数组来表示，
     *      该数组的名称为bitmap，因而我们称其为位图数组。
     *      为了表示512份数据是否被占用，而一个long只有64个字节，因而这里就需要8 = 512 / 64个long来表示，
     *      因而这里使用的的是long型数组，而不是单独的一个long字段。
     *      但是我们注意，这里的handle高32位是一个整型值，而我们描述的bitmap是一个long型数组，
     *      那么一个整型是如何表示当前申请的内存是bitmap数组中的第几号元素，以及该元素中整型64字节中的第几位的。
     *
     *      这里其实就是通过整型的低9位来表示的，第7~9位用来表示当前是占用的bitmap数组中的第几号long型元素，
     *      而1~6位则用来表示该long型元素的第多少位是当前申请占用的。
     *      因而这里只需要一个长整型的handle即可表示当前申请到的内存在整个内存池中的位置，以及在PoolSubpage中的位置。
     * @param buf
     * @param reqCapacity
     * @param normCapacity
     * @return
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        final long handle;
        // 这里subpageOverflowMask=-8192，通过判断的结果可以看出目标容量是否小于8KB。
        // 在下面的两个分支逻辑中，都会返回一个long型的handle，一个long占8个字节，其由低位的4个字节和高位的
        // 4个字节组成，低位的4个字节表示当前normCapacity分配的内存在PoolChunk中所分配的节点在整个memoryMap
        // 数组中的下标索引；而高位的4个字节则表示当前需要分配的内存在PoolSubPage所代表的8KB内存中的位图索引。
        // 对于大于8KB的内存分配，由于其不会使用PoolSubPage来存储目标内存，因而高位四个字节的位图索引为0，
        // 而低位的4个字节则还是表示目标内存节点在memoryMap中的位置索引；
        // 对于低于8KB的内存分配，其会使用一个PoolSubPage来表示整个8KB内存，因而需要一个位图索引来表示目标内存
        // 也即normCapacity会占用PoolSubPage中的哪一部分的内存。
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize //默认为false
            // 申请高于8KB的内存
            handle =  allocateRun(normCapacity);
        } else {
            // 申请低于8KB的内存
            // =====> 核心方法，此处最终会生成PoolSubpage，并且调用page.addToPool()方法将生成的Page实例加入到tinySubpagePools或smallSubpagePools数组中
            handle = allocateSubpage(normCapacity);
        }
        // 如果返回的handle小于0，则表示要申请的内存大小超过了当前PoolChunk所能够申请的最大大小，也即16M，
        // 因而返回false，外部代码则会直接申请目标内存，而不由当前PoolChunk处理
        if (handle < 0) {
            return false;
        }
        // 这里会从缓存的ByteBuf对象池中获取一个ByteBuf对象，不存在则返回null
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        // 通过申请到的内存数据对获取到的ByteBuf对象进行初始化，如果ByteBuf为null，则创建一个新的然后进行初始化
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     * 在memeryMap平衡树中，当一个子节点分配了内存之后，需要更新父节点的可用内存
     * @param id id ： 以分配256B为例，第1次分配id=2048，即第11层的第1个节点
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1;//除以2，找到上一层地节点值,第1次1024
            byte val1 = value(id);//val1 = 12（已经分配出去了，不可用了）
            byte val2 = value(id ^ 1);// 2048 ^ 1 = 2049, val2 = 11
            byte val = val1 < val2 ? val1 : val2;// val1 < val2 = false ,所以此处为val = val2 = 11
            setValue(parentId, val);
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * 实现在二叉树中进行节点匹配,主要逻辑就是查找目标内存在memoryMap中的索引下标值，并且对所申请的节点的父节点值进行更新：
     * 1、从根节点开始遍历，如果当前节点的val<d，则通过id <<=1匹配下一层；
     * 2、如果val > d，则表示存在子节点被分配的情况，而且剩余节点的内存大小不够，此时需要在兄弟节点上继续查找；
     * 3、分配成功的节点需要标记为不可用，防止被再次分配，在memoryMap对应位置更新为12；
     * 4、分配节点完成后，其父节点的状态也需要更新，并可能引起更上一层父节点的更新，实现如下：
     * @param d depth ：默认是11
     * @return index in memoryMap
     */
    private int allocateNode(int d) {
        int id = 1;
        // 1 << d = 2048， -2048 = 0b11111111_11111111_11111000_00000000
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1。
        // 获取memoryMap中索引为id的位置的数据层数，初始时获取的就是根节点的层数
        byte val = value(id);
        // 如果根节点的层数值都比d要大，说明当前PoolChunk中没有足够的内存用于分配目标内存，直接返回-1
        if (val > d) { // unusable
            return -1;
        }
        // 这里就是通过比较当前节点的值是否比目标节点的值要小，如果要小，则说明当前节点所代表的子树是能够
        // 分配目标内存大小的，则会继续遍历其左子节点，然后遍历右子节点。
        /*
         * id 表示memerymap中4095个节点序号。
         * val 表示memeryMap中具体节点的值。此值代表当前节点可以分配的内存数。
         * val = 1 ,表示当前层每个节点可以分配16M(默认的顶层根节点)
         * val = 2,表示当前层每个节点可以分配8M(第2层)
         * ...
         * val = 11,表示当前层每个节点可以分配8K(第11层)。因为一共16M,第11层共有2048个节点，所以每个节点8K。
         *，
         * 以分配256B为例，循环前，id = 1,val = 0 (第1层，根节点)
         *   第1次循环：
         *      id = 2,
         *      val = 1 表示在第1层节点(根节点)，可以分配16M,太大，继续循环
         *
         *      第2次循环
         *      id = 4
         *      val = 2 ，表示每个节点可以分配8M,太大，继续循环
         *      第3次循环
         *      id =8
         *      val = 3 ，表示每个节点可以分配4M,太大，继续循环
         *      ....
         *
         *      直到id = 2048 = 2的11次方
         *      val = 11,还是大于256B
         *
         *      此时val > d 为false 继续循环，但while相关条件为false，所以会循环了。
         *      即最张val = 11 (8K大小)
         *            id = 2048
         *
         *
         */
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1; //因为是一颗平衡树，所以最左边的节点序号始终为2的n次方，通过左移实现这个算法逐渐的一层一层的往下移。
            val = value(id);// id = 4,所以value(id) = 2。即2层
            // 这里val > d其实就是表示当前节点的数值比目标数值要大，也就是说当前节点是没法申请到目标容量的内存，
            // 那么就会执行 id ^= 1，其实也就是将id切换到当前节点的兄弟节点，本质上其实就是从二叉树的

            // 左子节点开始查找，如果左子节点无法分配目标大小的内存，那么就到右子节点进行查找
            if (val > d) {
                id ^= 1;
                val = value(id);
            }
            //如果val < d表示有足够的memeryMap平衡树当前层有足够的内存进行分配。所以去下一层继续分配内存。

        }
        // 当找到之后，获取该节点所在的层数
        // 以分配 256B为例 ，最小id =2048,value = 11
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        // 将该memoryMap中该节点位置的值设置为unusable=12，表示其已经被占用
        setValue(id, unusable); // mark as unusable
        // 递归的更新父节点的值，使其继续保持"父节点存储的层数所代表的内存大小是未分配的
        // 子节点的层数所代表的内存之和"的语义。
        updateParentsAlloc(id);
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     * 首先会计算目标内存所对应的二叉树层数，然后递归的在二叉树中查找是否有对应的节点，找到了则直接返回:
     *      1、normCapacity是处理过的值，如申请大小为1000的内存，实际申请的内存大小为1024。
     *      2、d = maxOrder - (log2(normCapacity) - pageShifts) 可以确定需要在二叉树的d层开始节点匹配。
     *          其中pageShifts默认值为13，为何是13？因为只有当申请内存大小2^13（8192）时才会使用方法allocateRun分配内存。
     *      3、方法allocateNode实现在二叉树中进行节点匹配
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        // 这里maxOrder为11，表示整棵树最大的层数，log2(normCapacity)会将申请的目标内存大小转换为大于该大小的
        // 第一个2的指数次幂数然后取2的对数的形式，比如log2(9KB)转换之后为14，这是因为大于9KB的第一个2的指数
        // 次幂为16384，将其取2的对数后为14。pageShifts默认为13，这里整个表达式的目的就是快速计算出申请目标
        // 内存（normCapacity）需要对应的层数。
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        // 通过前面讲的递归方式从先父节点，然后左子节点，接着右子节点的方式依次判断其是否与目标层数相等，
        // 如果相等，则会将该节点所对应的在memoryMap数组中的位置索引返回
        int id = allocateNode(d);
        // 如果返回值小于0，则说明在当前PoolChunk中无法分配目标大小的内存，这一般是由于目标内存大于16M，
        // 或者当前PoolChunk已经分配了过多的内存，剩余可分配的内存不足以分配目标内存大小导致的
        if (id < 0) {
            return id;
        }
        // 更新剩余可分配内存的值
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     * allocateSubpage()方法主要是将申请到的8KB内存交由一个PoolSubpage进行管理，并且由其返回响应的位图索引
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        // 这里其实也是与PoolThreadCache中存储PoolSubpage的方式相同，也是采用分层的方式进行存储的，
        // 具体是取目标数组中哪一个元素的PoolSubpage则是根据目标容量normCapacity来进行的。
        // ========>核心方法： 通过调用 arena.findSubpagePoolHead(elemSize)来完成具体的head寻址，后续创建的PoolSubpage就会添加到这个head所在的链表中
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        synchronized (head) {
            // 这里调用allocateNode()方法在二叉树中查找时，传入的d值maxOrder=11，也就是说，其本身就是
            // 直接在叶节点上查找可用的叶节点位置
            int id = allocateNode(d);
            // 小于0说明没有符合条件的内存块
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;// 8KB = 8192
            // 剩余空闲内存需要减去刚分配出去的内存pageSize
            freeBytes -= pageSize;
            // 计算当前id对应的PoolSubpage数组中的位置: subpageIdx(2048 = 0)
            int subpageIdx = subpageIdx(id);
            // 以第1次分配256B为例，subpageIdx = 0
            PoolSubpage<T> subpage = subpages[subpageIdx];
            // 这里主要是通过一个PoolSubpage对申请到的内存块进行管理<第一次没有初始化，所以为null>
            if (subpage == null) {
                //======>生成一个新的PoolSubpage，并且会在初始化时调用page.addToPool()方法将此Page实例加入到tinySubpagePools或smallSubpagePools数组中
                // 这里runOffset()方法会返回该id在PoolChunk中维护的字节数组中的偏移量位置，
                // normCapacity则记录了当前将要申请的内存大小；
                // pageSize记录了每个页的大小，默认为8KB
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity);
            }
            // 通过PoolSubpage申请一块内存，并且返回代表该内存块的位图索引，位图索引的具体计算方式，
            // 我们前面已经简要讲述，详细的实现原理我们后面会进行讲解。
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     * 内存的申请就是在主内存块中查找可以申请的内存块，然后将代表其位置的比如层号，或者位图索引标志为已经分配。
     * 所以释放过程其实就是返回来，然后将这些标志进行重置
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        // 根据当前内存块在memoryMap数组中的位置
        int memoryMapIdx = memoryMapIdx(handle);
        // 获取当前内存块的位图索引
        int bitmapIdx = bitmapIdx(handle);
        // 如果位图索引不等于0，说明当前内存块是小于8KB的内存块，因而将其释放过程交由PoolSubpage进行
        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                // 由PoolSubpage释放内存
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        // 走到这里说明需要释放的内存大小大于8KB，这里首先计算要释放的内存块的大小
        freeBytes += runLength(memoryMapIdx);
        // 将要释放的内存块所对应的二叉树的节点对应的值进行重置
        setValue(memoryMapIdx, depth(memoryMapIdx));
        // 将要释放的内存块所对应的二叉树的各级父节点的值进行更新
        updateParentsFree(memoryMapIdx);

        // 将创建的ByteBuf对象释放到缓存池中，以便下次申请时复用
        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    /**
     * 对ByteBuf进行初始化
     * @param buf
     * @param nioBuffer
     * @param handle
     * @param reqCapacity
     */
    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);// 第1次分配256B时，memoryMapIdx = 2048,即第11层的第1个节点
        int bitmapIdx = bitmapIdx(handle);
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }
    /** 初始化ByteBuf */
    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;
        // 初始化ByteBuf
        buf.init(
            this, nioBuffer, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    /**
     * 获取当前平衡树的节点值。用来判断是否有可以分配的内存。
     * @param id
     * @return
     */
    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    /**
     * 取long的低32位
     * @param handle
     * @return
     */
    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    /**
     * 取long的高32位
     * @param handle
     * @return
     */
    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
