/*
 * Copyright 2017 The Netty Project
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
package io.netty.buffer;

import io.netty.util.internal.PlatformDependent;
import org.junit.Test;

public class UnpooledByteBufAllocatorTest extends AbstractByteBufAllocatorTest<UnpooledByteBufAllocator> {
    /**
     * copy form PpooledByteBufAllocatorTest
     */
    @Test
    public void testPooledUnsafeHeapBufferAndUnsafeDirectBuffer() {
        UnpooledByteBufAllocator allocator = newAllocator(true);
        ByteBuf directBuffer = allocator.directBuffer();
        directBuffer.writeChar('h');
        directBuffer.writeChar('e');
        directBuffer.writeChar('l');
        directBuffer.writeChar('l');
        directBuffer.writeChar('o');
        while (directBuffer.isReadable()){

            System.out.print(directBuffer.readChar());
        }
        assertInstanceOf(directBuffer,
                PlatformDependent.hasUnsafe() ? PooledUnsafeDirectByteBuf.class : PooledDirectByteBuf.class);
        directBuffer.release();

        ByteBuf heapBuffer = allocator.heapBuffer();
        assertInstanceOf(heapBuffer,
                PlatformDependent.hasUnsafe() ? PooledUnsafeHeapByteBuf.class : PooledHeapByteBuf.class);
        heapBuffer.release();
    }


    @Override
    protected UnpooledByteBufAllocator newAllocator(boolean preferDirect) {
        return new UnpooledByteBufAllocator(preferDirect);
    }

    @Override
    protected UnpooledByteBufAllocator newUnpooledAllocator() {
        return new UnpooledByteBufAllocator(false);
    }
}
