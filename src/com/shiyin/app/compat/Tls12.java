package com.shiyin.app.compat;

import android.os.Build;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * TLS 1.2 兜底。
 *
 * <p>Android 4.1–4.4（API16–19）默认不启用 TLS 1.2，而现在的源站基本只支持 TLS 1.2+，
 * 不补这一步在 TV 上所有 https 请求都会 SSLHandshakeException。
 *
 * <p>做法：拿一个支持 TLSv1.2 的 SSLSocketFactory，再包一层在 createSocket 之后把
 * 协议列表显式设成 TLSv1.2/1.1/1.0（按平台实际支持取交集），最后装成默认工厂。
 * 全程吞异常——补不上也不能让应用起不来。
 */
public final class Tls12 {

    private static final String[] PREFERRED = {"TLSv1.2", "TLSv1.1", "TLSv1"};

    private static boolean done;

    private Tls12() {
    }

    public static synchronized void enable() {
        if (done) return;
        done = true;
        // API21 起 TLS 1.2 默认就在启用列表里，不用动。
        if (Build.VERSION.SDK_INT >= 21) return;
        try {
            SSLContext context;
            try {
                context = SSLContext.getInstance("TLSv1.2");
            } catch (Throwable unsupported) {
                // 老 BoringSSL/OpenSSL 组合没有 TLSv1.2 provider，退回通用 TLS 再靠 setEnabledProtocols 开。
                context = SSLContext.getInstance("TLS");
            }
            context.init(null, null, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(
                    new ProtocolSocketFactory(context.getSocketFactory()));
        } catch (Throwable ignored) {
            // 补不上就算了，至少不影响启动和明文源站。
        }
    }

    /** 委托给真实工厂，只在建好 socket 后改协议列表。 */
    private static final class ProtocolSocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory delegate;

        ProtocolSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override public Socket createSocket(String host, int port) throws IOException {
            return upgrade(delegate.createSocket(host, port));
        }

        @Override public Socket createSocket(String host, int port, InetAddress localHost,
                                             int localPort) throws IOException {
            return upgrade(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return upgrade(delegate.createSocket(host, port));
        }

        @Override public Socket createSocket(InetAddress address, int port,
                                             InetAddress localAddress, int localPort)
                throws IOException {
            return upgrade(delegate.createSocket(address, port, localAddress, localPort));
        }

        @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
                throws IOException {
            return upgrade(delegate.createSocket(socket, host, port, autoClose));
        }
    }

    private static Socket upgrade(Socket socket) {
        if (!(socket instanceof SSLSocket)) return socket;
        SSLSocket ssl = (SSLSocket) socket;
        try {
            Set<String> supported = new HashSet<>();
            String[] available = ssl.getSupportedProtocols();
            if (available != null) {
                for (String protocol : available) supported.add(protocol);
            }
            List<String> use = new ArrayList<>();
            for (String protocol : PREFERRED) {
                if (supported.contains(protocol)) use.add(protocol);
            }
            if (!use.isEmpty()) ssl.setEnabledProtocols(use.toArray(new String[0]));
        } catch (Throwable ignored) {
            // 平台不支持就算了，保持默认协议。
        }
        return ssl;
    }
}
