package org.telegram.messenger.xmpp;

import java.io.File;

public final class MonogramBranding {
    private MonogramBranding() {}
    /** Keep existing cache paths readable; fresh installations use Monogram folder names. */
    public static File mediaDirectory(File parent,String legacyName) {
        File legacy=new File(parent,legacyName);
        return legacy.isDirectory() ? legacy : new File(parent,legacyName.replace("Telegram","Monogram"));
    }
}
