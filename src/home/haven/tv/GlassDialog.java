package home.haven.tv;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ListView;

/**
 * Haven's pop-ups (app options, rename, confirmations) in the same glass look as the rest of
 * Haven: a frosted panel showing the blurred wallpaper, a glass highlight on the focused item
 * and glass buttons. Use it exactly like {@link AlertDialog.Builder}.
 */
final class GlassDialog extends AlertDialog.Builder {

    private final Context c;

    GlassDialog(Context c) {
        super(c, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        this.c = c;
    }

    @Override public AlertDialog create() {
        AlertDialog d = super.create();
        d.setOnShowListener(x -> style(d));
        return d;
    }

    private void style(AlertDialog d) {
        Window w = d.getWindow();
        if (w == null) return;
        View decor = w.getDecorView();
        w.setBackgroundDrawable(new GlassDrawable(decor, dp(26), 0x990D0F13, 0.8f));
        w.setDimAmount(0.35f);
        ListView list = d.getListView();
        if (list != null) {
            list.setSelector(new android.graphics.drawable.InsetDrawable(new GlassDrawable(null, dp(14), 0x40FFFFFF, 0.9f), dp(14), dp(2), dp(14), dp(2)));
            list.setDrawSelectorOnTop(false);
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button b = d.getButton(which);
            if (b == null) continue;
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, new GlassDrawable(null, -1, 0x59FFFFFF, 1f));
            bg.addState(new int[]{}, new GlassDrawable(null, -1, 0x1AFFFFFF, 0.5f));
            b.setBackground(bg);
            b.setTextColor(Color.WHITE);
        }
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }
}
