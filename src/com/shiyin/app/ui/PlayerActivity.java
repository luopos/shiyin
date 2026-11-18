package com.shiyin.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.play.Player;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.util.ImgView;
import com.shiyin.app.util.Ui;

/** 播放页：封面、进度、上一集/播放/下一集。 */
public class PlayerActivity extends Activity implements Player.Listener {

    private ImgView cover;
    private TextView title;
    private TextView chapter;
    private TextView cur;
    private TextView total;
    private TextView playBtn;
    private TextView stateHint;
    private SeekBar seek;
    private boolean dragging = false;
    private boolean bound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());
        Player.get().addListener(this);
        bound = true;
        render();
        getWindow().getDecorView().post(() -> BackgroundActivity.showHintOnce(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!bound) {
            Player.get().addListener(this);
            bound = true;
        }
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Player.get().saveProgress();
    }

    @Override
    protected void onDestroy() {
        Player.get().removeListener(this);
        bound = false;
        super.onDestroy();
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
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), 0);
        TextView back = Ui.text(this, "‹", 26f, Ui.DIM);
        back.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 8), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(sp);
        TextView catalog = Ui.text(this, "目录", 13f, Ui.DIM);
        catalog.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        catalog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Book b = Player.get().book();
                if (b == null) return;
                startActivity(DetailActivity.intent(PlayerActivity.this, b));
            }
        });
        bar.addView(catalog);
        root.addView(bar);

        /* 封面 */
        LinearLayout coverBox = new LinearLayout(this);
        coverBox.setGravity(Gravity.CENTER);
        coverBox.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 20));
        cover = new ImgView(this);
        cover.radius(16);
        cover.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(this, 240), Ui.dp(this, 240)));
        coverBox.addView(cover);
        root.addView(coverBox);

        /* 标题 */
        title = Ui.bold(this, "", 18f, Ui.TEXT);
        title.setGravity(Gravity.CENTER);
        title.setPadding(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
        title.setMaxLines(2);
        root.addView(title);

        chapter = Ui.text(this, "", 13f, Ui.DIM);
        chapter.setGravity(Gravity.CENTER);
        chapter.setPadding(Ui.dp(this, 28), Ui.dp(this, 8), Ui.dp(this, 28), 0);
        chapter.setMaxLines(2);
        root.addView(chapter);

        stateHint = Ui.text(this, "", 11.5f, Ui.RED);
        stateHint.setGravity(Gravity.CENTER);
        stateHint.setPadding(Ui.dp(this, 24), Ui.dp(this, 6), Ui.dp(this, 24), 0);
        root.addView(stateHint);

        /* 进度 */
        LinearLayout prog = new LinearLayout(this);
        prog.setOrientation(LinearLayout.HORIZONTAL);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        prog.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), 0);

        cur = Ui.text(this, "00:00", 11f, Ui.DIM2);
        cur.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 46),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        prog.addView(cur);

        seek = new SeekBar(this);
        seek.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        seek.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) cur.setText(Ui.timeMs(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                Player.get().seekTo(sb.getProgress());
            }
        });
        prog.addView(seek);

        total = Ui.text(this, "00:00", 11f, Ui.DIM2);
        total.setGravity(Gravity.END);
        total.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 46),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        prog.addView(total);
        root.addView(prog);

        /* 控制按钮 */
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER);
        ctrl.setPadding(0, Ui.dp(this, 24), 0, 0);

        TextView prev = Ui.text(this, "⏮", 26f, Ui.TEXT);
        prev.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Player.get().prev(PlayerActivity.this);
            }
        });
        ctrl.addView(prev);

        TextView back15 = Ui.text(this, "-15", 13f, Ui.DIM);
        back15.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        back15.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Player.get().seekBy(-15);
            }
        });
        ctrl.addView(back15);

        playBtn = Ui.text(this, "▶", 30f, 0xFF0B0E14);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setBackground(Ui.round(Ui.AMBER, 40, this));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                Ui.dp(this, 72), Ui.dp(this, 72));
        plp.leftMargin = Ui.dp(this, 16);
        plp.rightMargin = Ui.dp(this, 16);
        playBtn.setLayoutParams(plp);
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Player.get().toggle(PlayerActivity.this);
            }
        });
        ctrl.addView(playBtn);

        TextView fwd15 = Ui.text(this, "+15", 13f, Ui.DIM);
        fwd15.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        fwd15.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Player.get().seekBy(15);
            }
        });
        ctrl.addView(fwd15);

        TextView next = Ui.text(this, "⏭", 26f, Ui.TEXT);
        next.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Player.get().next(PlayerActivity.this);
            }
        });
        ctrl.addView(next);

        root.addView(ctrl);

        /* 底部信息 */
        TextView foot = Ui.text(this, "", 11f, Ui.DIM2);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24), 0);
        foot.setId(0x7f0f0001);
        root.addView(foot);
        this.foot = foot;

        return root;
    }

    private TextView foot;

    private void render() {
        Player p = Player.get();
        Book b = p.book();
        if (b == null) {
            finish();
            return;
        }
        cover.load(b.cover);
        title.setText(b.title);
        Chapter c = p.chapter();
        chapter.setText(c == null ? "" : c.title);

        int st = p.state();
        if (st == Player.LOADING) {
            playBtn.setText("…");
            stateHint.setText(p.message().length() == 0 ? "正在加载…" : p.message());
        } else if (st == Player.PLAYING) {
            playBtn.setText("❚❚");
            stateHint.setText("");
        } else if (st == Player.ERROR) {
            playBtn.setText("▶");
            stateHint.setText(p.error());
        } else {
            playBtn.setText("▶");
            stateHint.setText("");
        }

        int i = p.index();
        int n = p.chapterTotal();
        foot.setText(i < 0 ? "" : ("第 " + (i + 1)
                + (n >= 0 ? " / " + n : "") + " 集 · "
                + Widgets.srcName(b.source) + " · " + Prefs.quality() + "kbps"));

        int d = p.duration();
        if (d > 0) {
            seek.setMax(d);
            total.setText(Ui.timeMs(d));
        }
        if (!dragging) {
            seek.setProgress(p.position());
            cur.setText(Ui.timeMs(p.position()));
        }
    }

    @Override
    public void onState(int state, String message) {
        render();
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
        if (dragging) return;
        if (durationMs > 0) {
            if (seek.getMax() != durationMs) {
                seek.setMax(durationMs);
                total.setText(Ui.timeMs(durationMs));
            }
            seek.setProgress(positionMs);
        }
        cur.setText(Ui.timeMs(positionMs));
    }

    @Override
    public void onTrack(int index, Chapter c) {
        chapter.setText(c == null ? "" : c.title);
        render();
    }
}
