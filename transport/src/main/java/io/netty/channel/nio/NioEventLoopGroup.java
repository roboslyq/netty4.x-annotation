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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.MultithreadEventLoopGroup;
import io.netty.channel.SelectStrategyFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;

import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * {@link MultithreadEventLoopGroup} implementations which is used for NIO {@link Selector} based {@link Channel}s.
 * 0、前提
 *  1): 常见的服务端线程模型有Reactor和Proactor两种，常用的是Reactor。
 *
 *  2)：以下的bossGroup和workerGroup是指示下例示例代码中的变量：
 *         EventLoopGroup bossGroup = new NioEventLoopGroup(1);
 *         EventLoopGroup workerGroup = new NioEventLoopGroup();
 *         ServerBootstrap b = new ServerBootstrap();
 *         b.group(bossGroup, workerGroup)
 *      即bossGroup 和 workerGroup均是EventLoopGroup,即对应的Reactor角色。
 *
 * 1、Netty 基于事件驱动模型，使用不同的事件来通知状态的改变或者操作状态的改变。
 * 2、Channel 为Netty 网络操作抽象类，EventLoop 负责处理注册到其上的 Channel 处理 I/O 操作，两者配合参与 I/O 操作。
 * 3、在Reactor线程模型中，EventLoopGroup就是Reactor的角色。而EvnetLoop即线程，具体的干活的线程。由Reactor完成任务分配和调度。
 *     (1)如果通过ServerBootstrap.group(bossGroup, workerGroup)方式进行初始化，表示Netty使用主从Reactor线程型。
 *          bossGroup是Reactor主线程池，workerGroup是从Reactor线程池。
 *          bossGroup作用：
 *              1) ->接收客户端的连接，初始化Channel参数。
 *              2) ->将链路状态变更时间通知给ChannelPipeline。
 *          workerGroup作用:
 *              1)异步读取通信对端的数据报，发送读事件到ChannelPipeline。
 *              2)异步发送消息到通信对端，调用ChannelPipeline的消息发送接口。
 *              3)执行系统调用Task。
 *              4)执行定时任务Task。
 *     (2)如果通过ServerBootstrap.group(group)方式进行初始化，表示Netty使用单线程池模型型。
 *        此时IO连接，读，写等均由group线程池完成
 *
 * 4、EventLoopGroup 是一个 EventLoop 的分组，它可以获取到一个或者多个 EventLoop 对象，因此它提供了迭代出 EventLoop 对象的方法。
 *      (1)一个 EventLoopGroup 包含一个或多个 EventLoop ，即 EventLoopGroup : EventLoop = 1 : n 。
 *      (2)一个 EventLoop 在它的生命周期内，只能与一个 Thread 绑定，即 EventLoop : Thread = 1 : 1 。
 *      (3)所有有 EventLoop 处理的 I/O 事件都将在它专有的 Thread 上被处理，从而保证线程安全，即 Thread : EventLoop = 1 : 1。
 *      (4)一个 Channel 在它的生命周期内只能注册到一个 EventLoop 上，即 Channel : EventLoop = n : 1。
 *      (5)一个 EventLoop 可被分配至一个或多个 Channel ，即 EventLoop : Channel = 1 : n 。
 * 当一个连接到达时，Netty 就会创建一个 Channel，然后从 EventLoopGroup 中分配一个 EventLoop 来给这个 Channel 绑定上，
 * 在该 Channel 的整个生命周期中都是有这个绑定的 EventLoop 来服务的。
 *
 * 5、通过配置boss和worker线程池的线程个数以及是否共享线程池等方式，Netty的线程模型可以在以下三种Reactor模型之间进行切换:
 *      1)单Reactor单线程模型
 *          1个bossGroup和1个EventLoop
 *      2)单Reactor多线程模型
 *          1个bossGroup和 N 个EventLoop
 *      3)主从Reactor多线程模型
 *          1个bossGroup + N个EventLoop 和 1个workerGroup + N个EventLoop
 */
public class NioEventLoopGroup extends MultithreadEventLoopGroup {

    /**
     * Create a new instance using the default number of threads, the default {@link ThreadFactory} and
     * the {@link SelectorProvider} which is returned by {@link SelectorProvider#provider()}.
     * 创建NioEventLoopGroup实例，此处会层层调用父类的构造器。
     * 同时，在父类中最终完成NioEventLoop的实例初始化。
     * MultithreadEventExecutorGroup==>
     *      if (executor == null) {
     *             executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
     *         }
     *      protected ThreadFactory newDefaultThreadFactory() {
     *         return new DefaultThreadFactory(getClass());
     *     }
     *
     */
    public NioEventLoopGroup() {
        this(0);
    }

    /**
     * Create a new instance using the specified number of threads, {@link ThreadFactory} and the
     * {@link SelectorProvider} which is returned by {@link SelectorProvider#provider()}.
     */
    public NioEventLoopGroup(int nThreads) {
        this(nThreads, (Executor) null);
    }

    /**
     * Create a new instance using the specified number of threads, the given {@link ThreadFactory} and the
     * {@link SelectorProvider} which is returned by {@link SelectorProvider#provider()}.
     */
    public NioEventLoopGroup(int nThreads, ThreadFactory threadFactory) {
        this(nThreads, threadFactory, SelectorProvider.provider());
    }

    public NioEventLoopGroup(int nThreads, Executor executor) {
        this(nThreads, executor, SelectorProvider.provider());
    }

    /**
     * Create a new instance using the specified number of threads, the given {@link ThreadFactory} and the given
     * {@link SelectorProvider}.
     */
    public NioEventLoopGroup(
            int nThreads, ThreadFactory threadFactory, final SelectorProvider selectorProvider) {
        this(nThreads, threadFactory, selectorProvider, DefaultSelectStrategyFactory.INSTANCE);
    }

    public NioEventLoopGroup(int nThreads, ThreadFactory threadFactory,
        final SelectorProvider selectorProvider, final SelectStrategyFactory selectStrategyFactory) {
        super(nThreads, threadFactory, selectorProvider, selectStrategyFactory, RejectedExecutionHandlers.reject());
    }

    public NioEventLoopGroup(
            int nThreads, Executor executor, final SelectorProvider selectorProvider) {
        this(nThreads, executor, selectorProvider, DefaultSelectStrategyFactory.INSTANCE);
    }

    public NioEventLoopGroup(int nThreads, Executor executor, final SelectorProvider selectorProvider,
                             final SelectStrategyFactory selectStrategyFactory) {
        super(nThreads, executor, selectorProvider, selectStrategyFactory, RejectedExecutionHandlers.reject());
    }

    public NioEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                             final SelectorProvider selectorProvider,
                             final SelectStrategyFactory selectStrategyFactory) {
        super(nThreads, executor, chooserFactory, selectorProvider, selectStrategyFactory,
                RejectedExecutionHandlers.reject());
    }

    public NioEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                             final SelectorProvider selectorProvider,
                             final SelectStrategyFactory selectStrategyFactory,
                             final RejectedExecutionHandler rejectedExecutionHandler) {
        super(nThreads, executor, chooserFactory, selectorProvider, selectStrategyFactory, rejectedExecutionHandler);
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the child event loops.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        for (EventExecutor e: this) {
            ((NioEventLoop) e).setIoRatio(ioRatio);
        }
    }

    /**
     * Replaces the current {@link Selector}s of the child event loops with newly created {@link Selector}s to work
     * around the  infamous epoll 100% CPU bug.
     */
    public void rebuildSelectors() {
        for (EventExecutor e: this) {
            ((NioEventLoop) e).rebuildSelector();
        }
    }

    /**
     * 实例化具体的NioEventLoop线程。
     * @param executor ThreadPerTaskExecutor
     * @param args args[0] = 在windows下为WindowsSelectorProvider
     *             args[1] = DefaultSelectStrategyFactory
     *             args[1] = RejectedExecutionHandler
     * @return
     * @throws Exception
     */
    @Override
    protected EventLoop newChild(Executor executor, Object... args) throws Exception {
        return new NioEventLoop(this, executor, (SelectorProvider) args[0],
            ((SelectStrategyFactory) args[1]).newSelectStrategy(), (RejectedExecutionHandler) args[2]);
    }
}
