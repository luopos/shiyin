package com.shiyin.app.source;

import android.content.Context;

import com.shiyin.app.model.Cat;
import com.shiyin.app.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 分类树。接口本身不返回分类名字，这是从原版 bundle 里抠出来的静态表，
 * 放在 assets/catalog.json。
 */
public final class Catalog {

    private static final List<Cat> LRTS = new ArrayList<>();
    private static final List<Cat> KUWO = new ArrayList<>();
    private static boolean loaded = false;

    private Catalog() {
    }

    public static synchronized void load(Context ctx) {
        if (loaded) return;
        try {
            InputStream in = ctx.getAssets().open("catalog.json");
            byte[] buf = new byte[in.available()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            in.close();
            JSONObject root = new JSONObject(new String(buf, "UTF-8"));
            parse(root.optJSONArray("lrts"), "lrts", LRTS);
            parse(root.optJSONArray("kuwo"), "kuwo", KUWO);
            // 只有真正读成功才记已加载，避免一次读取失败后分类永久为空。
            loaded = true;
        } catch (Exception ignored) {
        }
    }

    private static void parse(JSONArray a, String source, List<Cat> out) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Cat c = new Cat(source, o.optString("id"), o.optString("name"));
            c.icon = o.optString("icon", "");
            c.desc = o.optString("desc", "");
            c.color = Ui.parseColor(o.optString("accent", ""), Ui.AMBER);
            JSONArray ch = o.optJSONArray("children");
            if (ch != null) {
                for (int k = 0; k < ch.length(); k++) {
                    JSONObject co = ch.optJSONObject(k);
                    if (co == null) continue;
                    Cat cc = new Cat(source, co.optString("id"), co.optString("name"));
                    cc.color = c.color;
                    c.children.add(cc);
                }
            }
            out.add(c);
        }
    }

    public static List<Cat> lrts() {
        return LRTS;
    }

    public static List<Cat> kuwo() {
        return KUWO;
    }
}
