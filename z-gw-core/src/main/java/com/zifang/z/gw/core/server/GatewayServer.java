package com.zifang.z.gw.core.server;

import com.zifang.z.gw.core.config.ServerConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Netty 网关服务器 — 启动/关闭 Netty server,装配 pipeline。
 *
 * <p>线程模型:
 * <ul>
 *   <li>bossGroup: 1 线程,接 accept</li>
 *   <li>workerGroup: CPU * 2 线程,处理 I/O</li>
 *   <li>业务阻塞操作通过 {@link com.zifang.z.gw.core.http.BackendHttpClient} 走独立 EventLoop</li>
 * </ul>
 */
public class GatewayServer {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final ServerConfig config;
    private final GatewayHandler gatewayHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean started = false;

    public GatewayServer(ServerConfig config, GatewayHandler gatewayHandler) {
        this.config = config;
        this.gatewayHandler = gatewayHandler;
    }

    public synchronized void start() throws InterruptedException {
        if (started) {
            log.warn("Gateway server already started");
            return;
        }
        log.info("Starting Z-GW Netty server on {}:{}", config.getHost(), config.getPort());

        boolean useEpoll = Epoll.isAvailable();
        if (useEpoll) {
            bossGroup = new EpollEventLoopGroup(config.getBossThreads(), new DefaultThreadFactory("zgw-boss"));
            workerGroup = new EpollEventLoopGroup(
                    config.getWorkerThreads() > 0 ? config.getWorkerThreads() : 0,
                    new DefaultThreadFactory("zgw-worker"));
        } else {
            bossGroup = new NioEventLoopGroup(config.getBossThreads(), new DefaultThreadFactory("zgw-boss"));
            workerGroup = new NioEventLoopGroup(
                    config.getWorkerThreads() > 0 ? config.getWorkerThreads() : 0,
                    new DefaultThreadFactory("zgw-worker"));
        }

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, config.getSoBacklog())
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, config.isTcpNodelay())
                .childOption(ChannelOption.SO_KEEPALIVE, config.isSoKeepalive())
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new ReadTimeoutHandler(60, TimeUnit.SECONDS))
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(config.getMaxContentLength()))
                                .addLast(new HttpServerExpectContinueHandler())
                                .addLast(gatewayHandler);
                    }
                });

        ChannelFuture f = b.bind(new InetSocketAddress(config.getHost(), config.getPort())).sync();
        serverChannel = f.channel();
        started = true;

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        log.info("Z-GW started successfully on {}:{}", config.getHost(), config.getPort());
    }

    public synchronized void shutdown() {
        if (!started) return;
        log.info("Shutting down Z-GW...");
        try {
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully().syncUninterruptibly();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().syncUninterruptibly();
            }
            started = false;
            log.info("Z-GW shutdown complete");
        } catch (Exception e) {
            log.error("Error during shutdown", e);
        }
    }

    public boolean isStarted() {
        return started;
    }

    public void awaitTermination() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.closeFuture().sync();
        }
    }
}
