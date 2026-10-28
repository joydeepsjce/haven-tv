package home.haven.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The TV's HDMI ports and what's plugged into them, to open Live TV on the port the set-top box is
 * really on instead of assuming HDMI 1. Uses only public TV input APIs: each port's connection
 * state, and the devices HDMI-CEC found (listed as inputs under their port, with the device's name).
 */
final class Inputs {

    static final class Port {
        final String id, label;
        /** A device on this port that answered over HDMI-CEC ("Tata Play", "PlayStation 5"), or "". */
        final String device;
        final int state;

        Port(String id, String label, String device, int state) {
            this.id = id;
            this.label = label;
            this.device = device;
            this.state = state;
        }

        boolean connected() {
            return state == TvInputManager.INPUT_STATE_CONNECTED;
        }

        /** "HDMI 1 · Tata Play" or just "HDMI 1". */
        String name() {
            return device.isEmpty() ? label : label + " · " + device;
        }
    }

    /** The port you chose for Live TV (Hold OK on Live TV), and the last one opened from Haven. */
    static final String CHOSEN = "liveInput", LAST = "lastInput";
    /** Chosen value meaning "the TV's own Live TV app". */
    static final String TV_APP = "app";

    /** Players and consoles: a CEC device with one of these names isn't the TV box. */
    private static final String[] NOT_TV = {"playstation", "ps4", "ps5", "xbox", "nintendo", "switch",
            "chromecast", "fire tv", "firetv", "apple tv", "roku", "shield", "mi box", "blu-ray", "bluray",
            "soundbar", "pc", "computer", "steam"};

    private Inputs() { }

    /** HDMI ports in order (HDMI 1 first), with what CEC found on each. */
    static List<Port> ports(Context c) {
        List<Port> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> all;
        try {
            all = new ArrayList<>(tim.getTvInputList());
        } catch (RuntimeException e) {
            return out;
        }
        Collections.sort(all, (a, b) -> a.getId().compareTo(b.getId()));
        for (TvInputInfo info : all) {
            if (info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null || info.isHidden(c)) continue;
            String device = "";
            for (TvInputInfo child : all) {
                if (!info.getId().equals(child.getParentId())) continue;
                CharSequence l = child.loadLabel(c);
                if (!TextUtils.isEmpty(l)) { device = l.toString(); break; }
            }
            int state;
            try {
                state = tim.getInputState(info.getId());
            } catch (RuntimeException e) {
                state = TvInputManager.INPUT_STATE_DISCONNECTED;
            }
            out.add(new Port(info.getId(), label(c, info), device, state));
        }
        return out;
    }

    private static String label(Context c, TvInputInfo info) {
        CharSequence custom = info.loadCustomLabel(c);
        return String.valueOf(TextUtils.isEmpty(custom) ? info.loadLabel(c) : custom);
    }

    /**
     * The port for Live TV, or null to use the TV's own Live TV app. In order: your choice, a CEC
     * device that isn't a player or console (a set-top box), the only connected port, the port you
     * last opened from Haven if it's still connected.
     */
    static String liveTvPort(Context c) {
        SharedPreferences p = c.getSharedPreferences("haven", Context.MODE_PRIVATE);
        String chosen = p.getString(CHOSEN, "");
        List<Port> ports = ports(c);
        if (TV_APP.equals(chosen)) return null;
        for (Port port : ports) if (port.id.equals(chosen)) return port.id;
        for (Port port : ports) if (!port.device.isEmpty() && isTvBox(port.device)) return port.id;
        Port only = null;
        int connected = 0;
        for (Port port : ports) if (port.connected() && (port.device.isEmpty() || isTvBox(port.device))) { connected++; only = port; }
        if (connected == 1) return only.id;
        String last = p.getString(LAST, "");
        for (Port port : ports) if (port.id.equals(last) && port.state != TvInputManager.INPUT_STATE_DISCONNECTED) return port.id;
        return null;
    }

    private static boolean isTvBox(String device) {
        String d = device.toLowerCase(Locale.ROOT);
        for (String n : NOT_TV) if (d.contains(n)) return false;
        return true;
    }

    /** Opens an HDMI port full screen, as the TV's input picker does. */
    static Intent open(String inputId) {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(inputId), TvContract.Channels.CONTENT_ITEM_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static void noteOpened(Context c, String inputId) {
        c.getSharedPreferences("haven", Context.MODE_PRIVATE).edit().putString(LAST, inputId).apply();
    }
}
