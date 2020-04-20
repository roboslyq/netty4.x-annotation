# Netty常用类

## channel

- NioSocketChannel：代表异步的客户端 TCP Socket 连接
-  NioServerSocketChannel：异步的服务器端 TCP Socket 连接
-  NioDatagramChannel：异步的 UDP 连接
-  NioSctpChannel：异步的客户端 Sctp 连接
-  NioSctpServerChannel：异步的 Sctp 服务器端连接
-  OioSocketChannel：同步的客户端 TCP Socket 连接
-  OioServerSocketChannel：同步的服务器端 TCP Socket 连接
-  OioDatagramChannel：同步的 UDP 连接
-  OioSctpChannel：同步的 Sctp 服务器端连接
-  OioSctpServerChannel：同步的客户端 TCP Socket 连接

## codec

- ByteToMessageCodec.java
- ByteToMessageDecoder.java
- MessageToByteEncoder.java

## codec-http

- HttpObjectAggregator.java



##  buffer

- AbstractByteBuf.java
- AbstractByteBufAllocator.java
- AbstractReferenceCountedByteBuf.java
- ByteBuf.java
- ByteBufAllocator.java
- ByteBufHolder.java
- ByteBufUtil.java
- CompositeByteBuf.java
- HeapByteBufUtil.java
- PoolArena.java
- PoolChunk.java
- PoolChunkList.java
- PoolChunkMetric.java
- PooledByteBuf.java
- PooledByteBufAllocator.java
- PooledDirectByteBuf.java
- PooledHeapByteBuf.java
- PooledUnsafeDirectByteBuf.java
- PoolSubpage.java
- PoolThreadCache.java
- ReadOnlyByteBuf.java
- Unpooled.java
- UnpooledHeapByteBuf.java
  

##  common

AbstractEventExecutor.java
AbstractScheduledEventExecutor.java
DefaultEventExecutorChooserFactory.java
DefaultEventExecutorGroup.java
DefaultPromise.java
DefaultThreadFactory 
DefaultThreadFactory.java
EventExecutor.java
EventExecutorChooserFactory.java
EventExecutorGroup.java

- FastThreadLocal
  - 对JDK的ThreadLocal进行优化扩展



FastThreadLocal.java
FastThreadLocalRunnable
FastThreadLocalRunnable.java
InternalLoggerFactory.java
MultithreadEventExecutorGroup.java

ObjectUtil.java


OrderedEventExecutor.java

- Recycler
  - 对象池化技术实现
  - 主要有3个内部抽象
    - Handle
    - Stack
    - WeakOrderQueue

PlatformDependent.java
PlatformDependent0.java
ResourceLeakDetector.java
ResourceLeakTracker.java

SingleThreadEventExecutor.java
SocketUtils.java
ThreadExecutorMap.java
ThreadPerTaskExecutor.java

- Promise
  - 一个异步框架的实现。扩展的JDK原生的Future.

## handler

LoggingHandler.java
IdleStateHandler.java

## transport

AbstractBootstrap.java
AbstractBootstrapConfig.java
AbstractChannel.java
AbstractChannelHandlerContext.java
AbstractNioByteChannel.java
AbstractNioChannel.java
AbstractNioMessageChannel.java
Channel.java
ChannelDuplexHandler.java
ChannelHandlerContext.java
ChannelInboundInvoker.java
ChannelInitializer.java
ChannelOutboundInvoker.java
DefaultChannelHandlerContext.java
DefaultChannelPipeline.java
DefaultChannelPromise.java
DefaultEventExecutorGroup.java
DefaultSelectStrategy.java
DefaultThreadFactory.java
EventLoop.java
EventLoopGroup.java
FastThreadLocal.java
FastThreadLocalRunnable.java
FileRegion.java
MultithreadEventExecutorGroup.java
MultithreadEventLoopGroup.java
NioEventLoop.java
NioEventLoopGroup.java
NioServerSocketChannel.java
NioSocketChannel.java
RecvByteBufAllocator.java
ReflectiveChannelFactory.java
ServerBootstrap.java
SingleThreadEventExecutor.java
SingleThreadEventLoop.java
ThreadExecutorMap.java
ThreadPerTaskExecutor.java