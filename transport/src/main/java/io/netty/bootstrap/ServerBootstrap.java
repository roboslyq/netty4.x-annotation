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
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 * 1、服务端服务启动引导类 ，Bootstrap子类，方便引导启动 ServerChannel类。
 *    此类是服务端的,具体客户端见Bootstrap。
 * 2、服务端有两种线程池，用于Acceptor的React主线程(一般命名为bossGroup)和用于I/O操作的React从线程池（一般命名为workGroup）；
 *    客户端只有用于连接及IO操作的React的主线程池；
 * 3、ServerBootstrap中定义了服务端React的"从线程池"对应的相关配置，都是以child开头的属性。
 *    而用于"主线程池"channel的属性都定义在AbstractBootstrap中；
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);
    /**
     * ServerBootStrap配置相关
     */
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);
    /**
     * 从线程池(workerGroup)可选项相关配置
     */
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    /**
     * 从线程池(workerGroup)ATTR相关配置
     */
    private final Map<AttributeKey<?>, Object> childAttrs = new LinkedHashMap<AttributeKey<?>, Object>();
    /**
     * 定义(workerGroup)的线程组
     */
    private volatile EventLoopGroup childGroup;
    /**
     * 定义(workerGroup)的Handler：此处通常为ChannelInitializer的具体实现
     */
    private volatile ChannelHandler childHandler;

    public ServerBootstrap() { }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        synchronized (bootstrap.childAttrs) {
            childAttrs.putAll(bootstrap.childAttrs);
        }
    }

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     * 设置Reactor的主线程和从线程
     * parentGroup为主线程组:处理IO连接
     * childGroup为从线程组：处理具体的IO读写
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        super.group(parentGroup);
        ObjectUtil.checkNotNull(childGroup, "childGroup");
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = childGroup;
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        ObjectUtil.checkNotNull(childOption, "childOption");
        if (value == null) {
            synchronized (childOptions) {
                childOptions.remove(childOption);
            }
        } else {
            synchronized (childOptions) {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        ObjectUtil.checkNotNull(childKey, "childKey");
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     * 设置从线程组的相关处理器。
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        return this;
    }

    /**
     * START-SERVER-STEP3：
     * 服务端启动时配置channel<NioServerSocketChannel>,在父类AbstractBootStrap#initAndRegister()被调用
     * @param channel
     * @throws Exception
     */
    @Override
    void init(Channel channel) throws Exception {
        /* 即获取如下代码中的option()方法设置的对应参数：
         * ServerBootstrap b = new ServerBootstrap();
         *              b.group(bossGroup, workerGroup)
         *              .channel(NioServerSocketChannel.class)
         *              .option(ChannelOption.SO_BACKLOG, 100)
         */
        final Map<ChannelOption<?>, Object> options = options0();
        synchronized (options) {
            setChannelOptions(channel, options, logger);
        }
        /*
         * Channel设置的相关属性
         */
        final Map<AttributeKey<?>, Object> attrs = attrs0();
        synchronized (attrs) {
            for (Entry<AttributeKey<?>, Object> e: attrs.entrySet()) {
                @SuppressWarnings("unchecked")
                AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
                channel.attr(key).set(e.getValue());
            }
        }
        /*
         * channel = NioServerSocketChannel： 初始化channelPipeline
         * p = DefaultChannelPipeline <在父类AbstractChannel构造方法中完成初始化>
         */
        // 获取channel中的 pipeline，这个pipeline使我们前面在channel创建过程中设置的 pipeline
        ChannelPipeline p = channel.pipeline();
        //设置相关属性
        // 将启动器中配置的 childGroup 保存到局部变量 currentChildGroup
        final EventLoopGroup currentChildGroup = childGroup;
        // 将启动器中配置的 childHandler 保存到局部变量 currentChildHandler
        final ChannelHandler currentChildHandler = childHandler;
        final Entry<ChannelOption<?>, Object>[] currentChildOptions;
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs;
        // 保存用户设置的 childOptions 到局部变量 currentChildOptions
        synchronized (childOptions) {
            currentChildOptions = childOptions.entrySet().toArray(newOptionArray(0));
        }
        // 保存用户设置的 childAttrs 到局部变量 currentChildAttrs
        synchronized (childAttrs) {
            currentChildAttrs = childAttrs.entrySet().toArray(newAttrArray(0));
        }
        // 构造channelPipeline链最后一个channelHandler，此链固定为：ServerBootstrapAcceptor。用户不用进行相关设置
        /* 假设用户使用方式如下：
         ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .option(ChannelOption.SO_BACKLOG, 100)
             .handler(new LoggingHandler(LogLevel.INFO))
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     ChannelPipeline p = ch.pipeline();
                     if (sslCtx != null) {
                         p.addLast(sslCtx.newHandler(ch.alloc()));
                     }
                     p.addLast(new LoggingHandler(LogLevel.INFO));
                     p.addLast(serverHandler);
                 }
             });

          那么，在此时，p中已经可能存在3个ChannelHandler了
               p.addLast(sslCtx.newHandler(ch.alloc()));
               p.addLast(new LoggingHandler(LogLevel.INFO));
               p.addLast(serverHandler);
          那么，再调用一次下面方法后，结果为：
               p.addLast(sslCtx.newHandler(ch.alloc()));
               p.addLast(new LoggingHandler(LogLevel.INFO));
               p.addLast(serverHandler);
               pipeline.addLast(new ServerBootstrapAcceptor())

                DefaultChannelPipeline采用了链表方式保存上述结构关系。
         */
        /*
         * 因为是服务端，所以需要添加一个通用的Handler: ServerBootstrapAcceptor
         */
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) throws Exception {
                final ChannelPipeline pipeline = ch.pipeline();
                // 获取启动器上配置的handler
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    // 添加 handler 到 pipeline 中
                    pipeline.addLast(handler);
                }
                //异步添加内置的连接处理Handler： ServerBootstrapAcceptor
                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        // 用child相关的参数创建出一个新连接接入器ServerBootstrapAcceptor
                        // 通过 ServerBootstrapAcceptor 可以将一个新连接绑定到一个线程上去
                        // 每次有新的连接进来 ServerBootstrapAcceptor 都会用child相关的属性对它们进行配置，并注册到ChaildGroup上去
                        pipeline.addLast(new ServerBootstrapAcceptor(
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }

    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    private static Entry<AttributeKey<?>, Object>[] newAttrArray(int size) {
        return new Entry[size];
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<ChannelOption<?>, Object>[] newOptionArray(int size) {
        return new Map.Entry[size];
    }

    /**
     * Acceptor实现:完成Channel的注册
     */
    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {
        // 子EventLoopGroup，即为workGroup
        private final EventLoopGroup childGroup;
        // ServerBootstrap启动时配置的 childHandler
        private final ChannelHandler childHandler;
        // ServerBootstrap启动时配置的 childOptions
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        // ServerBootstrap启动时配置的 childAttrs
        private final Entry<AttributeKey<?>, Object>[] childAttrs;

        private final Runnable enableAutoReadTask;
        // 构造函数
        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        /**
         * 当事件为ACCEPTOR时，读请求实现
         *      处理Pipeline所传播的channelRead事件
         *      pipeline.fireChannelRead(readBuf.get(i));
         *      ServerBootstrapAcceptor的channelRead接口将会被调用，用于处理channelRead事件
         * @param ctx
         * @param msg NioSocketChannel
         */
        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // 获取传播事件的对象数据，即为前面的readBuf.get(i)
            // readBuf.get(i)取出的对象为 NioSocketChannel
            final Channel child = (Channel) msg;
            // 向 NioSocketChannel 添加childHandler，也就是我们常看到的
            // ServerBootstrap在启动时配置的代码：
            // ServerBootstrap.childHandler(new ChannelInitializer<SocketChannel>() {...} ）
            // 最终的结果就是向NioSocketChannel的Pipeline添加用户自定义的ChannelHandler 用于处理客户端的channel连接
            child.pipeline().addLast(childHandler);
            // 配置 NioSocketChannel的TCP属性
            setChannelOptions(child, childOptions, logger);
            // 配置 NioSocketChannel 一些用户自定义数据
            for (Entry<AttributeKey<?>, Object> e: childAttrs) {
                child.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
            }
            // 将NioSocketChannel注册到childGroup，也就是Netty的WorkerGroup当中去
            // 也就是完成Selector注册。childGroup = NioEventLoopGroup
            try {
                // childGroup = MultithreadEventLoopGroup
                // <在childGroup.register(child)这个方法里面，会触发ChannelInitializer#initChannel()方法>
                // NioSocketChannel注册到work的eventLoop中，这个过程和NioServerSocketChannel注册到boss的eventLoop的过程一样
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        return copiedMap(childOptions);
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
