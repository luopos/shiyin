package com.shiyin.app.source;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Cat;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.net.Http;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Apple 播客：iTunes 搜索接口找节目，再解析 RSS 拿每期音频。
 *
 * 搜索框里直接粘 RSS 链接也行 —— 那就跳过搜索，直接当一期节目读。
 * 这是实测最稳的源：音频走 CDN，没有防盗链。
 */
public class PodcastSource extends Source {

    private static final String[] TOPICS = {"新闻", "故事", "文化", "科技", "历史", "商业"};
    private static final String DIRECTORY = "https://itunes.apple.com/search";
    private static final Pattern URL_OK =
            Pattern.compile("^https?://[^/\\s?#]+[^\\s]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIO_EXT =
            Pattern.compile("\\.(mp3|m4a|m4b|aac|ogg|opus|wav|flac|m3u8)(?:[?#]|$)",
                    Pattern.CASE_INSENSITIVE);

    private static String http(String v) {
        if (v == null) return "";
        String s = v.trim();
        return URL_OK.matcher(s).matches() ? s : "";
    }

    private static String text(Element e) {
        if (e == null) return "";
        String t = e.text();
        return t == null ? "" : t.trim();
    }

    private static String childText(Element parent, String tag) {
        if (parent == null) return "";
        Elements es = parent.getElementsByTag(tag);
        return es.isEmpty() ? "" : text(es.first());
    }

    /** 读 RSS。includeEpisodes=false 时只返回节目信息（搜索列表用）。 */
    private static Book read(String feed, boolean includeEpisodes, List<Chapter> out)
            throws Exception {
        String xml = Http.getString(feed);
        Document doc;
        try {
            doc = Jsoup.parse(xml, "", Parser.xmlParser());
        } catch (Exception e) {
            throw new Exception("RSS 解析失败");
        }
        Element channel = doc.selectFirst("channel");
        if (channel == null) throw new Exception("这个链接没有返回播客 RSS 频道");

        Book b = new Book();
        b.source = "podcast";
        b.bookUrl = feed;
        b.id = feed;
        b.title = childText(channel, "title");
        if (b.title.length() == 0) b.title = "RSS 播客";
        b.author = childText(channel, "itunes:author");
        if (b.author.length() == 0) b.author = childText(channel, "managingEditor");
        String intro = childText(channel, "description");
        b.desc = intro.replaceAll("<[^>]*>", " ").trim();

        Element img = channel.selectFirst("itunes|image");
        if (img == null) img = channel.selectFirst("image");
        if (img != null) {
            String href = img.attr("href");
            if (href.length() == 0) {
                Element u = img.selectFirst("url");
                if (u != null) href = text(u);
            }
            b.cover = http(href);
        }

        if (!includeEpisodes) return b;

        Elements items = channel.getElementsByTag("item");
        Set<String> seen = new HashSet<>();
        for (Element it : items) {
            Elements encs = it.getElementsByTag("enclosure");
            for (Element enc : encs) {
                String type = enc.attr("type").toLowerCase();
                String url = http(enc.attr("url"));
                if (url.length() == 0) continue;
                boolean isAudio = type.startsWith("audio/") || AUDIO_EXT.matcher(url).find();
                if (!isAudio) continue;
                if (!seen.add(url)) break;
                Chapter c = new Chapter();
                c.bookId = feed;
                String t = childText(it, "title");
                c.title = t.length() == 0 ? ("第 " + (out.size() + 1) + " 期") : t;
                c.url = url;
                c.index = out.size() + 1;
                out.add(c);
                break;
            }
        }
        if (out.isEmpty()) throw new Exception("RSS 中没有可播放的音频附件");
        b.songNum = out.size();
        b.status = "共 " + out.size() + " 期";
        return b;
    }

    @Override
    public String id() {
        return "podcast";
    }

    @Override
    public String name() {
        return "源4";
    }

    @Override
    public String desc() {
        return "搜节目名，或直接粘贴 RSS 链接；音频走 CDN，最稳";
    }

    @Override
    public List<Cat> cats() {
        List<Cat> out = new ArrayList<>();
        Cat root = new Cat("podcast", "topic", "按主题浏览");
        root.icon = "🎧";
        for (String t : TOPICS) {
            root.children.add(new Cat("podcast", "topic:" + t, t));
        }
        out.add(root);
        return out;
    }

    @Override
    public Page page(Cat cat, int pn) throws Exception {
        Page p = new Page();
        String key = cat.id;
        if (key.startsWith("topic:")) {
            p.books = search(key.substring(6), 1);
        }
        p.totalPage = 1;
        return p;
    }

    @Override
    public List<Book> search(String keyword, int page) throws Exception {
        List<Book> out = new ArrayList<>();
        if (page > 1) return out;
        String q = keyword == null ? "" : keyword.trim();
        if (q.length() == 0) return out;

        // 直接粘 RSS
        String asFeed = q.replaceAll("(?i)^feed://(?=https?://)", "")
                .replaceAll("(?i)^feed://", "https://");
        if (URL_OK.matcher(asFeed).matches() && !asFeed.contains("itunes.apple.com")) {
            Book b = read(asFeed, false, new ArrayList<Chapter>());
            b.bookUrl = asFeed;
            b.id = asFeed;
            out.add(b);
            return out;
        }

        Set<String> seen = new HashSet<>();
        searchIn(q, "cn", out, seen);
        if (out.isEmpty()) searchIn(q, "us", out, seen);
        return out;
    }

    private void searchIn(String q, String country, List<Book> out, Set<String> seen)
            throws Exception {
        String url = DIRECTORY + "?media=podcast&entity=podcast&limit=40&country=" + country
                + "&term=" + URLEncoder.encode(q, "UTF-8");
        String body = Http.getString(url);
        JSONObject j = new JSONObject(body);
        JSONArray arr = j.optJSONArray("results");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row == null) continue;
            String feedUrl = http(row.optString("feedUrl", ""));
            String title = row.optString("collectionName", row.optString("trackName", ""));
            if (feedUrl.length() == 0 || title.length() == 0) continue;
            if (!seen.add(feedUrl)) continue;
            Book b = new Book();
            b.source = "podcast";
            b.id = feedUrl;
            b.bookUrl = feedUrl;
            b.title = title;
            b.author = row.optString("artistName", "");
            String art = http(row.optString("artworkUrl600", ""));
            if (art.length() == 0) art = http(row.optString("artworkUrl100", ""));
            if (art.length() == 0) art = http(row.optString("artworkUrl60", ""));
            b.cover = art;
            out.add(b);
        }
    }

    @Override
    public Detail detail(Book book, boolean withEpisodes) throws Exception {
        String feed = http(book.bookUrl);
        if (feed.length() == 0) feed = http(book.id);
        if (feed.length() == 0) throw new Exception("无效的 RSS 链接");
        List<Chapter> eps = new ArrayList<>();
        Book meta = read(feed, withEpisodes, eps);
        if (meta.cover.length() == 0) meta.cover = book.cover;
        Detail d = new Detail();
        d.meta = meta;
        if (withEpisodes) d.chapters = eps;
        return d;
    }

    @Override
    public String audio(Chapter chapter) throws Exception {
        String media = http(chapter.url);
        if (media.length() == 0) throw new Exception("无效的播客音频地址");
        return media;
    }
}
