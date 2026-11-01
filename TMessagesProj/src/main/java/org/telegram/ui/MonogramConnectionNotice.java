// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.widget.FrameLayout;
import java.util.function.IntConsumer;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

/** One non-expiring reconnect action for the foreground account, without a polling timer. */
final class MonogramConnectionNotice {
    private final FrameLayout container;
    private final IntConsumer reconnect;
    private Bulletin bulletin;
    private int account = -1;

    MonogramConnectionNotice(FrameLayout container, IntConsumer reconnect) {
        this.container = container;
        this.reconnect = reconnect;
    }

    void update(int selected, boolean active, int state) {
        if (selected != account) hide();
        account = selected;
        if (!active || state != ConnectionsManager.ConnectionStateWaitingForNetwork) {
            hide();
            return;
        }
        if (bulletin != null && bulletin.isShowing()) return;
        Bulletin notice = BulletinFactory.of(container, null).createSimpleBulletin(0,
                LocaleController.getString(R.string.MonogramDisconnected),
                LocaleController.getString(R.string.MonogramReconnect), -1, () -> {
                    if (account != selected || bulletin == null) return;
                    hide();
                    reconnect.accept(selected);
                });
        bulletin = notice;
        notice.hideAfterBottomSheet(false).show(true);
    }

    void hide() {
        Bulletin previous = bulletin;
        bulletin = null;
        if (previous != null) previous.hide();
    }
}
