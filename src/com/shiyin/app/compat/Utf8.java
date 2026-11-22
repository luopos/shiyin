package com.shiyin.app.compat;

import java.io.UnsupportedEncodingException;

/**
 * UTF-8 编码工具。
 *
 * <p>{@code java.nio.charset.StandardCharsets} 是 API19 才有的，Android 4.1 上没有这个类，
 * 类一加载就 NoClassDefFoundError。共享代码里凡是需要 UTF-8 字节的地方都走这里，
 * 用 {@code getBytes(String)} 的旧接口。
 */
public final class Utf8 {

    public static final String NAME = "UTF-8";

    private Utf8() {
    }

    public static byte[] bytes(String text) {
        if (text == null) return new byte[0];
        try {
            return text.getBytes(NAME);
        } catch (UnsupportedEncodingException impossible) {
            // 任何 JVM/ART 都必须支持 UTF-8，这里只是让编译器闭嘴。
            throw new AssertionError(impossible);
        }
    }

    public static String string(byte[] data) {
        if (data == null) return "";
        try {
            return new String(data, NAME);
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
