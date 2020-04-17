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
package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AttributeKey;

/**
 * Echoes back any received data from a client.
 */
public final class EchoServer {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    /**
     * (1)、 初始化用于Acceptor的主"线程池"以及用于I/O工作的从"线程池"；
     * (2)、 初始化ServerBootstrap实例， 此实例是netty服务端应用开发的入口；
     * (3)、 通过ServerBootstrap的group方法，设置（1）中初始化的主从"线程池"；
     * (4)、 指定通道channel的类型，由于是服务端，故而是NioServerSocketChannel；
     * (5)、 设置ServerSocketChannel的处理器
     * (6)、 设置子通道也就是SocketChannel的处理器， 其内部是实际业务开发的"主战场"
     * (7)、 配置ServerSocketChannel的选项
     * (8)、 配置子通道也就是SocketChannel的选项
     * (9)、 绑定并侦听某个端口
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        // Configure SSL.
        final SslContext sslCtx;
        if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        // Configure the server.
        /*
         *   1、boss线程池的线程负责处理请求的accept事件，当接收到accept事件的请求时，把对应的socket封装到一个NioSocketChannel中，
         *       并交给work线程池。
         *   2、work线程池负责请求的read和write事件，由对应的Handler处理
         */
        //创建boss主线程池，用于服务端接受(Acceptor)客户端的连接
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        //初始化用于SocketChannel的I/O读写工作的从"线程池"；
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            // 创建辅助启动类ServerBootstrap，并设置相关配置：
            ServerBootstrap b = new ServerBootstrap();
            // 设置处理Accept事件和读写操作的事件循环组
            b.group(bossGroup, workerGroup)
                    // 配置Channel类型
             .channel(NioServerSocketChannel.class)
                    // 配置监听地址
//             .localAddress()
                    // 设置服务器通道的选项，设置TCP属性
             .option(ChannelOption.SO_BACKLOG, 100)
                    // channel属性，便于保存用户自定义数据
//                    .attr(AttributeKey.newInstance("UserId"), "60293")
             .handler(new LoggingHandler(LogLevel.INFO))
                    // 设置子处理器，主要是用户的自定义处理器，用于处理IO网络事件
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     ChannelPipeline p = ch.pipeline();
                     if (sslCtx != null) {
                         p.addLast(sslCtx.newHandler(ch.alloc()));
                     }
                     //打开日志组件，方便调试
                     p.addLast(new LoggingHandler(LogLevel.INFO));
                     p.addLast(serverHandler);
                 }
             });

            // Start the server.
            // 调用bind()方法绑定端口，sync()会阻塞等待处理请求。这是因为bind()方法是一个异步过程，
            // 会立即返回一个ChannelFuture对象，调用sync()会等待执行完成
            // 核心流程：启动服务，监听端口，并同步阻塞
            ChannelFuture f = b.bind(PORT).sync();

            // Wait until the server socket is closed.
            // 获得Channel的closeFuture阻塞等待关闭，服务器Channel关闭时closeFuture会完成
            f.channel().closeFuture().sync();

        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
