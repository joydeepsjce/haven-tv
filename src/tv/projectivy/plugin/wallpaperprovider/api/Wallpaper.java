package tv.projectivy.plugin.wallpaperprovider.api;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A wallpaper returned by a Projectivy plugin. Java copy of the plugin API's Kotlin
 * {@code @Parcelize data class Wallpaper} (Apache License 2.0); the fields must be read and written
 * in the same order as there.
 */
public final class Wallpaper implements Parcelable {

    public static final int IMAGE = 0, DRAWABLE = 1, ANIMATED_DRAWABLE = 2, LOTTIE = 3, VIDEO = 4, COLOR = 5;
    public static final int DEFAULT = 0, CROP = 1, STRETCH = 2, BLUR = 3, FIT_CENTER = 4, FIT_CENTER_BLURRED_BG = 5;

    public final String uri;
    public final int type, displayMode;
    public final String title, source, author, actionUri;

    public Wallpaper(String uri, int type, int displayMode, String title, String source, String author, String actionUri) {
        this.uri = uri;
        this.type = type;
        this.displayMode = displayMode;
        this.title = title;
        this.source = source;
        this.author = author;
        this.actionUri = actionUri;
    }

    private Wallpaper(Parcel in) {
        this(in.readString(), in.readInt(), in.readInt(), in.readString(), in.readString(), in.readString(), in.readString());
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(uri);
        out.writeInt(type);
        out.writeInt(displayMode);
        out.writeString(title);
        out.writeString(source);
        out.writeString(author);
        out.writeString(actionUri);
    }

    @Override public int describeContents() {
        return 0;
    }

    public static final Creator<Wallpaper> CREATOR = new Creator<Wallpaper>() {
        @Override public Wallpaper createFromParcel(Parcel in) {
            return new Wallpaper(in);
        }

        @Override public Wallpaper[] newArray(int size) {
            return new Wallpaper[size];
        }
    };
}
