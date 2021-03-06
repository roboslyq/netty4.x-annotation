/*
 * Copyright 2013 The Netty Project
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
package io.netty.util;

/**
 * A reference-counted object that requires explicit deallocation.
 * 引用计数相关的接，当实现了ReferenceCounted接口的类就具备了引用计数的能力。
 * <p>
 * When a new {@link ReferenceCounted} is instantiated, it starts with the reference count of {@code 1}.
 * {@link #retain()} increases the reference count, and {@link #release()} decreases the reference count.
 * If the reference count is decreased to {@code 0}, the object will be deallocated explicitly, and accessing
 * the deallocated object will usually result in an access violation.
 * </p>
 * 当一个引用计数对象初始化时,计数器count = 1。
 * <p>
 * If an object that implements {@link ReferenceCounted} is a container of other objects that implement
 * {@link ReferenceCounted}, the contained objects will also be released via {@link #release()} when the container's
 * reference count becomes 0.
 * </p>
 * 被引用计数包含的对象，能够显示的被垃圾回收。
 * 当初始化的时候，计数为1。retain（）方法能够增加计数，release() 方法能够减少计数，如果计数被减少到0则对象会被显示回收，再次访问被回收的这些对象将会抛出异常。
 * 如果一个对象实现了ReferenceCounted，并且包含有其他对象也实现来ReferenceCounted，
 * 当这个对象计数为0被回收的时候，所包含的对象同样会通过release()释放掉。
 */
public interface ReferenceCounted {
    /**
     * Returns the reference count of this object.  If {@code 0}, it means this object has been deallocated.
     * 引用数量
     */
    int refCnt();

    /**
     * Increases the reference count by {@code 1}.
     * retain()方法对引用计数加1
     */
    ReferenceCounted retain();

    /**
     * Increases the reference count by the specified {@code increment}.
     * retain()方法对引用计数加 increment
     */
    ReferenceCounted retain(int increment);

    /**
     * Records the current access location of this object for debugging purposes.
     * If this object is determined to be leaked, the information recorded by this operation will be provided to you
     * via {@link ResourceLeakDetector}.  This method is a shortcut to {@link #touch(Object) touch(null)}.
     * 等价于touch(null)
     */
    ReferenceCounted touch();

    /**
     * Records the current access location of this object with an additional arbitrary information for debugging
     * purposes.  If this object is determined to be leaked, the information recorded by this operation will be
     * provided to you via {@link ResourceLeakDetector}.
     * 调试用途：
     *     出于调试目的,用一个额外的任意的(arbitrary)信息记录这个对象的当前访问地址. 如果这个对象被检测到泄露了,
     *     这个操作记录的信息将通过ResourceLeakDetector提供.
     */
    ReferenceCounted touch(Object hint);

    /**
     * Decreases the reference count by {@code 1} and deallocates this object if the reference count reaches at
     * {@code 0}.
     *
     * @return {@code true} if and only if the reference count became {@code 0} and this object has been deallocated
     *  release()方法对引用计数减1。如果引用计数被减到0，则这个对象就将被显式的回收，此时再来访问该对象则会抛出IllegalReferenceCountException异常。
     */
    boolean release();

    /**
     * Decreases the reference count by the specified {@code decrement} and deallocates this object if the reference
     * count reaches at {@code 0}.
     * 引用计数减 decrement
     * @return {@code true} if and only if the reference count became {@code 0} and this object has been deallocated
     */
    boolean release(int decrement);
}
