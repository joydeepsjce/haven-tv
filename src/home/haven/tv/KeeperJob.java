package home.haven.tv;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Woken by the system when Haven's watched settings change; puts them back (see Keeper). */
public class KeeperJob extends JobService {

    static final int ID = 7341;

    @Override public boolean onStartJob(JobParameters params) {
        Keeper.schedule(this);
        Keeper.ensure(this, false, () -> jobFinished(params, false));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}
