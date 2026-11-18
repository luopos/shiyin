package com.shiyin.app.util;

/**
 * 抹掉渠道真名。
 *
 * 有些栏目名是接口直接给的（"听友推荐" 这种），界面上只留"源1/源2/源3/源4"，
 * 这些漏出来的品牌词统一在这儿清掉。
 */
public final class Scrub {

    private static final String[] BRANDS = {
            "听书吧", "酷我", "听友听书", "听友", "懒人听书", "懒人", "喜马拉雅", "蜻蜓FM", "蜻蜓",
            "云听", "番茄", "咪咕", "荔枝", "企鹅", "网易云音乐", "网易云", "酷狗", "QQ音乐",
            "书音FM", "书音", "百家讲坛", "声音巴士", "悦听吧", "念音网", "tingyou", "kuwo",
            "lrts", "Apple 播客", "Apple播客", "iTunes"
    };

    private Scrub() {
    }

    public static String text(String s) {
        if (s == null || s.length() == 0) return "";
        String out = s;
        for (String b : BRANDS) {
            if (out.contains(b)) out = out.replace(b, "");
        }
        out = out.trim();
        // 全被抹掉时给个中性词，别显示空标签
        if (out.length() == 0) return "精选";
        return out;
    }

    /** 栏目名用：抹完再补个"推荐"，避免只剩半个词。 */
    public static String section(String s) {
        String t = text(s);
        if (t.length() == 0 || "精选".equals(t)) return "精选推荐";
        return t;
    }
}
