package home.haven.tv;

import android.graphics.drawable.GradientDrawable;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/** Screensaver: your chosen wallpapers with a large clock, in the spirit of Apple TV's. */
public class HavenDream extends DreamService {

    private Slideshow slideshow;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        Ui.init(this);
        int side = Ui.dp(this, 56);

        FrameLayout root = new FrameLayout(this);
        LinearLayout credit = new LinearLayout(this);
        credit.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "", 22, Ui.medium);
        TextView line = Ui.text(this, "", 12, Ui.regular);
        line.setAlpha(0.7f);
        credit.addView(title);
        credit.addView(line);

        slideshow = new Slideshow(this, 30_000, (t, l, color) -> {
            title.setText(t);
            line.setText(l);
            credit.setAlpha(0f);
            credit.animate().alpha(1f).setDuration(1800).start();
        });
        root.addView(slideshow.view, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x00000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, Ui.dp(this, 200), Gravity.BOTTOM));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        clp.leftMargin = side;
        clp.bottomMargin = Ui.dp(this, 44);
        root.addView(credit, clp);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        Ui.style(time, 56, Ui.light);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        tlp.rightMargin = side;
        tlp.bottomMargin = Ui.dp(this, 36);
        root.addView(time, tlp);
        setContentView(root);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        slideshow.start();
    }

    @Override public void onDreamingStopped() {
        slideshow.pause();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        slideshow.release();
        super.onDetachedFromWindow();
    }
}
