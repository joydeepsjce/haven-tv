package home.haven.tv;

import android.media.tv.TvInputService;

/**
 * Not a real TV input. Some makers' boot managers (TCL's TclAppBootConfigManager, for one) let any
 * package that declares a TvInputService start its services at boot; without this, it blocks
 * HomeKeyService after every power-on and Home opens Google TV. The manifest entry has no
 * android.media.tv.input metadata, so Android's TV input framework ignores it and it never
 * appears as an input.
 */
public class BootPassService extends TvInputService {
    @Override public Session onCreateSession(String inputId) {
        return null;
    }
}
