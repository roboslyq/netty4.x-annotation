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
package io.netty.util;

/**
 * 针对需要手动关闭的资源对象追踪，心免内存泄露
 *   原理 ：该接口提供了一个release方法。在资源对象关闭需要调用release方法。如果从未调用release方法则被认为存在资源泄露。
 * 默认实现：该接口只有一个实现，就是io.netty.util.ResourceLeakDetector.DefaultResourceLeak，该实现继承了WeakReference。每一个DefaultResourceLeak会与一个需要监控的资源对象关联，同时关联着一个引用队列。
 * @param <T>
 */
public interface ResourceLeakTracker<T>  {

    /**
     * Records the caller's current stack trace so that the {@link ResourceLeakDetector} can tell where the leaked
     * resource was accessed lastly. This method is a shortcut to {@link #record(Object) record(null)}.
     */
    void record();

    /**
     * Records the caller's current stack trace and the specified additional arbitrary information
     * so that the {@link ResourceLeakDetector} can tell where the leaked resource was accessed lastly.
     */
    void record(Object hint);

    /**
     * Close the leak so that {@link ResourceLeakTracker} does not warn about leaked resources.
     * After this method is called a leak associated with this ResourceLeakTracker should not be reported.
     *
     * @return {@code true} if called first time, {@code false} if called already
     */
    boolean close(T trackedObject);
}
