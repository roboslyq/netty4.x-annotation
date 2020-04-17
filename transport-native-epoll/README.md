# Native transport for Linux

> See [our wiki page](http://netty.io/wiki/native-transports.html).

## 介绍
> Netty官方提供了使用本地库的说明 Native transports
  Netty是通过JNI本地库的方式来提供的。而且这种本地库的方式不是Netty核心的一部分，所以需要有额外依赖
　　Netty Native用C++编写JNI调用的Socket Transport，与JDK的NIO相比，GC更少，性能更高。
Netty Native高性能

> Netty的 epoll transport使用 edge-triggered 而 JDK NIO 使用 level-triggered；
> 更少GC，更少synchronized；
> 暴露了更多的Socket配置参数，见EpollChannelOption；

注意： Netty Native跟OS相关且基于GLIBC2.10编译，目前只支持Linux (since 4.0.16)和MacOS/BSD (since 4.1.11)，建议根据System.getProperty(“os.name”)和System.getProperty(“os.version”)来判断。
Netty Native使用。

