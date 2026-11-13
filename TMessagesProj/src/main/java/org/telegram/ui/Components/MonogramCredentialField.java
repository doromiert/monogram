// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Components;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.view.View;

/** Only credential forms opt in; chat, wallet and other rich editors stay excluded. */
public final class MonogramCredentialField extends EditTextBoldCursor {
    public MonogramCredentialField(Context context) {
        super(context);
        if (Build.VERSION.SDK_INT >= 26) setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
    }
    @TargetApi(26)
    @Override public int getAutofillType() { return AUTOFILL_TYPE_TEXT; }
}
