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
package io.netty.handler.codec;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.util.List;

/**
 * {@link ChannelInboundHandlerAdapter} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 * 解码器，自定义协议可以实现此接口
 * For example here is an implementation which reads all readable bytes from
 * the input {@link ByteBuf} and create a new {@link ByteBuf}.
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in, List&lt;Object&gt; out)
 *                 throws {@link Exception} {
 *             out.add(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * <h3>Frame detection</h3>
 * <p>
 * Generally frame detection should be handled earlier in the pipeline by adding a
 * {@link DelimiterBasedFrameDecoder}, {@link FixedLengthFrameDecoder}, {@link LengthFieldBasedFrameDecoder},
 * or {@link LineBasedFrameDecoder}.
 * <p>
 * If a custom frame decoder is required, then one needs to be careful when implementing
 * one with {@link ByteToMessageDecoder}. Ensure there are enough bytes in the buffer for a
 * complete frame by checking {@link ByteBuf#readableBytes()}. If there are not enough bytes
 * for a complete frame, return without modifying the reader index to allow more bytes to arrive.
 * <p>
 * To check for complete frames without modifying the reader index, use methods like {@link ByteBuf#getInt(int)}.
 * One <strong>MUST</strong> use the reader index when using methods like {@link ByteBuf#getInt(int)}.
 * For example calling <tt>in.getInt(0)</tt> is assuming the frame starts at the beginning of the buffer, which
 * is not always the case. Use <tt>in.getInt(in.readerIndex())</tt> instead.
 * <h3>Pitfalls</h3>
 * <p>
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong>
 * annotated with {@link @Sharable}.
 * <p>
 * Some methods such as {@link ByteBuf#readBytes(int)} will cause a memory leak if the returned buffer
 * is not released or added to the <tt>out</tt> {@link List}. Use derived buffers like {@link ByteBuf#readSlice(int)}
 * to avoid leaking memory.
 */
/*
 *  入口为channelReads
 * 步骤一：把当前的ByteBuf与上次未处理的ByteBuf合并(每一个TCP连接是独立的，因此，每一个连接都有自己的ByteToMessageDecoder
 * 所以，不需要考虑不同客户端与服务端之前连接。
 * TCP的拆包，粘包是针对同一个连接而言。同一连接，对应的相应的Handler也都是私有的，不需要共享 。
 * 如果共享,在构造函数中的方法 ensureNotSharable()会抛出异常
 * )
 *
 */
public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {

    /**
     * Cumulate：累加
     * Cumulate {@link ByteBuf}s by merge them into one {@link ByteBuf}'s, using memory copies.
     * 使用内存copy技术，将多个ByteBuf合并为一个ByteBuf
     */
    public static final Cumulator MERGE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            try {
                final ByteBuf buffer;
                // 判断容量是否足够，如果不够，则进行扩容
                if (cumulation.writerIndex() > cumulation.maxCapacity() - in.readableBytes()
                    || cumulation.refCnt() > 1 || cumulation.isReadOnly()) {
                    // Expand cumulation (by replace it) when either there is not more room in the buffer
                    // or if the refCnt is greater then 1 which may happen when the user use slice().retain() or
                    // duplicate().retain() or if its read-only.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/2327
                    // - https://github.com/netty/netty/issues/1764
                    // 扩容
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                } else {
                    buffer = cumulation;
                }
                // 写入数据
                buffer.writeBytes(in);
                return buffer;
            } finally {
                // We must release in in all cases as otherwise it may produce a leak if writeBytes(...) throw
                // for whatever release (for example because of OutOfMemoryError)
                in.release();
            }
        }
    };

    /**
     * Cumulate {@link ByteBuf}s by add them to a {@link CompositeByteBuf} and so do no memory copy whenever possible.
     * Be aware that {@link CompositeByteBuf} use a more complex indexing implementation so depending on your use-case
     * and the decoder implementation this may be slower then just use the {@link #MERGE_CUMULATOR}.
     * 组合累加器，将多个ByteBuf组合成一个CompositeByteBuf
     */
    public static final Cumulator COMPOSITE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            ByteBuf buffer;
            try {
                if (cumulation.refCnt() > 1) {
                    // Expand cumulation (by replace it) when the refCnt is greater then 1 which may happen when the
                    // user use slice().retain() or duplicate().retain().
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/2327
                    // - https://github.com/netty/netty/issues/1764
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                    buffer.writeBytes(in);
                } else {
                    CompositeByteBuf composite;
                    if (cumulation instanceof CompositeByteBuf) {
                        composite = (CompositeByteBuf) cumulation;
                    } else {
                        composite = alloc.compositeBuffer(Integer.MAX_VALUE);
                        composite.addComponent(true, cumulation);
                    }
                    composite.addComponent(true, in);
                    in = null;
                    buffer = composite;
                }
                return buffer;
            } finally {
                if (in != null) {
                    // We must release if the ownership was not transferred as otherwise it may produce a leak if
                    // writeBytes(...) throw for whatever release (for example because of OutOfMemoryError).
                    in.release();
                }
            }
        }
    };

    private static final byte STATE_INIT = 0;
    private static final byte STATE_CALLING_CHILD_DECODE = 1;
    private static final byte STATE_HANDLER_REMOVED_PENDING = 2;
    /**
     * 用来保存累计读取到的字节（相当于容器，每一个Channel都有自己的容器，因此，不同客户端的TCP请求，互不干扰）
     * */
    ByteBuf cumulation;
    /**
     * 累计器，把从channel获取到的字节累计起来。主要有两种实现。默认是MERGE_CUMULATOR
     */
    private Cumulator cumulator = MERGE_CUMULATOR;
    private boolean singleDecode;
    private boolean decodeWasNull;
    private boolean first;
    /**
     * A bitmask where the bits are defined as
     * <ul>
     *     <li>{@link #STATE_INIT}</li>
     *     <li>{@link #STATE_CALLING_CHILD_DECODE}</li>
     *     <li>{@link #STATE_HANDLER_REMOVED_PENDING}</li>
     * </ul>
     */
    private byte decodeState = STATE_INIT;
    // 默认读16次，如果读16次还未完成，则丢弃
    private int discardAfterReads = 16;
    // 已读次数
    private int numReads;
    // 构造函数
    protected ByteToMessageDecoder() {
        // 注意: 这个解码器一定是独立的,不可共享的,如果共享则抛出异常
        ensureNotSharable();
    }

    /**
     * If set then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)}
     * call. This may be useful if you need to do some protocol upgrade and want to make sure nothing is mixed up.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }

    /**
     * If {@code true} then only one message is decoded on each
     * {@link #channelRead(ChannelHandlerContext, Object)} call.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public boolean isSingleDecode() {
        return singleDecode;
    }

    /**
     * Set the {@link Cumulator} to use for cumulate the received {@link ByteBuf}s.
     */
    public void setCumulator(Cumulator cumulator) {
        if (cumulator == null) {
            throw new NullPointerException("cumulator");
        }
        this.cumulator = cumulator;
    }

    /**
     * Set the number of reads after which {@link ByteBuf#discardSomeReadBytes()} are called and so free up memory.
     * The default is {@code 16}.
     */
    public void setDiscardAfterReads(int discardAfterReads) {
        checkPositive(discardAfterReads, "discardAfterReads");
        this.discardAfterReads = discardAfterReads;
    }

    /**
     * Returns the actual number of readable bytes in the internal cumulative
     * buffer of this decoder. You usually do not need to rely on this value
     * to write a decoder. Use it only when you must use it at your own risk.
     * This method is a shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }

    /**
     * Returns the internal cumulative buffer of this decoder. You usually
     * do not need to access the internal buffer directly to write a decoder.
     * Use it only when you must use it at your own risk.
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        if (decodeState == STATE_CALLING_CHILD_DECODE) {
            decodeState = STATE_HANDLER_REMOVED_PENDING;
            return;
        }
        ByteBuf buf = cumulation;
        if (buf != null) {
            // Directly set this to null so we are sure we not access it in any other method here anymore.
            cumulation = null;
            numReads = 0;
            int readable = buf.readableBytes();
            if (readable > 0) {
                ByteBuf bytes = buf.readBytes(readable);
                buf.release();
                ctx.fireChannelRead(bytes);
                ctx.fireChannelReadComplete();
            } else {
                buf.release();
            }
        }
        handlerRemoved0(ctx);
    }

    /**
     * Gets called after the {@link ByteToMessageDecoder} was removed from the actual context and it doesn't handle
     * events anymore.
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception { }

    /**
     * ========>入口方法：读信息,每次从TCP缓冲区读到数据都会调用的方法
     * 触发点在AbstractNioByteChannel的read方法中，里面有个while循环不断读取，读取到一次就触发一次channelRead
     * 1.累加数据
     * 2.将累加到的数据传递给业务进行业务拆包
     * 3.清理字节容器
     * 4.传递业务数据包给业务解码器处理
     * @param ctx
     * @param msg 可能为ByteBuf或者NioChannelSocket
     * @throws Exception
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            // 从对象池中取出一个List<Object>：此处也使用了对象池技术
            CodecOutputList out = CodecOutputList.newInstance();
            try {
                // 步骤一：1.累加数据:把当前的ByteBuf与上次未处理的ByteBuf合并。
                ByteBuf data = (ByteBuf) msg;
                first = cumulation == null;
                // 判断是不是第一次读取数据
                if (first) {
                    // 是第一次就直接赋值,将cumulation直接指向data即可
                    cumulation = data;
                } else {
                    // 不是第一次就调用累加器，进行累加.此时,cumulation中的数据是完全的.
                    cumulation = cumulator.cumulate(ctx.alloc(), cumulation, data);
                }
                // 步骤二：将累加到的数据传递给业务进行拆包，处理子类decode后添加到List中的数据。
                // 此时字节容器里的数据已是目前未拆包部分的所有的数据
                callDecode(ctx, cumulation, out);
            } catch (DecoderException e) {
                throw e;
            } catch (Exception e) {
                throw new DecoderException(e);
            } finally {
                //当存在数据且不可读，说明已经读完了，就重置状态，释放空间
                if (cumulation != null && !cumulation.isReadable()) {
                    numReads = 0;
                    cumulation.release();
                    cumulation = null;
                }
                //判断反复累加次数是不是大于设置的discardAfterReads，当达到了discardAfterReads，就要去丢弃一些数据，防止OOM
                else if (++ numReads >= discardAfterReads) {
                    // We did enough reads already try to discard some bytes so we not risk to see a OOME.
                    // See https://github.com/netty/netty/issues/4275
                    numReads = 0;
                    discardSomeReadBytes();
                }

                int size = out.size();
                decodeWasNull = !out.insertSinceRecycled();
                //  步骤三：传递业务数据包给业务解码器处理: 触发渠道的channelRead事件，size是触发多少次事件。
                fireChannelRead(ctx, out, size);
                //回收解码结果
                out.recycle();
            }
        } else {
            //如果是其它(例如NioSocketChannel),则直接交给下一个Handle处理
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Get {@code numElements} out of the {@link List} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, List<Object> msgs, int numElements) {
        if (msgs instanceof CodecOutputList) {
            fireChannelRead(ctx, (CodecOutputList) msgs, numElements);
        } else {
            for (int i = 0; i < numElements; i++) {
                ctx.fireChannelRead(msgs.get(i));
            }
        }
    }

    /**
     * Get {@code numElements} out of the {@link CodecOutputList} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, CodecOutputList msgs, int numElements) {
        for (int i = 0; i < numElements; i ++) {
            ctx.fireChannelRead(msgs.getUnsafe(i));
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        numReads = 0;
        discardSomeReadBytes();
        if (decodeWasNull) {
            decodeWasNull = false;
            if (!ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
        }
        ctx.fireChannelReadComplete();
    }

    protected final void discardSomeReadBytes() {
        if (cumulation != null && !first && cumulation.refCnt() == 1) {
            // discard some bytes if possible to make more room in the
            // buffer but only if the refCnt == 1  as otherwise the user may have
            // used slice().retain() or duplicate().retain().
            //
            // See:
            // - https://github.com/netty/netty/issues/2327
            // - https://github.com/netty/netty/issues/1764
            cumulation.discardSomeReadBytes();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelInputClosed(ctx, true);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            // The decodeLast method is invoked when a channelInactive event is encountered.
            // This method is responsible for ending requests in some situations and must be called
            // when the input has been shutdown.
            channelInputClosed(ctx, false);
        }
        super.userEventTriggered(ctx, evt);
    }

    private void channelInputClosed(ChannelHandlerContext ctx, boolean callChannelInactive) throws Exception {
        CodecOutputList out = CodecOutputList.newInstance();
        try {
            channelInputClosed(ctx, out);
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            try {
                if (cumulation != null) {
                    cumulation.release();
                    cumulation = null;
                }
                int size = out.size();
                fireChannelRead(ctx, out, size);
                if (size > 0) {
                    // Something was read, call fireChannelReadComplete()
                    ctx.fireChannelReadComplete();
                }
                if (callChannelInactive) {
                    ctx.fireChannelInactive();
                }
            } finally {
                // Recycle in all cases
                out.recycle();
            }
        }
    }

    /**
     * Called when the input of the channel was closed which may be because it changed to inactive or because of
     * {@link ChannelInputShutdownEvent}.
     */
    void channelInputClosed(ChannelHandlerContext ctx, List<Object> out) throws Exception {
        if (cumulation != null) {
            callDecode(ctx, cumulation, out);
            decodeLast(ctx, cumulation, out);
        } else {
            decodeLast(ctx, Unpooled.EMPTY_BUFFER, out);
        }
    }

    /**
     * Called once data should be decoded from the given {@link ByteBuf}. This method will call
     * {@link #decode(ChannelHandlerContext, ByteBuf, List)} as long as decoding should take place.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     */
    // 步骤二：处理子类decode后添加到List中的数据：
    // 将尝试将字节容器的数据拆分成业务数据包塞到业务数据容器out中
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            // 如果累计区还有可读字节，循环解码，因为这里in有可能是粘包，即多次完整的数据包粘在一起，
            // 通过换行符连接。
            // 下面的decode方法只能处理一个完整的数据包，所以这里循环处理粘包
            // 循环调用：只要in可读
            while (in.isReadable()) {
                // 调用子类的decode方法前处理List了，因为是在一个while循环里
                int outSize = out.size();
                // 上次循环成功解码：表示已经处理过数据，不是第1次读，先将旧数据处理掉
                if (outSize > 0) {
                    // 触发channelRead事件，处理已经解析成功的数据
                    fireChannelRead(ctx, out, outSize);
                    out.clear();
                    // Check if this handler was removed before continuing with decoding.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/4635
                    if (ctx.isRemoved()) {
                        break;
                    }
                    // 重置为0
                    outSize = 0;
                }
                // ByteBuf中可读取的数据字节数，即当前TCP已经接收到的字节数
                int oldInputLength = in.readableBytes();
                // 调用子类具体的decode实现
                decodeRemovalReentryProtection(ctx, in, out);

                // Check if this handler was removed before continuing the loop.
                // If it was removed, it is not safe to continue to operate on the buffer.
                //
                // See https://github.com/netty/netty/issues/1664
                if (ctx.isRemoved()) {
                    break;
                }
                // 此时outSize等于0，表明没有读取到数据，因为如果不为0，会被处理掉。
                // 正常情况应该不相等，因为outSize此时为0，如果有解析出来的报文（没有半包数据），那么out.size()应该大于0
                if (outSize == out.size()) {
                    // 上面赋值语句为：int oldInputLength = in.readableBytes();
                    // 如果oldInputLength == in.readableBytes()，表明是一个半包数据，当前解码器还未读取任何数据。
                    // 因此不需要下一次循环，等待下一次可读事件读取新的数据。
                    if (oldInputLength == in.readableBytes()) {
                        break;
                    } else {
                        // 否则证明已经读取到了数据，继承处理out
                        continue;
                    }
                }
                // 能进入到这里，表明已经读取到了数据，已经读取到了数据，oldInputLength == in.readableBytes()不应该相等。
                // 因为读取了数据，readableBytes肯定会减小
                // 当oldInputLength == in.readableBytes()时，表明没有读取到任务数据，但是又读取到了数据，表示出现了异常。
                // 因为如果没读取到数据，会进入上面的 if (outSize == out.size())循环。
                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(
                            StringUtil.simpleClassName(getClass()) +
                                    ".decode() did not read anything but decoded a message.");
                }
                // 如果是“单次”模型，则中断，否则进入下一次循环
                if (isSingleDecode()) {
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception cause) {
            throw new DecoderException(cause);
        }
    }

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error occurs
     */
    // 抽象方法，供子类实现
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error occurs
     */
    /** 调用子类decode实现*/
    final void decodeRemovalReentryProtection(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
            throws Exception {
        decodeState = STATE_CALLING_CHILD_DECODE;
        try {
            // 调用子类decode
            decode(ctx, in, out);
        } finally {
            boolean removePending = decodeState == STATE_HANDLER_REMOVED_PENDING;
            decodeState = STATE_INIT;
            if (removePending) {
                handlerRemoved(ctx);
            }
        }
    }

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active. Which means the
     * {@link #channelInactive(ChannelHandlerContext)} was triggered.
     *
     * By default this will just call {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may
     * override this for some special cleanup operation.
     */
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.isReadable()) {
            // Only call decode() if there is something left in the buffer to decode.
            // See https://github.com/netty/netty/issues/4386
            decodeRemovalReentryProtection(ctx, in, out);
        }
    }

    /**
     * 扩容也是一个内存拷贝操作，新增的大小即是新读取数据的大小
     * @param alloc
     * @param cumulation
     * @param readable
     * @return
     */
    static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf cumulation, int readable) {
        ByteBuf oldCumulation = cumulation;
        cumulation = alloc.buffer(oldCumulation.readableBytes() + readable);
        cumulation.writeBytes(oldCumulation);
        oldCumulation.release();
        return cumulation;
    }

    /**
     * 用于累计字节，该接口有两个实现，默认使用的是MERGE_CUMULATOR
     * Cumulate {@link ByteBuf}s.
     */
    public interface Cumulator {
        /**
         * Cumulate the given {@link ByteBuf}s and return the {@link ByteBuf} that holds the cumulated bytes.
         * The implementation is responsible to correctly handle the life-cycle of the given {@link ByteBuf}s and so
         * call {@link ByteBuf#release()} if a {@link ByteBuf} is fully consumed.
         */
        //  in 参数表示新读取到的数据，
        ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in);
    }
}
