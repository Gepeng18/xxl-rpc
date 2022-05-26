package com.xxl.rpc.core.remoting.net.impl.netty.server;

import com.xxl.rpc.core.remoting.net.Server;
import com.xxl.rpc.core.remoting.net.impl.netty.codec.NettyDecoder;
import com.xxl.rpc.core.remoting.net.impl.netty.codec.NettyEncoder;
import com.xxl.rpc.core.remoting.net.params.Beat;
import com.xxl.rpc.core.remoting.net.params.XxlRpcRequest;
import com.xxl.rpc.core.remoting.net.params.XxlRpcResponse;
import com.xxl.rpc.core.remoting.provider.XxlRpcProviderFactory;
import com.xxl.rpc.core.util.ThreadPoolUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * netty rpc server
 *
 * @author xuxueli 2015-10-29 18:17:14
 */
public class NettyServer extends Server {

    private Thread thread;

    @Override
    public void start(final XxlRpcProviderFactory xxlRpcProviderFactory) throws Exception {

        thread = new Thread(new Runnable() {
            @Override
            public void run() {

                // param
                final ThreadPoolExecutor serverHandlerPool = ThreadPoolUtil.makeServerThreadPool(
                        NettyServer.class.getSimpleName(),
                        xxlRpcProviderFactory.getCorePoolSize(),
                        xxlRpcProviderFactory.getMaxPoolSize());
                EventLoopGroup bossGroup = new NioEventLoopGroup();
                EventLoopGroup workerGroup = new NioEventLoopGroup();

                try {
                    // start server
                    ServerBootstrap bootstrap = new ServerBootstrap();
                    bootstrap.group(bossGroup, workerGroup)
                            .channel(NioServerSocketChannel.class)
                            .childHandler(new ChannelInitializer<SocketChannel>() {
                                @Override
                                public void initChannel(SocketChannel channel) throws Exception {
                                    // 设置编码解码
                                    channel.pipeline()
                                            .addLast(new IdleStateHandler(0,0, Beat.BEAT_INTERVAL*3, TimeUnit.SECONDS))     // beat 3N, close if idle
                                            // 该回调方法主要是将之前注册到注册中心的服务移除掉
                                            .addLast(new NettyDecoder(XxlRpcRequest.class, xxlRpcProviderFactory.getSerializerInstance()))
                                            // 在响应的时候会被调用
                                            // encode 方法主要就是对 XxlRpcResponse 对象进行编码 然后再流转到其他调用者手中
                                            .addLast(new NettyEncoder(XxlRpcResponse.class, xxlRpcProviderFactory.getSerializerInstance()))
                                            .addLast(new NettyServerHandler(xxlRpcProviderFactory, serverHandlerPool));
                                }
                            })
                            .childOption(ChannelOption.TCP_NODELAY, true)
                            .childOption(ChannelOption.SO_KEEPALIVE, true);

                    // bind
                    ChannelFuture future = bootstrap.bind(xxlRpcProviderFactory.getPort()).sync();

                    logger.info(">>>>>>>>>>> xxl-rpc remoting server start success, nettype = {}, port = {}", NettyServer.class.getName(), xxlRpcProviderFactory.getPort());
                    // 回调处理 ，实现将服务注册到注册中心
                    onStarted();

                    // wait util stop
                    future.channel().closeFuture().sync();

                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        logger.info(">>>>>>>>>>> xxl-rpc remoting server stop.");
                    } else {
                        logger.error(">>>>>>>>>>> xxl-rpc remoting server error.", e);
                    }
                } finally {

                    // stop
                    try {
                        serverHandlerPool.shutdown();    // shutdownNow
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                    try {
                        workerGroup.shutdownGracefully();
                        bossGroup.shutdownGracefully();
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }

                }
            }
        });
        // 设置守护线程
        thread.setDaemon(true);
        thread.start();

    }

    @Override
    public void stop() throws Exception {

        // destroy server thread
        if (thread != null && thread.isAlive()) {
            // 中断启动时设置的守护线程。
            thread.interrupt();
        }

        // on stop
        // 将注册中心上面的服务移除
        onStoped();
        logger.info(">>>>>>>>>>> xxl-rpc remoting server destroy success.");
    }

}
