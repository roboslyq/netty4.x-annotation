#　1、Netty中零拷贝 Zero-Copy是指什么？

​		Zero-copy与传统意义的 [zero-copy ](http://en.wikipedia.org/wiki/Zero-copy)不太一样。传统的zero-copy是IO传输过程中，数据无需中内核态到用户态、用户态到内核态的数据拷贝，减少拷贝次数。

​		而Netty的zero-copy则是完全在用户态，或者说传输层的zero-copy机制，可以参考下图。由于协议传输过程中，通常会有拆包、合并包的过程，一般的做法就是System.arrayCopy了，但是Netty通过ByteBuf.slice以及Unpooled.wrappedBuffer等方法拆分、合并Buffer无需拷贝数据。

如何实现zero-copy的呢。slice实现就是创建一个SlicedByteBuf对象，将this对象，以及相应的数据指针传入即可，wrappedBuffer实现机制类似。

Netty的零拷贝主要体现在三个方面：

第一种实现：DirectByteBuf
就如上所说，ByteBuf可以分为HeapByteBuf和DirectByteBuf，当使用DirectByteBuf可以实现零拷贝

第二种实现：CompositeByteBuf
CompositeByteBuf将多个ByteBuf封装成一个ByteBuf，对外提供封装后的ByteBuf接口

第三种实现：DefaultFileRegion
DefaultFileRegion是Netty的文件传输类，它通过transferTo方法将文件直接发送到目标Channel，而不需要循环拷贝的方式，提升了传输性能


参考资料：https://my.oschina.net/LucasZhu/blog/1799162

# 2、Netty为什么要实现ByteBuf，相比JDK ByteBuffer有什么优缺点?

**1、ByteBuf与Java NIO Buffer**

ByteBuf则是Java NIO Buffer的新轮子，官方列出了一些ByteBuf的特性：

- 需要的话，可以自定义buffer类型；
- 通过组合buffer类型，可实现透明的zero-copy；
- 提供动态的buffer类型，如StringBuffer一样，容量是按需扩展；
- 无需调用flip()方法；
- 常常「often」比ByteBuffer快。

 ByteBuf提供了一些较为丰富的实现类，逻辑上主要分为两种：HeapByteBuf和DirectByteBuf，实现机制则分为两种：PooledByteBuf和UnpooledByteBuf，除了这些之外，Netty还实现了一些衍生ByteBuf（DerivedByteBuf），如：ReadOnlyByteBuf、DuplicatedByteBuf以及SlicedByteBuf。 

HeapByteBuf和DirectByteBuf区别在于Buffer的管理方式：HeapByteBuf由Heap管理，Heap是Java堆的意思，内部实现直接采用byte[] array；DirectByteBuf使用是堆外内存，Direct应是采用Direct  I/O之意，内部实现使用java.nio.DirectByteBuffoer。

PooledByteBuf和UnpooledByteBuf，UnpooledByteBuf实现就是普通的ByteBuf了，PooledByteBuf是4.x之后的新特性，稍后再说。

DerivedByteBuf是ByteBuf衍生类，实现采用装饰器模式对原有的ByteBuf进行了一些封装。ReadOnlyByteBuf是某个ByteBuf的只读引用；DuplicatedByteBuf是某个ByteBuf对象的引用；SlicedByteBuf是某个ByteBuf的部分内容。

SwappedByteBuf和CompositedByteBuf我觉得也算某种程度的衍生类吧，SwappedByteBuf封装了一个ByteBuf对象和ByteOrder对象，实现某个ByteBuf对象序列的逆转；CompositedByteBuf内部实现了一个ByteBuf列表，称之为组合ByteBuf，由于不懂相关的技术业务，无法理解该类的存在意义（官方解释：A user can save bulk memory copy operations using a composite buffer at  the cost of relatively expensive random  access.）。这两个类从逻辑上似乎完全可以继承于DerivedByteBuf，Trustin大神为啥如此设计呢？

# 3、Netty是如何实现线程和Channel唯一绑定的?

>  netty的线程模型对一个channel来说是单线程的,也就是说这个channel的所有读写事件都是同一个线程执行的,避免了多线程产生的并发问题。
>
>  而一个eventloop是可以被多个channel绑定的,那么每次服务器连接一个channel之时,netty时如何知道使用哪个线程的呢? 



# 4、Netty中,耗时业务到底该如何处理？

##　Handler中添加业务线程池

## Context中，添加线程池

https://www.cnblogs.com/stateis0/p/9062151.html

# 5、Netty是如何解决粘包和拆包问题?



# 6、Netty如果实现池化技术的？Arena？

# 7、Native transports模块有什么用？为什么要用这个模块？
    > https://netty.io/wiki/native-transports.html

# 8、EmbeddedChannel 有什么用？