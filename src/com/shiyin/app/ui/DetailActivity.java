package com.shiyin.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.play.Player;
import com.shiyin.app.source.ChapterRepository;
import com.shiyin.app.source.Source;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.util.Bg;
import com.shiyin.app.util.ImgView;
import com.shiyin.app.util.Ui;

/** 书籍详情 + 目录。点章节直接开播。 */
public class DetailActivity extends Activity {

    /** 所有书籍入口共用同一组参数，避免新增字段时遗漏页面。 */
    public static Intent intent(android.content.Context context, Book book) {
        Intent intent = new Intent(context, DetailActivity.class);
        intent.putExtra("source", book.source);
        intent.putExtra("id", book.id);
        intent.putExtra("title", book.title);
        intent.putExtra("cover", book.cover);
        intent.putExtra("author", book.author);
        intent.putExtra("narrator", book.narrator);
        intent.putExtra("bookUrl", book.bookUrl);
        return intent;
    }

    private Book book = new Book();
    private Source.ChapterPage shownPage;
    private ChapterAdapter adapter;
    private ListView chapterList;
    private TextView playBtn;
    private TextView shelfBtn;
    private ImgView headCover;
    private TextView headTitle;
    private TextView headWho;
    private TextView headStatus;
    private TextView description;
    private TextView directoryCount;
    private LinearLayout directoryState;
    private ProgressBar directorySpinner;
    private TextView directoryMessage;
    private TextView directoryRetry;
    private LinearLayout pager;
    private TextView previousPage;
    private TextView pageInfo;
    private TextView nextPage;
    private boolean loading;
    private int loadGeneration;
    private int requestedPage = -1;
    private int pendingPlayIndex = -1;
    private int retryPage = -1;
    private int retryPlayIndex = -1;
    private int confirmedTotal = -1;

    private void showDirectoryState(boolean spinning, String message, boolean retry) {
        directoryState.setVisibility(View.VISIBLE);
        directorySpinner.setVisibility(spinning ? View.VISIBLE : View.GONE);
        directoryMessage.setPadding(spinning ? Ui.dp(this, 10) : 0, 0, Ui.dp(this, 8), 0);
        directoryMessage.setText(message);
        directoryRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    private int continuationIndex() {
        Player player = Player.get();
        Book active = player.book();
        if (active != null && book.source.equals(active.source) && book.id.equals(active.id)
                && (player.state() == Player.PLAYING || player.state() == Player.PAUSED
                || player.state() == Player.LOADING) && player.index() >= 0) {
            return player.index();
        }
        Prefs.Prog progress = Prefs.progress(book.source, book.id);
        return progress == null ? -1 : progress.chapterIndex;
    }

    private static void enable(TextView view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.5f);
    }

    private boolean hasChapters() {
        return shownPage != null && !shownPage.chapters.isEmpty();
    }

    private boolean changingPage() {
        return loading && (!hasChapters() || shownPage.page != requestedPage);
    }

    private void updatePlayButton() {
        int index = continuationIndex();
        boolean canPlay = hasChapters() && !changingPage();
        enable(playBtn, canPlay);
        adapter.setPickingEnabled(canPlay);
        adapter.setPlayingIndex(index);
        boolean historyMissing = isOutsideDirectory(index);
        if (historyMissing) playBtn.setText("▶ 播放本页");
        else playBtn.setText(index >= 0 ? "▶ 继续第 " + ((long) index + 1) + " 集" : "▶ 播放");
    }

    private boolean isOutsideDirectory(int index) {
        return index >= 0 && confirmedTotal >= 0 && index >= confirmedTotal;
    }

    @Override protected void onDestroy() {
        loadGeneration++;
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent it = getIntent();
        book.source = it.getStringExtra("source");
        if (book.source == null) book.source = "";
        book.id = it.getStringExtra("id");
        if (book.id == null) book.id = "";
        book.title = it.getStringExtra("title");
        if (book.title == null) book.title = "";
        book.cover = it.getStringExtra("cover");
        if (book.cover == null) book.cover = "";
        book.author = it.getStringExtra("author");
        if (book.author == null) book.author = "";
        book.narrator = it.getStringExtra("narrator");
        if (book.narrator == null) book.narrator = "";
        book.bookUrl = it.getStringExtra("bookUrl");
        if (book.bookUrl == null) book.bookUrl = "";

        setContentView(build());
        int firstPage = Math.max(0, continuationIndex()) / Source.CHAPTER_PAGE_SIZE;
        if (savedInstanceState != null) firstPage = savedInstanceState.getInt("chapterPage", firstPage);
        requestPage(firstPage, -1, false);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (shownPage != null) outState.putInt("chapterPage", shownPage.page);
    }

    @Override protected void onResume() {
        super.onResume();
        updatePlayButton();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        /* 顶栏 */
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 4));
        TextView back = Ui.text(this, "‹", 24f, Ui.DIM);
        back.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 8), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);
        TextView t = Ui.text(this, "详情", 14f, Ui.DIM);
        bar.addView(t);
        root.addView(bar);

        /* 头部：封面 + 信息 */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));

        headCover = new ImgView(this);
        headCover.radius(12);
        headCover.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 138)));
        headCover.load(book.cover);
        head.addView(headCover);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(this, 14);
        col.setLayoutParams(clp);

        headTitle = Ui.bold(this, book.title, 17f, Ui.TEXT);
        headTitle.setMaxLines(3);
        col.addView(headTitle);

        headWho = Ui.text(this, "", 12f, Ui.DIM);
        headWho.setPadding(0, Ui.dp(this, 6), 0, 0);
        headWho.setMaxLines(2);
        col.addView(headWho);

        headStatus = Ui.text(this, "", 11.5f, Ui.DIM2);
        headStatus.setPadding(0, Ui.dp(this, 6), 0, 0);
        col.addView(headStatus);

        TextView src = Ui.text(this, Widgets.srcName(book.source), 10.5f, Ui.TEAL);
        src.setPadding(0, Ui.dp(this, 6), 0, 0);
        col.addView(src);

        head.addView(col);
        root.addView(head);

        /* 按钮 */
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 10));

        playBtn = Widgets.button(this, "▶ 播放", true, view -> playFromHistory());
        playBtn.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(playBtn);

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));
        btns.addView(gap);

        shelfBtn = Widgets.button(this, Prefs.inShelf(book) ? "已在书架" : "+ 书架", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean added = Prefs.toggleShelf(book);
                        shelfBtn.setText(added ? "已在书架" : "+ 书架");
                        Ui.toast(DetailActivity.this, added ? "已加入书架" : "已移出书架");
                    }
                });
        btns.addView(shelfBtn);
        root.addView(btns);

        /* 简介 */
        description = Ui.text(this, "", 12.5f, Ui.DIM);
        description.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 10));
        description.setMaxLines(2);
        description.setLineSpacing(Ui.dp(this, 2), 1f);
        description.setOnClickListener(view -> description.setMaxLines(
                description.getMaxLines() == 2 ? 6 : 2));
        root.addView(description);
        updateBookHeader();

        /* 目录标题 */
        LinearLayout ct = new LinearLayout(this);
        ct.setOrientation(LinearLayout.HORIZONTAL);
        ct.setGravity(Gravity.CENTER_VERTICAL);
        ct.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 8));
        View bar2 = new View(this);
        bar2.setBackground(Ui.round(Ui.AMBER, 2, this));
        bar2.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 3), Ui.dp(this, 15)));
        ct.addView(bar2);
        TextView ctTxt = Ui.bold(this, "目录", 16f, Ui.TEXT);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = Ui.dp(this, 8);
        ctTxt.setLayoutParams(tl);
        ct.addView(ctTxt);
        directoryCount = Ui.text(this, "每页 " + Source.CHAPTER_PAGE_SIZE + " 集", 11.5f, Ui.DIM2);
        ct.addView(directoryCount);
        root.addView(ct);

        directoryState = Widgets.hbox(this);
        directoryState.setGravity(Gravity.CENTER_VERTICAL);
        directoryState.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 12));
        directorySpinner = new ProgressBar(this);
        directorySpinner.setIndeterminate(true);
        directorySpinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.AMBER));
        directoryState.addView(directorySpinner, new LinearLayout.LayoutParams(
                Ui.dp(this, 24), Ui.dp(this, 24)));
        directoryMessage = Ui.text(this, "正在载入目录…", 12f, Ui.DIM);
        directoryMessage.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
        directoryMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        directoryState.addView(directoryMessage, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        directoryRetry = Widgets.button(this, "重试", false, view -> {
            if (retryPage >= 0) requestPage(retryPage, retryPlayIndex, true);
        });
        directoryRetry.setVisibility(View.GONE);
        directoryState.addView(directoryRetry);
        root.addView(directoryState);

        /* 目录列表 */
        chapterList = new ListView(this);
        chapterList.setDivider(null);
        chapterList.setDividerHeight(0);
        chapterList.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new ChapterAdapter(this, (chapter, position) -> {
            if (!changingPage()) playFrom(chapter.index);
        });
        chapterList.setAdapter(adapter);
        root.addView(chapterList);

        pager = Widgets.hbox(this);
        pager.setGravity(Gravity.CENTER_VERTICAL);
        pager.setBackgroundColor(Ui.BG);
        pager.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 10));
        previousPage = Widgets.button(this, "上一页", false, view -> {
            if (shownPage != null && shownPage.page > 0) requestPage(shownPage.page - 1, -1, false);
            else if (shownPage == null && !loading && retryPage > 0) requestPage(0, -1, false);
        });
        pageInfo = Ui.text(this, "", 13f, Ui.AMBER);
        pageInfo.setGravity(Gravity.CENTER);
        pageInfo.setMinHeight(Ui.dp(this, 44));
        pageInfo.setOnClickListener(view -> showPageJump());
        nextPage = Widgets.button(this, "下一页", false, view -> {
            if (canGoNext()) requestPage(shownPage.page + 1, -1, false);
        });
        pager.addView(previousPage);
        pager.addView(pageInfo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pager.addView(nextPage);
        pager.setVisibility(View.GONE);
        root.addView(pager);

        return root;
    }

    private static String prefer(String newValue, String oldValue) {
        return newValue == null || newValue.trim().isEmpty() ? oldValue : newValue;
    }

    private void updateBookHeader() {
        headTitle.setText(book.title);
        String who = book.author;
        if (!book.narrator.isEmpty()) {
            who = who.isEmpty() ? book.narrator : who + " · " + book.narrator;
        }
        headWho.setText(who);
        headWho.setVisibility(who.isEmpty() ? View.GONE : View.VISIBLE);

        // 总集数以目录分页接口为准，源元数据中的集数可能尚未确认。
        String status = book.status.replaceFirst("(^共\\s*|\\s*·\\s*)\\d+\\s*[集期]$", "").trim();
        headStatus.setText(status);
        headStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        description.setText(book.desc);
        description.setVisibility(book.desc.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void applyPage(Source.ChapterPage page) {
        boolean firstDisplay = shownPage == null;
        boolean pageChanged = firstDisplay || shownPage.page != page.page;
        shownPage = page;
        Book meta = page.meta;
        if (meta != null) {
            String cover = prefer(meta.cover, book.cover);
            if (!book.cover.equals(cover)) headCover.load(cover);
            book.cover = cover;
            book.title = prefer(meta.title, book.title);
            book.author = prefer(meta.author, book.author);
            book.narrator = prefer(meta.narrator, book.narrator);
            book.desc = prefer(meta.desc, book.desc);
            book.status = prefer(meta.status, book.status);
            book.bookUrl = prefer(meta.bookUrl, book.bookUrl);
        }
        if (page.total >= 0) book.songNum = page.total;
        updateBookHeader();
        int position = chapterList.getFirstVisiblePosition();
        View first = chapterList.getChildAt(0);
        int top = first == null ? 0 : first.getTop();
        adapter.set(page.chapters);
        if (pageChanged) {
            int positionToShow = 0;
            if (firstDisplay) {
                int index = continuationIndex();
                for (int i = 0; i < page.chapters.size(); i++) {
                    if (page.chapters.get(i).index == index) {
                        positionToShow = i;
                        break;
                    }
                }
            }
            chapterList.setSelection(positionToShow);
        } else if (!page.chapters.isEmpty()) {
            chapterList.setSelectionFromTop(Math.min(position, page.chapters.size() - 1), top);
        }
        updatePlayButton();
        updatePager();
    }

    private int shownTotal() {
        if (shownPage == null) return -1;
        long end = (long) shownPage.page * Source.CHAPTER_PAGE_SIZE + shownPage.chapters.size();
        if (confirmedTotal >= 0 && end <= confirmedTotal) return confirmedTotal;
        return shownPage.total;
    }

    private static int totalPages(int total) {
        return Math.max(1, (int) ((total + (long) Source.CHAPTER_PAGE_SIZE - 1)
                / Source.CHAPTER_PAGE_SIZE));
    }

    private int availablePages() {
        if (shownPage == null) return 1;
        int total = shownTotal();
        if (total >= 0) return totalPages(total);
        return shownPage.page + (shownPage.hasMore ? 2 : 1);
    }

    private boolean canGoNext() {
        return hasChapters() && shownPage.hasMore && pageSelectable(shownPage.page + 1);
    }

    private boolean pageSelectable(int page) {
        if (page < 0 || page >= availablePages()) return false;
        return !loading || page != requestedPage;
    }

    private void updatePager() {
        String perPage = "每页 " + Source.CHAPTER_PAGE_SIZE + " 集";
        if (shownPage == null) {
            directoryCount.setText(perPage);
            boolean offerFirstPage = !loading && retryPage > 0;
            pager.setVisibility(offerFirstPage ? View.VISIBLE : View.GONE);
            if (offerFirstPage) {
                previousPage.setText("回到首页");
                pageInfo.setText("");
                pageInfo.setContentDescription(null);
                enable(previousPage, true);
                enable(pageInfo, false);
                enable(nextPage, false);
            }
            return;
        }
        int total = shownTotal();
        if (total >= 0) {
            directoryCount.setText("共 " + total + " 集 · " + perPage);
        } else {
            directoryCount.setText("本页已载入 " + shownPage.chapters.size() + " 集 · " + perPage);
        }
        pager.setVisibility(View.VISIBLE);
        previousPage.setText("上一页");
        int page = shownPage.page;
        int pageCount = total >= 0 ? totalPages(total) : -1;
        String label = "第 " + (page + 1) + (pageCount >= 0 ? "/" + pageCount : "") + " 页";
        pageInfo.setText(label);
        pageInfo.setContentDescription(label + "，跳转页码");
        enable(previousPage, pageSelectable(page - 1));
        enable(nextPage, canGoNext());
        enable(pageInfo, availablePages() > 1);
    }

    private void showPageJump() {
        if (shownPage == null) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("页码（1–" + availablePages() + "）");
        FrameLayout container = new FrameLayout(this);
        container.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        container.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("跳转页码")
                .setView(container).setNegativeButton("取消", null)
                .setPositiveButton("跳转", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int maxPage = availablePages();
                    int enteredPage;
                    try {
                        enteredPage = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        enteredPage = 0;
                    }
                    if (enteredPage < 1 || enteredPage > maxPage) {
                        input.setError("请输入 1 至 " + maxPage + " 之间的页码");
                        return;
                    }
                    dialog.dismiss();
                    requestPage(enteredPage - 1, -1, false);
                }));
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
    }

    private static boolean hasChapters(Source.ChapterPage result, int page) {
        return result != null && result.page == page && !result.chapters.isEmpty();
    }

    private void requestPage(int page, int playIndex, boolean forceRefresh) {
        if (page < 0 || (long) page * Source.CHAPTER_PAGE_SIZE > Integer.MAX_VALUE) return;
        if (loading && requestedPage == page) {
            if (playIndex >= 0) pendingPlayIndex = playIndex;
            return;
        }
        if (!loading && shownPage != null && shownPage.page == page
                && playIndex < 0 && !forceRefresh) return;

        final int generation = ++loadGeneration;
        loading = true;
        requestedPage = page;
        pendingPlayIndex = playIndex;
        retryPage = -1;
        final Source.ChapterPage cached = ChapterRepository.cached(book, page);
        final boolean shownFromCache = hasChapters(cached, page);
        if (shownFromCache && (shownPage == null || shownPage.page != page)) applyPage(cached);
        updatePlayButton();
        updatePager();
        String pageLabel = "第 " + (page + 1) + " 页";
        showDirectoryState(true, shownFromCache ? "已显示" + pageLabel + "缓存，正在检查更新…"
                : "正在载入" + pageLabel + "目录…", false);
        final Book requestBook = book;
        Bg.run(() -> forceRefresh || (shownFromCache && playIndex < 0)
                        ? ChapterRepository.refresh(requestBook, page)
                        : ChapterRepository.load(requestBook, page),
                new Bg.Cb<Source.ChapterPage>() {
                    @Override public void ok(Source.ChapterPage result) {
                        if (!currentRequest(generation)) return;
                        if (result != null && result.page == page && result.total >= 0) {
                            confirmedTotal = result.total;
                            if (result.total > 0 && page >= totalPages(result.total)) {
                                loading = false;
                                pendingPlayIndex = -1;
                                int lastPage = totalPages(result.total) - 1;
                                Ui.toast(DetailActivity.this, "目录已更新，正在载入最后一页");
                                requestPage(lastPage, -1, true);
                                return;
                            }
                        }
                        if (!hasChapters(result, page)) {
                            if (result != null && result.page == page && shownPage == null && page == 0) {
                                applyPage(result);
                            }
                            finishPageFailure(page, result == null
                                    ? pageLabel + "目录加载失败" : pageLabel + "暂无章节");
                            return;
                        }
                        loading = false;
                        int index = pendingPlayIndex;
                        pendingPlayIndex = -1;
                        applyPage(result);
                        if (isOutsideDirectory(continuationIndex())) {
                            showDirectoryState(false, "目录已更新，历史章节暂不可用，请选择章节", false);
                            return;
                        }
                        directoryState.setVisibility(View.GONE);
                        if (index >= 0 && !playFrom(index)) {
                            pendingPlayIndex = index;
                            finishPageFailure(page, "未找到第 " + ((long) index + 1) + " 集");
                            return;
                        }
                        if (shownFromCache && playIndex >= 0 && index >= 0 && !forceRefresh) {
                            requestPage(page, -1, true);
                        }
                    }

                    @Override public void fail(Exception e) {
                        if (!currentRequest(generation)) return;
                        finishPageFailure(page, pageLabel + "目录加载失败");
                    }
                });
    }

    private boolean currentRequest(int generation) {
        return generation == loadGeneration && !isFinishing() && !isDestroyed();
    }

    private void finishPageFailure(int page, String message) {
        loading = false;
        retryPage = page;
        retryPlayIndex = pendingPlayIndex;
        pendingPlayIndex = -1;
        if (hasChapters()) {
            message += (shownPage.page == page ? "，当前目录可继续播放"
                    : "，仍显示第 " + (shownPage.page + 1) + " 页");
        }
        updatePlayButton();
        updatePager();
        showDirectoryState(false, message, true);
    }

    private void playFromHistory() {
        if (!hasChapters() || changingPage()) return;
        int index = continuationIndex();
        if (index < 0 || isOutsideDirectory(index)) index = shownPage.chapters.get(0).index;
        if (!playFrom(index)) requestPage(index / Source.CHAPTER_PAGE_SIZE, index, false);
    }

    private boolean playFrom(int index) {
        if (!hasChapters() || changingPage()) return false;
        for (Chapter chapter : shownPage.chapters) {
            if (chapter.index != index) continue;
            pendingPlayIndex = -1;
            Player player = Player.get();
            Book active = player.book();
            if (active != null && book.source.equals(active.source) && book.id.equals(active.id)
                    && player.index() == index && (player.state() == Player.PLAYING
                    || player.state() == Player.PAUSED || player.state() == Player.LOADING)) {
                if (!player.playRequested()) player.resume(this);
            } else {
                Prefs.Prog p = Prefs.progress(book.source, book.id);
                player.setChapterPage(this, book, shownPage, index);
                if (p != null && p.chapterIndex == index && p.position > 3000) player.seekTo(p.position);
            }
            updatePlayButton();
            startActivity(new Intent(this, PlayerActivity.class));
            return true;
        }
        return false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        Player.get().saveProgress();
    }
}
