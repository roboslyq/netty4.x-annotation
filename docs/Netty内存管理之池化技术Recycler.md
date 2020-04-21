# Netty内存池化技术之Recycler

Recycler是Netty具体池化技术的实现，基于FastThreadLocal相关技术。

## 使用Demo

### 定义业务对象

定义一个需要池化的具体对象 

```java
   /**
     * 普通的POJO: POJO中包含有Handle对象。
     */
    static final class HandledObject {

        Recycler.Handle<HandledObject> handle;

        HandledObject(Recycler.Handle<HandledObject> handle) {
            this.handle = handle;
        }

        void recycle() {
            handle.recycle(this);
        }
    }
```

> 此对象需要包含具体的Handle。

### 获取Recycler实例

```java
/**
     * 创建一个Recycler对象实例，因为Recycler本身是抽象的，因此需要此方式实现例化。
     * @param max
     * @return
     */
    private static Recycler<HandledObject> newRecycler(int max) {
        return new Recycler<HandledObject>(max) {
            //如果当前缓冲池为空，训实例化一个新对象
            @Override
            protected HandledObject newObject(
                    Recycler.Handle<HandledObject> handle) {
                return new HandledObject(handle);
            }
        };
    }
```

> Recycler本身是一个抽象类不能直接实例化，我们需要实现其抽象方法`newObject()`

### 测试

```java
@Test(expected = IllegalStateException.class)
public void testMultipleRecycle() {
        Recycler<HandledObject> recycler = newRecycler(1024);
        HandledObject object = recycler.get();
        /*
         * 此处注意,直接调用的是HandleObject中的recycle方法,即Handle#recycle()方法.
         * 此方法能完成不同线程间的对象回收.
         */
        object.recycle();
        object.recycle();
    }
```

> Handle#recycle()方法只能被回收一次，重复回收会抛出`IllegalStateException`异常。

