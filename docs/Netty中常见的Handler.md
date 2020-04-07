- SslHandler
  - ·负责对请求进行加密和解密，是放在ChannelPipeline中的第一个ChannelHandler

- HttpClientCodec 和 HttpServerCodec

  - HttpClientCodec负责将请求字节解码为HttpRequest、HttpContent和LastHttpContent消息，以及对应的转为字节；HttpServerCodec负责服务端中将字节码解析成HttpResponse、HttpContent和LastHttpContent消息，以及对应的将它转为字节

    > HttpServerCodec 里面组合了HttpResponseEncoder和HttpRequestDecoder
    >
    > HttpClientCodec 里面组合了HttpRequestEncoder和HttpResponseDecoder

- HttpObjectAggregator
  - 负责将http聚合成完整的消息，而不是原始的多个部分
- HttpContentCompressor和HttpContentDecompressor
  - HttpContentCompressor用于服务器压缩数据，HttpContentDecompressor用于客户端解压数据
- IdleStateHandler
  - 连接空闲时间过长，触发IdleStateEvent事件
- ReadTimeoutHandler
  - 指定时间内没有收到任何的入站数据，抛出ReadTimeoutException异常,并关闭channel
- WriteTimeoutHandler
  - 指定时间内没有任何出站数据写入，抛出WriteTimeoutException异常，并关闭channel
- DelimiterBasedFrameDecoder
  - 使用任何用户提供的分隔符来提取帧的通用解码器
- FixedLengthFrameDecoder
  - 提取在调用构造函数时的定长帧
  -  FileRegion 
- ChunkedWriteHandler
  - 将大型文件从文件系统复制到内存【DefaultFileRegion进行大型文件传输】
-  LengthFieldBasedFrameDecoder
  - 自定义长度解码器 
-  LineBasedFrameDecoder 
-   Marshalling 
  -  序列化 JBoss Marshalling
-  protobuf 
  -  序列化 JBoss Google Protobuf
- WebSocketClientProtocolHandler
- WebSocketServerProtocolHandler