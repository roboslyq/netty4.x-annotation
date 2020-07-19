Thread[nioEventLoopGroup-2-1,10,main]







nioEventLoopGroup-3-1

nioEventLoopGroup-3-1

nioEventLoopGroup-3-2

0、每个NioEventLoop的构造函数十分重要

```java
 NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
        // 父类中指定了当前NioEventLoop对应的Executor
        super(parent, executor, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        // 在Windows环境下为：WindowsselectorProvider
        provider = selectorProvider;
        //  创建 NIO Selector 对象。
        final SelectorTuple selectorTuple = openSelector();
        selector = selectorTuple.selector;
        // 未包装的 Selector 对象
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }
```

> 此处我关注的是        final SelectorTuple selectorTuple = openSelector();
>
> 即每个NioEventLoop都有自己的Selector。因此 ，一个NioServerSocket可以有N个不同的Selector，可以将N个NioSocket注册到M个不同的Selector中。
>
> 此处关键是Selector只是一个选择器，我可以针对不同的Channel(NioSocketChannel或者NioServerSocketChannel)创建出不同的Selector。
>
> 在Netty中，每一个NioEventLoop都绑定了一个Selector

1、每1个NioEventLoop都在不停的while循环监听

2、每1个监听，都对应不同事的Selector

```
当前线程：Thread[nioEventLoopGroup-3-2,10,main]; 当前线程对应的selector：io.netty.channel.nio.SelectedSelectionKeySetSelector@3901a7ca; 当前线程对应的channel：1
当前线程：Thread[nioEventLoopGroup-2-1,10,main]; 当前线程对应的selector：io.netty.channel.nio.SelectedSelectionKeySetSelector@2eb9f64d; 当前线程对应的channel：1
当前线程：Thread[nioEventLoopGroup-3-1,10,main]; 当前线程对应的selector：io.netty.channel.nio.SelectedSelectionKeySetSelector@7d116ae9; 当前线程对应的channel：1

```

3、