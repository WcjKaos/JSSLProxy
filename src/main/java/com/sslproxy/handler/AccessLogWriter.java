package com.sslproxy.handler;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 异步访问日志写入器
 * <p>
 * 使用独立线程和阻塞队列将日志异步写入文件，避免阻塞 Netty EventLoop。
 * </p>
 */
public class AccessLogWriter {
    private static final Logger logger = LoggerFactory.getLogger(AccessLogWriter.class);

    private final String filePath;
    private final int bufferSize;
    private final BlockingQueue<String> queue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread writerThread;
    private PrintWriter writer;

    public AccessLogWriter(String filePath, int bufferSize) {
        this.filePath = filePath;
        this.bufferSize = bufferSize;
        this.queue = new LinkedBlockingQueue<>(bufferSize);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            try {
                writer = new PrintWriter(new BufferedWriter(new FileWriter(filePath, true), bufferSize));
            } catch (IOException e) {
                logger.error("Failed to open access log file: {}", filePath, e);
                running.set(false);
                return;
            }

            writerThread = new Thread(this::writeLoop, "access-log-writer");
            writerThread.setDaemon(true);
            writerThread.start();
        }
    }

    public void stop() {
        running.set(false);
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (writer != null) {
            // 写入剩余日志
            String line;
            while ((line = queue.poll()) != null) {
                writer.println(line);
            }
            writer.close();
        }
    }

    public void write(String line) {
        if (!queue.offer(line)) {
            // 队列满了，丢弃日志（不阻塞 I/O 线程）
            logger.debug("Access log queue full, dropping log entry");
        }
    }

    private void writeLoop() {
        while (running.get()) {
            try {
                String line = queue.take();
                writer.println(line);

                // 批量刷新：每 100 条或队列空时刷新
                int count = 1;
                while (count < 100 && !queue.isEmpty()) {
                    line = queue.poll();
                    if (line != null) {
                        writer.println(line);
                        count++;
                    }
                }
                writer.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
