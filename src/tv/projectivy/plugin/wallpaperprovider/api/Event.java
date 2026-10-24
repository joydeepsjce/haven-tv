package tv.projectivy.plugin.wallpaperprovider.api;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * What the launcher tells a Projectivy plugin when it asks for wallpapers. Java copy of the plugin
 * API's Kotlin {@code sealed class Event} (Apache License 2.0). Every event starts with its type;
 * the rest is written field by field in the order the Kotlin {@code @Parcelize} classes use.
 * Haven only sends events, so reading one back is never needed.
 */
public final class Event implements Parcelable {

    public static final int TIME_ELAPSED = 1, NOW_PLAYING_CHANGED = 2, CARD_FOCUSED = 4,
            PROGRAM_CARD_FOCUSED = 8, LAUNCHER_IDLE_MODE_CHANGED = 16;

    public final int type;
    private final Object[] fields;

    private Event(int type, Object... fields) {
        this.type = type;
        this.fields = fields;
    }

    /** Time for the next wallpaper; the plugin returns its full list. */
    public static Event timeElapsed() {
        return new Event(TIME_ELAPSED);
    }

    /** An app tile got focus. */
    public static Event cardFocused(int lightColor, int darkColor, String title, String packageName) {
        return new Event(CARD_FOCUSED, lightColor, darkColor, title, packageName);
    }

    /**
     * A program card (Continue watching, Top picks) got focus. {@code programType} is a
     * TvContract PreviewProgramColumns TYPE_* value, or null; {@code aspectRatio} is an
     * ASPECT_RATIO_* value.
     */
    public static Event programCardFocused(String title, Integer programType, String iconUri, int aspectRatio) {
        return new Event(PROGRAM_CARD_FOCUSED, title, new Nullable(programType), iconUri, aspectRatio);
    }

    /** The launcher went idle (true) or the user is back (false). */
    public static Event launcherIdle(boolean idle) {
        return new Event(LAUNCHER_IDLE_MODE_CHANGED, idle);
    }

    /** A nullable Int, which @Parcelize writes as a 0/1 presence flag followed by the value. */
    private static final class Nullable {
        final Integer value;

        Nullable(Integer value) {
            this.value = value;
        }
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        // TimeElapsed is a Kotlin object: @Parcelize writes a single 1, which is also its type.
        out.writeInt(type);
        for (Object f : fields) {
            if (f instanceof Integer) out.writeInt((Integer) f);
            else if (f instanceof Boolean) out.writeInt((Boolean) f ? 1 : 0);
            else if (f instanceof Nullable) {
                Integer v = ((Nullable) f).value;
                out.writeInt(v != null ? 1 : 0);
                if (v != null) out.writeInt(v);
            } else out.writeString((String) f);
        }
    }

    @Override public int describeContents() {
        return 0;
    }

    public static final Creator<Event> CREATOR = new Creator<Event>() {
        @Override public Event createFromParcel(Parcel in) {
            return new Event(in.readInt());
        }

        @Override public Event[] newArray(int size) {
            return new Event[size];
        }
    };
}
