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

## codec-http

HttpObjectAggregator.java



##  buffer

ByteBuf.java
ByteBufAllocator.java
ByteBufHolder.java
ByteBufUtil.java
CompositeByteBuf.java
PooledByteBuf.java
Unpooled.java

ByteBuf.java

ByteBufUtil.java
PoolArena.java
PoolChunk.java
PoolChunkList.java
PooledByteBuf.java
PooledByteBufAllocator.java
PooledHeapByteBuf.java
PoolSubpage.java
Unpooled.java

##  common

DefaultPromise.java

AbstractEventExecutor.java
EventExecutor.java
OrderedEventExecutor.java

AbstractEventExecutor.java
AbstractScheduledEventExecutor.java
EventExecutor.java
EventExecutorGroup.java
SingleThreadEventExecutor.java

FastThreadLocalRunnable

FastThreadLocal

 DefaultThreadFactory 

DefaultEventExecutorGroup.java
DefaultThreadFactory.java
FastThreadLocal.java
FastThreadLocalRunnable.java
MultithreadEventExecutorGroup.java
SingleThreadEventExecutor.java
ThreadPerTaskExecutor.java
ThreadExecutorMap.java

SocketUtils.java

AbstractScheduledEventExecutor.java
InternalLoggerFactory.java

DefaultEventExecutorChooserFactory.java
EventExecutorChooserFactory.java
MultithreadEventExecutorGroup.java
SingleThreadEventExecutor.java

## handler

LoggingHandler.java
IdleStateHandler.java

## transport

DefaultEventExecutorGroup.java
DefaultThreadFactory.java
FastThreadLocal.java
FastThreadLocalRunnable.java
MultithreadEventExecutorGroup.java
SingleThreadEventExecutor.java
ThreadPerTaskExecutor.java
ThreadExecutorMap.java
AbstractBootstrap.java
ServerBootstrap.java
AbstractNioMessageChannel.java
NioEventLoop.java
NioEventLoopGroup.java
NioServerSocketChannel.java
AbstractChannel.java
AbstractChannelHandlerContext.java
ChannelHandlerContext.java
ChannelInitializer.java
DefaultChannelPipeline.java
DefaultChannelPromise.java
EventLoopGroup.java
MultithreadEventLoopGroup.java
SingleThreadEventLoop.java

AbstractBootstrap.java

ServerBootstrap.java
AbstractNioChannel.java

NioEventLoop.java
NioServerSocketChannel.java
AbstractChannel.java
AbstractChannelHandlerContext.java
Channel.java
ChannelInitializer.java
DefaultChannelHandlerContext.java
DefaultChannelPipeline.java
MultithreadEventLoopGroup.java
ReflectiveChannelFactory.java
SingleThreadEventLoop.java

AbstractBootstrapConfig.java
ServerBootstrap.java
AbstractNioChannel.java
NioEventLoop.java
NioEventLoopGroup.java
AbstractChannel.java
AbstractChannelHandlerContext.java
DefaultChannelPipeline.java
EventLoop.java
ReflectiveChannelFactory.java
SingleThreadEventLoop.java

AbstractNioByteChannel.java
NioEventLoop.java
AbstractChannelHandlerContext.java
ChannelInboundInvoker.java
ChannelOutboundInvoker.java
DefaultSelectStrategy.java
SingleThreadEventLoop.java

DefaultChannelPipeline.java
FileRegion.java