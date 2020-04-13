/*
 * Copyright 2016 The Netty Project
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
package io.netty.channel;
/**
 * I/O输出操作相关，具体的输出相关操作请见{@link ChannelOutboundInvoker}
 * 本类主要有以下几个功能
 * 1、Channel的注册/取消注册事件
 * 2、Channel状态Active/InActiove事件
 * 3、Channel异常事件
 * 4、Channel读事件
 * 5、Channel读完成事件
 */
public interface ChannelInboundInvoker {

    /**
     * A {@link Channel} was registered to its {@link EventLoop}.
     *
     * This will result in having the  {@link ChannelInboundHandler#channelRegistered(ChannelHandlerContext)} method
     * called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.N
     */
    ChannelInboundInvoker fireChannelRegistered();

    /**
     * A {@link Channel} was unregistered from its {@link EventLoop}.
     *
     * This will result in having the  {@link ChannelInboundHandler#channelUnregistered(ChannelHandlerContext)} method
     * called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelInboundInvoker fireChannelUnregistered();

    /**
     * A {@link Channel} is active now, which means it is connected.
     *
     * This will result in having the  {@link ChannelInboundHandler#channelActive(ChannelHandlerContext)} method
     * called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelInboundInvoker fireChannelActive();

    /**
     * A {@link Channel} is inactive now, which means it is closed.
     *
     * This will result in having the  {@link ChannelInboundHandler#channelInactive(ChannelHandlerContext)} method
     * called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelInboundInvoker fireChannelInactive();

    /**
     * A {@link Channel} received an {@link Throwable} in one of its inbound operations.
     *
     * This will result in having the  {@link ChannelInboundHandler#exceptionCaught(ChannelHandlerContext, Throwable)}
     * method  called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelInboundInvoker fireExceptionCaught(Throwable cause);

    /**
     * A {@link Channel} received an user defined event.
     *
     * This will result in having the  {@link ChannelInboundHandler#userEventTriggered(ChannelHandlerContext, Object)}
     * method  called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelInboundInvoker fireUserEventTriggered(Object event);

    /**
     * A {@link Channel} received a message.
     *
     * This will result in having the {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)}
     * method  called of the next {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelInboundInvoker fireChannelRead(Object msg);

    /**
     * Triggers an {@link ChannelInboundHandler#channelReadComplete(ChannelHandlerContext)}
     * event to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     */
    ChannelInboundInvoker fireChannelReadComplete();

    /**
     * Triggers an {@link ChannelInboundHandler#channelWritabilityChanged(ChannelHandlerContext)}
     * event to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     */
    ChannelInboundInvoker fireChannelWritabilityChanged();
}
