package com.sslproxy.management.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the static admin UI shell from classpath resources.
 */
public class AdminUiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String UI_PREFIX = "/admin/ui";
    private static final String RESOURCE_ROOT = "admin-ui/";
    private static final String CSP = "default-src 'none'; "
            + "connect-src 'self' http://localhost:* https://localhost:* "
            + "http://127.0.0.1:* https://127.0.0.1:* http://[::1]:* https://[::1]:*; "
            + "script-src 'self'; style-src 'self'; img-src 'self'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = new QueryStringDecoder(request.uri()).path();
        if (!isUiPath(path)) {
            ctx.fireChannelRead(request.retain());
            return;
        }

        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            sendText(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method Not Allowed");
            return;
        }

        Resource resource = resolveResource(path);
        if (resource == null) {
            sendText(ctx, HttpResponseStatus.NOT_FOUND, "Not Found");
            return;
        }

        byte[] bytes;
        try {
            bytes = readResource(resource.fileName());
        } catch (IOException e) {
            sendText(ctx, HttpResponseStatus.NOT_FOUND, "Not Found");
            return;
        }

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                request.method() == HttpMethod.HEAD ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(bytes)
        );
        setCommonHeaders(response, resource.contentType());
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                request.method() == HttpMethod.HEAD ? 0 : bytes.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static boolean isUiPath(String path) {
        return UI_PREFIX.equals(path) || path.startsWith(UI_PREFIX + "/");
    }

    private static Resource resolveResource(String path) {
        if (UI_PREFIX.equals(path) || (UI_PREFIX + "/").equals(path)) {
            return new Resource("index.html", "text/html; charset=UTF-8");
        }

        String name = path.substring((UI_PREFIX + "/").length());
        return switch (name) {
            case "styles.css" -> new Resource("styles.css", "text/css; charset=UTF-8");
            case "app.js" -> new Resource("app.js", "application/javascript; charset=UTF-8");
            default -> null;
        };
    }

    private static byte[] readResource(String fileName) throws IOException {
        String resourcePath = RESOURCE_ROOT + fileName;
        try (InputStream in = AdminUiHandler.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing resource: " + resourcePath);
            }
            return in.readAllBytes();
        }
    }

    private static void sendText(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(bytes)
        );
        setCommonHeaders(response, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void setCommonHeaders(FullHttpResponse response, String contentType) {
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        response.headers().set("Content-Security-Policy", CSP);
        response.headers().set("X-Content-Type-Options", "nosniff");
        response.headers().set("Referrer-Policy", "no-referrer");
        response.headers().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private record Resource(String fileName, String contentType) {
    }
}
