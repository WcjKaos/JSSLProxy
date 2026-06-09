# Netty 编程规则

## 核心原则：EventLoop 线程不可阻塞

### 禁止在 EventLoop 中执行的操作
- NEVER 调用阻塞 I/O（文件读写、数据库查询、HTTP 请求等）
- NEVER 使用 `Thread.sleep()` / `Object.wait()` / `CountDownLatch.await()`
- NEVER 调用 `ChannelFuture.sync()` 或 `Future.get()` — 使用 `addListener()` 代替
- NEVER 在 Handler 中持有锁超过微秒级别

违反以上规则会导致整个 EventLoop 组（NIO 线程）卡死，影响所有连接。

### 正确的异步模式

```java
// WRONG: 阻塞等待
Channel ch = bootstrap.connect(host, port).sync().channel();

// RIGHT: 异步回调
bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
    if (f.isSuccess()) {
        Channel ch = f.channel();
        // ...
    } else {
        // 处理连接失败
    }
});
```

### 跨线程操作 Channel
如果需要从非 EventLoop 线程操作 Channel，使用 `channel.eventLoop().execute()`:

```java
channel.eventLoop().execute(() -> {
    // 在 EventLoop 线程中安全执行
    channel.writeAndFlush(message);
});
```

---

## ByteBuf 引用计数

Netty 的 ByteBuf 使用引用计数管理内存。引用计数错误会导致内存泄漏或 double-free 崩溃。

### 基本规则

| 场景 | 操作 |
|------|------|
| 将 ByteBuf 传给异步操作（跨越当前方法栈）前 | `buf.retain()` |
| 异步操作成功完成后 | Netty 自动释放（writeAndFlush 消费一次引用） |
| 异步操作**失败**时 | 必须手动 `buf.release()` |
| `SimpleChannelInboundHandler` 的 `channelRead0` 中 | 框架自动释放，除非提前 retain() |

### 高危代码模式

```java
// WRONG: retain 之后连接失败，未 release — 内存泄漏
request.retain();
connectFuture.addListener(f -> {
    if (f.isSuccess()) {
        forward(request); // writeAndFlush 会消费引用
    }
    // BUG: 失败路径没有 release()
});

// RIGHT: 失败路径必须 release
request.retain();
connectFuture.addListener(f -> {
    if (f.isSuccess()) {
        forward(request);
    } else {
        request.release(); // 必须
        sendErrorResponse(ctx);
    }
});
```

### 引用计数审查清单
代码审查时对所有 Handler 检查：
- [ ] 每条 `retain()` 都有对应的 `release()` 或 `writeAndFlush()`
- [ ] 异常路径（catch/finally）是否释放了 ByteBuf
- [ ] `SimpleChannelInboundHandler` 中是否有多余的 `release()`

---

## Handler 设计规则

### @ChannelHandler.Sharable
- 只有**无状态** Handler 才能标注 `@Sharable`（多 Channel 共享同一实例）
- 有成员变量的 Handler **不能**是 Sharable
- 本项目中 `MaxConnectionsHandler` 是 Sharable（使用原子计数器）；`ProxyFrontendHandler` **不是**（有 `outboundChannel` 等状态）

### Pipeline 修改
- Pipeline 修改（`addLast`/`remove`）可以从任意线程调用，Netty 会保证线程安全
- Pipeline 的 Handler 顺序在 `ServerInitializer.initChannel()` 中定义 — 修改时参考 CLAUDE.md 中的 Pipeline 顺序注释
- NEVER 在请求处理过程中动态修改 Pipeline（除非明确需要，如 SSL 握手完成后移除 SslHandler）

### Pipeline 顺序（本项目）
```
MaxConnectionsHandler → IdleStateHandler → SslHandler → SSLAuthHandler
→ HttpServerCodec → HttpObjectAggregator → RequestIdHandler
→ MetricsCollectorHandler → RateLimitHandler → AccessLogHandler
→ ProxyFrontendHandler
```
Handler 顺序错误会导致安全漏洞（如绕过认证）或功能异常。

---

## 背压（Backpressure）

- 当写缓冲区满时，`channel.isWritable()` 返回 false
- 实现 `channelWritabilityChanged()` 并调用 `outboundChannel.config().setAutoRead(false)` 暂停读取
- 恢复可写时重新 `setAutoRead(true)`
- 本项目 `ProxyFrontendHandler` 已实现此机制 — 修改时不能破坏

---

## 连接关闭

```java
// 确保所有 pending 数据发出后再关闭
ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);

// 已关闭的 Channel 直接 close
ctx.close();
```

- 关闭前端连接时，必须同时关闭或释放后端连接（`closeBackendChannel()`）
- 使用连接池时，`release()` 而不是 `close()` 归还连接
