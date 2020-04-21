> 内存其实就是一块真实的操作系统的内存地址。具体怎么分配和使用这块内存地址，Netty对象其做了抽象。
>
> PoolArena,PoolChunk,PoolChunkList,PoolSubpage，这些都是对内存相关属性进行定义。然后使用这些定义的属性对内存进行相关的管理(内存分配，内存回收等)。
>
> 

# ByteBufAllocator

> ByteBuf内存分配器，入口类。具体默认的实现在工具类`ByteBufUtil`中的类属性`static final ByteBufAllocator DEFAULT_ALLOCATOR;`。
>
> 具体的ByteBufAllocator是由`PooledByteBufAllocator`通常，默认开启是池化的。





>  
>
>  netty的对象池化，包含两部分内容，首先是ByteBuf对象的池化（Recycler类实现），其次是真实内存(array或者DirectByteBuffer)的池化。 
>
>  - ByteBuf对象池化
>   -  在netty中ByteBuf的创建是个非常频繁的过程，使用对象池可以起到**对象可以复用，减少gc频率**的作用 

# PoolChunk

Netty一次向系统申请16M的连续内存空间，这块内存通过PoolChunk对象包装，为了更细粒度的管理它，进一步的把这16M内存分成了2048个页（pageSize=8k）。页作为Netty内存管理的最基本的单位 ，所有的内存分配首先必须申请一块空闲页。`Ps: 这里可能有一个疑问，如果申请1Byte的空间就分配一个页是不是太浪费空间，在Netty中Page还会被细化用于专门处理小于4096Byte的空间申请` 那么这些Page需要通过某种数据结构跟算法管理起来。最简单的是采用数组或位图管理


![img](https:////upload-images.jianshu.io/upload_images/2036372-d0ddcbbf39fe21c5.png?imageMogr2/auto-orient/strip|imageView2/2/w/585)

如上图1表示已申请，0表示空闲。这样申请一个Page的复杂度为O(n)。

![img](https:////upload-images.jianshu.io/upload_images/2036372-5b9f9d6c7ce3f9c3.png?imageMogr2/auto-orient/strip|imageView2/2/w/588)

这样的一个完全二叉树可以用大小为4096的数组表示，数组元素的值含义为：

```java
private final byte[] memoryMap; //表示完全二叉树,共有4096个
private final byte[] depthMap; //表示节点的层高，共有4096个
```

1. memoryMap[i] = depthMap[i]：表示该节点下面的所有叶子节点都可用，这是初始状态
2. memoryMap[i] = depthMap[i] + 1：表示该节点下面有一部分叶子节点被使用，但还有一部分叶子节点可用
3. memoryMap[i] = maxOrder + 1 = 12：表示该节点下面的所有叶子节点不可用

有了上面的数据结构，那么页的申请跟释放就非常简单了，只需要从根节点一路遍历找到可用的节点即可，复杂度为O(lgn)。代码为：

```java
#PoolChunk
  //根据申请空间大小，选择申请方法
  long allocate(int normCapacity) {
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            return allocateRun(normCapacity); //大于1页
        } else {
            return allocateSubpage(normCapacity);
        }
    }
  //按页申请
  private long allocateRun(int normCapacity) {
        //计算需要在哪一层开始
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        int id = allocateNode(d); 
        if (id < 0) {
            return id;
        }
        freeBytes -= runLength(id);
        return id;
    }
  / /申请空间，即节点编号
  private int allocateNode(int d) {
        int id = 1; //从根节点开始
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        if (val > d) { // unusable
            return -1;
        }
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1; //左节点
            val = value(id);
            if (val > d) {
                id ^= 1; //右节点
                val = value(id);
            }
        }
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
       //更新当前申请到的节点的状态信息
        setValue(id, unusable); // mark as unusable
       //级联更新父节点的状态信息
        updateParentsAlloc(id);
        return id;
    }
  //级联更新父节点的状态信息   
  private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            id = parentId;
        }
    }
```



# 

对于小内存（小于4096）的分配还会将Page细化成更小的单位Subpage。Subpage按大小分有两大类，36种情况：

1. Tiny：小于512的情况，最小空间为16，对齐大小为16，区间为[16,512)，所以共有32种情况。

2. 

   

   Small：大于等于512的情况，总共有四种，512,1024,2048,4096。

   ![img](https:////upload-images.jianshu.io/upload_images/2036372-588504dbb3f87d03.png?imageMogr2/auto-orient/strip|imageView2/2/w/592)

   PoolSubpage中直接采用位图管理空闲空间（因为不存在申请k个连续的空间），所以申请释放非常简单。
    代码：

>  因为netty用的是内存映射，也就是堆外内存，所以netty要自己分配回收内存
>
>  memoryMap类似buddy算法，觉得这个实现挺精妙的

# PoolChunkList

# PoolArena

# PoolSubPage

# Recycler 

> 池化技术， Recycler不仅可以用于ByteBuf对象的池化，它是一个通用的对象池化技术，我们可以直接使用Recycler实现自身系统对象的池化。



## Recycler的关键技术点

1.  **多线程竞争下的对象分配和释放（怎么减小多线程竞争）**
    每个线程都有自己的对象池，分配时从自己的对象池中获得一个对象。其他线程release对象时，把对象归还到原来自己的池子中去（分配线程的池子）。
    大量使用了ThreadLocal，每个线程都有自己的stack和weakorderqueue，做到线程封闭，有力减小竞争。
2.  **对象的分配**
    2.1 先从stack中分配，如果stack有，则直接stack.pop获得对象
    2.2  如果stack中没有，从WeakOrderQueue中一次移取多个对象到stack中(每次会尽可能scavenge整个head Link到stack中)，最后stack.pop获得对象。
3.  **对象的release**
    3.1 如果release线程就是对象的分配线程，直接入栈
    3.2 如果release线程和对象的分配线程不是同一个线程，则归还到分配线程的WeakOrderQueue中。release线程维护的数据结构为：ThreadLocal<Map<Stack,WeakOrderQueue>>

## Recycler 

-  每一个Recycler对象包含一个`FastThreadLocal<Stack<T> `属性`threadLocal`；  

  > FastTheadLocal是JDK中ThreadLocal的扩展，而此处ThreadLocal中存储的是一个Stack。因此，可能通过threadLocal这个变量，将线程与`Stack`进行绑定。而该Stack对象包含一个DefaultHandle[]，而DefaultHandle中有一个属性T value，用于存储真实对象。也就是说，每一个被回收的对象都会被包装成一个DefaultHandle对象。

-  每一个Recycler对象包含一个 `FastThreadLocal<Map<Stack<?>, WeakOrderQueue> ` 属性DELAYED_RECYCLED。

  > 每一个线程对象包含一个 `Map<Stack<?>,WeakOrderQueue>` ，存储着为其他线程创建的WeakOrderQueue对象，WeakOrderQueue对象中存储一个以Head为首的Link数组，每个Link对象中存储一个DefaultHandle[]数组，用于存放回收对象。

















## ThreadLocal

> 虽然提供了多个PoolArena减少线程间的竞争，但是难免还是会存在锁竞争，所以需要利用ThreaLocal进一步优化，把已申请的内存放入到ThreaLocal自然就没有竞争了。大体思路是在ThreadLocal里面放一个PoolThreadCache对象，然后释放的内存都放入到PoolThreadCache里面，下次申请先从PoolThreadCache获取。
>  但是，如果thread1申请了一块内存，然后传到thread2在线程释放，这个Netty在内存holder对象里面会引用PoolThreadCache，所以还是会释放到thread1里



> 通过NIO传输数据时需要一个内存地址，并且在数据传输过程中这个地址不可发生变化。但是，GC为了减少内存碎片会压缩内存，也就是说对象的实际内存地址会发生变化，所以Java就引入了不受GC控制的堆外内存来进行IO操作。那么数据传输就变成了这样
>
> ![img](https:////upload-images.jianshu.io/upload_images/2036372-4b304e7a6b23b35d.png?imageMogr2/auto-orient/strip|imageView2/2/w/369)
>
> 但是内存拷贝对性能有可能影响比较大，所以Java中可以绕开堆内存直接操作堆外内存，问题是创建堆外内存的速度比堆内存慢了10到20倍，为了解决这个问题Netty就做了内存池。
>
> 内存池是一套比较成熟的技术了，Netty的内存池方案借鉴了jemalloc。了解一下其背后的实现原理对阅读Netty内存池的源代码还是很有帮助的
>
> 1. [jemalloc原理](https://links.jianshu.com/go?to=https%3A%2F%2Fpeople.freebsd.org%2F~jasone%2Fjemalloc%2Fbsdcan2006%2Fjemalloc.pdf)
> 2. [glibc内存管理ptmalloc源代码分析](https://links.jianshu.com/go?to=http%3A%2F%2Fvdisk.weibo.com%2Fs%2FssWk3)



# 总体结构

Netty各版本的内存管理差异比较大，这里以4.1版本为例。为了不陷入无尽的细节泥沼之中，应该先了解下jemalloc的原理，然后就可以构想出内存池大概思路：

1. 首先应该会向系统申请一大块内存，然后通过某种算法管理这块内存并提供接口让上层申请空闲内存
2. 申请到的内存地址应该透出到应用层，但是对开发人员来说应该是透明的，所以要有一个对象包装这个地址，并且这个对象应该也是池化的，也就是说不仅要有内存池，还要有一个对象池

所以，自然可以带着以下问题去看源码：

1. 内存池管理算法是怎么做到申请效率，怎么减少内存碎片
2. 高负载下内存池不断扩展，如何做到内存回收
3. 对象池是如何实现的，这个不是关键路径，可以当成黑盒处理
4. 内存池跟对象池作为全局数据，在多线程环境下如何减少锁竞争
5. 池化后内存的申请跟释放必然是成对出现的，那么如何做内存泄漏检测，特别是跨线程之间的申请跟释放是如何处理的。

>  

# 4. Recycler核心结构

Recycler关联了4个核心类：

1. DefaultHandle:对象的包装类，在Recycler中缓存的对象都会包装成DefaultHandle类。

2. Stack:存储本线程回收的对象。对象的获取和回收对应Stack的pop和push，即获取对象时从Stack中pop出1个DefaultHandle，回收对象时将对象包装成DefaultHandle push到Stack中。Stack会与线程绑定，即每个用到Recycler的线程都会拥有1个Stack，在该线程中获取对象都是在该线程的Stack中pop出一个可用对象。
    **stack底层以数组作为存储结构，初始大小为256，最大大小为32768。** 

3. WeakOrderQueue:存储其它线程回收到分配线程的对象，当某个线程从Stack中获取不到对象时会从WeakOrderQueue中获取对象。

    3.1 从分配线程的角度来看，每个分配线程的Stack拥有1个WeakOrderQueue链表，每个WeakOrderQueue元素维持了对应release线程归还的对象。每个分配线程的WeakOrderQueue链表的对象池子中的对象数量不能超过

   ```
   availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
   ```

   ，该值默认为16K=16384。

    3.2 从release角度看，主要的数据结构为：

   ![img](https:////upload-images.jianshu.io/upload_images/10356017-47fbe075f4f2d8dd.png?imageMogr2/auto-orient/strip|imageView2/2/w/643)

   image.png

    Map<Stack<?>, WeakOrderQueue>维持了release线程向每个分配线程归还对象的数据结构，Map的大小不超过

   ```
   MAX_DELAYED_QUEUES_PER_THREAD = max(0, SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread", // We use the same value as default EventLoop number NettyRuntime.availableProcessors() * 2));
   ```

   。

    也就是release线程默认最多能向核数*2个分配线程归还对象。

4. Link: WeakOrderQueue中包含1个Link链表，回收对象存储在链表某个Link节点里，当Link节点存储的回收对象满了时会新建1个Link放在Link链表尾。
    `private static final class Link extends AtomicInteger`
    每个Link节点默认能存储16个元素，通过继承AtomicInteger是为了解决分配线程通过WeakOrderQueue和回收线程归还对象时的线程竞争。

整个Recycler回收对象存储结构如下图所示：

![img](https:////upload-images.jianshu.io/upload_images/10356017-35dce9790cdb0065.png?imageMogr2/auto-orient/strip|imageView2/2/w/799)



