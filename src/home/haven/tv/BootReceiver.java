package home.haven.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a cold boot, puts the Home-button service and the settings watcher back (see Keeper). */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context c, Intent i) {
        PendingResult r = goAsync();
        Keeper.schedule(c);
        Keeper.ensure(c, true, r::finish);
    }
}
