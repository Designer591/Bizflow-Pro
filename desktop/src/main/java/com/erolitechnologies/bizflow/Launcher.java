package com.erolitechnologies.bizflow;

import com.erolitechnologies.bizflow.infra.AppPaths;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.infra.Migrator;

/**
 * Plain main class (does not extend Application) so the app also starts from a normal classpath / jpackage runtime image.
 *
 * Offline recovery for a locked-out administrator (requires access to the computer itself):
 *   BizFlowPro.exe --recovery-code <username>
 * prints a one-time code, valid for 30 minutes, to use with "Forgot password?" on the sign-in screen.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        if (args.length == 2 && "--recovery-code".equals(args[0])) {
            try {
                AppPaths paths = AppPaths.forCurrentUser();
                Db db = Db.sqlite(paths.dbFile());
                new Migrator(db).migrate();
                var ctx = new AppContext(paths, db);
                System.out.println("Recovery code for " + args[1] + ": " + ctx.auth.createRecoveryCodeLocal(args[1]));
                System.out.println("Valid for 30 minutes. Use 'Forgot password?' on the sign-in screen.");
            } catch (RuntimeException e) {
                System.err.println("Could not create a recovery code: " + e.getMessage());
                System.exit(1);
            }
            return;
        }
        javafx.application.Application.launch(App.class, args);
    }
}
