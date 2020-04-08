#　1、零拷贝 Zero-Copy

Zero-copy与传统意义的 [zero-copy ](http://en.wikipedia.org/wiki/Zero-copy)不太一样。传统的zero-copy是IO传输过程中，数据无需中内核态到用户态、用户态到内核态的数据拷贝，减少拷贝次数。而Netty的zero-copy则是完全在用户态，或者说传输层的zero-copy机制，可以参考下图。由于协议传输过程中，通常会有拆包、合并包的过程，一般的做法就是System.arrayCopy了，但是Netty通过ByteBuf.slice以及Unpooled.wrappedBuffer等方法拆分、合并Buffer无需拷贝数据。

如何实现zero-copy的呢。slice实现就是创建一个SlicedByteBuf对象，将this对象，以及相应的数据指针传入即可，wrappedBuffer实现机制类似。

参考资料：https://my.oschina.net/LucasZhu/blog/1799162

## 2、ByteBuf

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

