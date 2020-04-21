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

/**
 * Netty内存管理的最小单元,多个Page构成一个Chunk。
 * Page默认大小为8KB。
 * Netty内存池中，内存大小在8KB~16M的内存是由PoolChunk维护的，小于8KB的内存则是由PoolSubpage来维护的。
 * 而对于低于8KB的内存，Netty也是将其分成了两种情况0~496byte和512byte~8KB。
 * 其中，0~496byte的内存是由一个名称为tinySubpagePools的PoolSubpage的数组维护的，512byte~8KB的内存
 * 则是由名称为smallSubpagePools的PoolSubpage数组来维护的
 *
 * 注意：PoolSubpage没有具体的内存块,仅仅是对内存块管理的抽象。具体的内存块在高层PoolChunk中抽象“final T memory;”。
 * @param <T>
 */
final class PoolSubpage<T> implements PoolSubpageMetric {
    /**
     * 记录当前PoolSubpage的8KB内存块是从哪一个PoolChunk中申请到的
     */
    final PoolChunk<T> chunk;
    /**
     * 当前PoolSubpage申请的8KB内存在PoolChunk中memoryMap中的下标索引
     */
    private final int memoryMapIdx;
    /**
     * 当前PoolSubpage的页大小，默认为8KB
     */
    private final int pageSize;
    /**
     * 当前PoolSubpage占用的8KB内存在PoolChunk中相对于叶节点的起始点的偏移量
     */
    private final int runOffset;
    /**
     * 通过对每一个二进制位的标记来修改一段内存的占用状态:存储当前PoolSubpage中各个内存块的使用情况。默认长度为8.
     * 因为PoolSubPage能分配的最大内存是8k = 1024*8 = 8192B，并且最小单位是16B(Netty固定默认的最小分配单元是16B)。所以需要 8192/16 = 512 bit位来标识每一个
     * 位的使用情况。而一个long = 8字节 = 64位。因此，只需要512 / 64 = 8 个long即可以表示所有bit位的使用情况。
     * 所以bitmap的默认长度为8。
     */
    private final long[] bitmap;
    /**
     * PoolSubpage是链式结构
     */
    // 指向前置节点的指针
    PoolSubpage<T> prev;
    // 指向后置节点的指针
    PoolSubpage<T> next;
    // 表征当前PoolSubpage是否已经被销毁了
    boolean doNotDestroy;
    // 该page切分后每一段的大小: 表征每个内存块的大小，比如我们这里的就是16
    int elemSize;
    // 该page包含的段数量:记录内存块的总个数
    private int maxNumElems;
    //记录总共可使用的bitmap数组的元素的个数
    private int bitmapLength;
    // 记录下一个可用的节点，初始为0，只要在该PoolSubpage中申请过一次内存，就会更新为-1，
    // 然后一直不会发生变化
    private int nextAvail;
    // 剩余可用的内存块的个数,此个数不确定。因为需要根据当前PoolSubpage所属的类型来判断。
    // 如果当前PoolSubpage属于16B类型(对应PoolArena中tinySubpagePool数组中的第0位Head元素所在的链)，那么，numAvail = 8k/16 = 512。
    // 如果当前PoolSubpage属于256类型(对应PoolArena中tinySubpagePool数组中的第15位Head元素所在的链)，那么，numAvail = 8k/256 = 32。
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    /**
     * 构造函数
     * @param head
     * @param chunk
     * @param memoryMapIdx
     * @param runOffset
     * @param pageSize
     * @param elemSize
     */
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        // 初始化当前PoolSubpage总内存大小，默认为8KB
        this.pageSize = pageSize;
        // 计算bitmap长度，这里pageSize >>> 10其实就是将pageSize / 1024，得到的是8，
        // 从这里就可以看出，无论内存块的大小是多少，这里的bitmap长度永远是8，因为pageSize始终是不变的
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        // 对其余的属性进行初始化
        init(head, elemSize);
    }

    /**
     * 初始化
     * @param head
     * @param elemSize
     */
    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        // elemSize记录了当前内存块的大小
        this.elemSize = elemSize;
        if (elemSize != 0) {
            /*
            * 初始时，numAvail记录了可使用的内存块个数，其个数可以通过pageSize / elemSize计算，
            * 我们这里就是8192 / 16 = 512。maxNumElems指的是最大可使用的内存块个数，
            * 初始时其是与可用内存块个数一致的。
            */
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            // 这里bitmapLength记录了可以使用的bitmap的元素个数，这是因为，我们示例使用的内存块大小是16，
            // 因而其总共有512个内存块，需要8个long才能记录，但是对于一些大小更大的内存块，比如smallSubpagePools
            // 中内存块为1024字节大小，那么其只有8192 / 1024 = 8个内存块，也就只需要一个long就可以表示，
            // 此时bitmapLength就是8。
            // 这里的计算方式应该是bitmapLength = maxNumElems / 64，因为64是一个long的总字节数，
            // 但是Netty将其进行了优化，也就是这里的maxNumElems >>> 6，这是因为2的6次方正好为64
            bitmapLength = maxNumElems >>> 6;
            // 这里(maxNumElems & 63) != 0就是判断元素个数是否小于64，如果小于，则需要将bitmapLegth加一。
            // 这是因为如果其小于64，前面一步的位移操作结果为0，但其还是需要一个long来记录
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
        }
    }
    // 对bitmap数组的值进行初始化
            for (int i = 0; i < bitmapLength; i ++) {
        bitmap[i] = 0;
    }
        // 将当前PoolSubpage添加到PoolSubpage的链表中，也就是最开始PoolArena中的的tinySubpagePools或者smallSubpagePools链表
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     * SubPage的内存分配
     *   对于allocate()方法，其没有传入任何参数是因为当前PoolSubpage所能申请的内存块大小在构造方法中已经通过elemSize指定了。
     *   当前方法返回的是一个long型整数，这里是将要申请的内存块使用了一个long型变量进行表征了。由于一个内存块是否使用是通过一个long型整数表示的，因而，如果想要表征当前申请到的内存块是这个long型整数中的哪一位，
     *   只需要一个最大为63的整数即可(long最多为64位)，这只需要long型数的低6位就可以表示，由于我们使用的是一个
     *   long型数组，因而还需要记录当前是在数组中第几个元素，由于数组长度最多为8，因而对于返回值的7~9位则是记录
     *  了当前申请的内存块是在bitmap数组的第几个元素中。总结来说，返回值的long型数的高32位中的低6位
     *  记录了当前申请的是是bitmap中某个long的第几个位置的内存块，而高32位的7~9位则记录了申请的是bitmap数组
     *  中的第几号元素。
     *  这里说返回值的高32位是因为其低32位记录了当前8KB内存块是在PoolChunk中具体的位置.
     */
    long allocate() {
        // 如果elemSize为0，则直接返回0
        if (elemSize == 0) {
            return toHandle(0);
        }
        // 如果当前PoolSubpage没有可用的元素，或者已经被销毁了，则返回-1
        // 如果两次申请均为256B内存，第1次numAvail = 32,第2次numAvail = 31
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }
        // 计算下一个可用的内存块的位置：第1次为0，第2次申请返回1
        final int bitmapIdx = getNextAvail();
        // 获取该内存块是bitmap数组中的第几号元素（除以64）
        // 因为1个Long是64bit，所以通过右移 >>> 6,即除以64取整，即可得到在bitmap数组的元素位置。
        // 例如 32 >>> 64 = 0。那么表示与bitmap[0]对应
        // 例如 72 >>> 64 = 1,那么表示与bitmap[1]对应
        int q = bitmapIdx >>> 6;//第1次为0，
        // 获取该内存块是bitmap数组中q号位元素的第多少位
        int r = bitmapIdx & 63;//第1次为0，
        assert (bitmap[q] >>> r & 1) == 0;
        // 将bitmap数组中q号元素的目标内存块位置标记为1，表示已经使用
        bitmap[q] |= 1L << r;//第1次 bitmpa[1,0,0,0,0,0,0,0]，第2次，bitMap[3,0,0,0,0,0,0,0,0]
        // 如果当前PoolSubpage中可用的内存块为0，则将其从链表中移除。
        if (-- numAvail == 0) {//如果申请256B内存，numAvail = 32, --numAvail = 31
            removeFromPool();
        }
        // 将得到的bitmapIdx放到返回值的高32位中
        return toHandle(bitmapIdx);
    }

    /**
     * 1、对于free()操作，主要是将目标位置标记为0
     * 2、然后设置相关属性
     * 3、并且判断是否需要将当前PoolSubpage添加到链表中或者从链表移除
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     *
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        // 获取当前需要释放的内存块是在bitmap中的第几号元素
        int q = bitmapIdx >>> 6;
        // 获取当前释放的内存块是在q号元素的long型数的第几位
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        // 将目标位置标记为0，表示可使用状态
        bitmap[q] ^= 1L << r;
        // 设置下一个可使用的数据
        setNextAvail(bitmapIdx);
        // numAvail如果等于0，表示之前已经被移除链表了，因而这里释放后需要将其添加到链表中
        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }
        // 如果可用的数量小于最大数量，则表示其还是在链表中，因而直接返回true
        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            // else分支表示当前PoolSubpage中没有任何一个内存块被占用了
            // 这里如果当前PoolSubpage的前置节点和后置节点相等，这表示其都是默认的head节点，也就是
            // 说当前链表中只有一个可用于内存申请的节点，也就是当前PoolSubpage，这里就不会将其移除
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            // 如果有多个节点，则将当前PoolSubpage移除
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    /**
     * PoolSubpage添加到链中,比如this为PoolSubpage3.
     * 插入之前head <--> PoolSubPage1<-->PoolSubPage2
     * 插入之后：head<-->PoolSubpage3<--> PoolSubPage1<-->PoolSubPage2
     * @param head
     */
    private void addToPool(PoolSubpage<T> head) {
        //在添加到链之前，当前节点head的prev和next都为空
        assert prev == null && next == null;
        //将现在的head设置为prev(即在head后面插入)
        prev = head;
        // 将next设置为head的next
        next = head.next;
        //把自己插入在head 和 head.next之间
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    /**
     * 下一个可用的元素位置
     * @return
     */
    private int getNextAvail() {
        int nextAvail = this.nextAvail;//第1次为0，第2次进入时为-1，因为第1次已经申请了。占了这个位置
        // 如果是第一次尝试获取数据，则直接返回bitmap第0号位置的long的第0号元素，
        // 这里nextAvail初始时为0，在第一次申请之后就会变为-1，后面将不再发生变化，
        // 通过该变量可以判断是否是第一次尝试申请内存
        if (nextAvail >= 0) {
            //已经被申请，设置为不可用 -1
            this.nextAvail = -1;
            return nextAvail;
        }
        // 如果不是第一次申请内存，则在bitmap中进行遍历获取
        return findNextAvail();
    }

    /**
     * 遍列BitMap
     * @return
     */
    private int findNextAvail() {
        /*
         * bitmap 为long类型数组，数组长度为8。因此一共有8* 64 = 512 bit位。
         *第1次进入时，还未被使用，所以 bitmap = {0,0,0,0,0,0,0,0} ->{00000000 00000000 00000000 00000000,00000000 00000000 00000000 00000000,00000000 00000000 00000000 00000000,00000000 00000000 00000000 00000000,... ...}
         *第2次进入时，已经被使用1位，{1,0,0,0,0,0,0,0},->{0000000 00000000 00000000 00000001,00000000 00000000 00000000 00000000,00000000 00000000 00000000 00000000,00000000 00000000 00000000 00000000,... ...}
         *第3次进入时，已经被使用2位，{3,0,0,0,0,0,0,0},->{0000000 00000000 00000000 00000011,00000000 00000000 00000000 00000000,00000000 00000000 00000000 00000000,00000000 00000000 00000000 00000000,... ...}
         *第4次进入时，已经被使用3位，{7,0,0,0,0,0,0,0},->{0000000 00000000 00000000 00000111,00000000 00000000 00000000 00000000,00000000 00000000 00000000 00000000,00000000 00000000 00000000 00000000,... ...}
         * ...
         * 第512次进入时，所有位数都为1
         */
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        // 这里的基本思路就是对bitmap数组进行遍历，首先判断其是否有未使用的内存是否全部被使用过
        // 如果有未被使用的内存，那么就在该元素中找可用的内存块的位置。
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // 与（&）、非（~）、或（|）、异或（^）
            if (~bits != 0) {// 非（~）操作之后不为0，表示bits的64个bit位不全为1，即还有可用内存分配。
                // 判断当前long型元素中是否有可用内存块
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    /**
     * 本方法原理：
     *      起始就是对bitmap数组进行遍历，首先判断当前元素是否有可用的内存块，如果有，
     *      则在该long型元素中进行遍历，找到第一个可用的内存块，最后将表征该内存块位置的整型数据返回。
     *      这里需要说明的是，上面判断bitmap中某个元素是否有可用内存块是使用的是~bits != 0来计算的，
     *      该算法的原理起始就是，如果一个long中所有的内存块都被申请了，那么这个long必然所有的位都为1，
     *      从整体上，这个long型数据的值就为-1，而将其取反~bits之后，值肯定就变为了0，
     *      因而这里只需要判断其取反之后是否等于0即可判断当前long型元素中是否有可用的内存块。
     *
     * @param i 表示当前是bitmap数组中的第几个元素，bits表示该元素的值
     * @param bits
     * @return
     */
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        // 这里baseVal就是将当前是第几号元素放到返回值的第7~9号位置上
        // 例如：当前i=1,表示bitmap中的第1个元素，左移6次就变成了00000000 00000000 00000000 01000000
        // 即落在了第7位上。在返回时进行拼接
        final int baseVal = i << 6;
        // 对bits的0~63号位置进行遍历，判断其是否为0，为0表示该位置是可用内存块，从而将位置数据
        // 和baseVal进行或操作，从而得到一个表征目标内存块位置的整型数据
        for (int j = 0; j < 64; j ++) {
            // 判断当前位置是否为0，如果为0，则表示是目标内存块(空闲的可用的)
            if ((bits & 1) == 0) {
                // 将内存快的位置数据和其位置j进行或操作，从而得到返回值
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            // 将bits不断的向右移位，以找到第一个为0的位置
            bits >>>= 1;
        }
        return -1;
    }

    /**
     *
     */
    private long toHandle(int bitmapIdx) {

        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
