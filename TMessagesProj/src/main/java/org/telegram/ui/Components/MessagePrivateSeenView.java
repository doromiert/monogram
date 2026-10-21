package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AppGlobalConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.Date;

public class MessagePrivateSeenView extends FrameLayout {

    public static final int TYPE_SEEN = 0;
    public static final int TYPE_EDIT = 1;
    public static final int TYPE_FORWARD = 2;

    private final int currentAccount;
    private final int type;
    private final Theme.ResourcesProvider resourcesProvider;

    private final LinearLayout valueLayout;
    private final TextView valueTextView;
    private final TextView loadingView;

    private final long dialogId;
    private final int messageId;
    private final int sent_date;
    private final int edit_date;
    private final int fwd_date;

    private final int messageDiff;

    public MessagePrivateSeenView(Context context, int type, @NonNull MessageObject messageObject, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.type = type;

        currentAccount = messageObject.currentAccount;
        this.resourcesProvider = resourcesProvider;
        messageDiff = ConnectionsManager.getInstance(currentAccount).getCurrentTime() - messageObject.messageOwner.date;

        dialogId = messageObject.getDialogId();
        messageId = messageObject.getId();
        sent_date = messageObject.messageOwner == null ? 0 : messageObject.messageOwner.date;
        edit_date = messageObject.messageOwner == null ? 0 : messageObject.messageOwner.edit_date;
        fwd_date = messageObject.messageOwner == null || messageObject.messageOwner.fwd_from == null ? 0 : messageObject.messageOwner.fwd_from.date;

        ImageView iconView = new ImageView(context);
        addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 11, 0, 0, 0));
        int icon;
        if (type == TYPE_EDIT) {
            icon = AppGlobalConfig.getInstance(currentAccount).messagePrimaryEditedDate.get() ?
                R.drawable.outline_message_time_24:
                R.drawable.menu_edited_stamp;
        } else if (type == TYPE_FORWARD) {
            icon = R.drawable.menu_forward_stamp;
        } else if (messageObject.isVoice()) {
            icon = R.drawable.msg_played;
        } else {
            icon = R.drawable.msg_seen;
        }
        Drawable drawable = ContextCompat.getDrawable(context, icon).mutate();
        drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        iconView.setImageDrawable(drawable);

        loadingView = new TextView(context);
        SpannableStringBuilder text = new SpannableStringBuilder("loading text ");
        text.setSpan(new LoadingSpan(loadingView, dp(96), dp(2), resourcesProvider), 0, text.length() - 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        loadingView.setTextColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider), .7f));
        loadingView.setText(text);
        loadingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        addView(loadingView, LayoutHelper.createFrame(96, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 40, -1, 8, 0));

        valueLayout = new LinearLayout(context);
        valueLayout.setOrientation(LinearLayout.HORIZONTAL);
        valueLayout.setAlpha(0f);
        addView(valueLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 38, 0, 8, 0));

        valueTextView = new TextView(context);
        valueTextView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        valueTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        valueLayout.addView(valueTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 0, -1, 0, 0));

        request();
    }

    private void request() {
        if (type == TYPE_EDIT) {
            valueLayout.setAlpha(1f);
            loadingView.setAlpha(0f);

            valueTextView.setText(AppGlobalConfig.getInstance(currentAccount).messagePrimaryEditedDate.get() ?
                LocaleController.formatPmSentDate(sent_date):
                LocaleController.formatPmEditedDate(edit_date));
            return;
        } else if (type == TYPE_FORWARD) {
            valueLayout.setAlpha(1f);
            loadingView.setAlpha(0f);

            valueTextView.setText(LocaleController.formatPmFwdDate(fwd_date));
            return;
        }
        setOnClickListener(null);
        valueLayout.setAlpha(0f);
        loadingView.setAlpha(1f);

        TLRPC.TL_messages_getOutboxReadDate req = new TLRPC.TL_messages_getOutboxReadDate();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.msg_id = messageId;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (err != null) {
                if ("USER_PRIVACY_RESTRICTED".equals(err.text) || "YOUR_PRIVACY_RESTRICTED".equals(err.text)) {
                    valueTextView.setText(LocaleController.getString(R.string.PmReadUnknown));

                } else {
                    valueTextView.setText(LocaleController.getString("UnknownError"));

                    BulletinFactory.of(Bulletin.BulletinWindow.make(getContext()), resourcesProvider).showForError(err);
                }
            } else if (res instanceof TLRPC.TL_outboxReadDate) {
                TLRPC.TL_outboxReadDate r = (TLRPC.TL_outboxReadDate) res;
                valueTextView.setText(LocaleController.formatPmSeenDate(r.date));

            }
            valueLayout.animate().alpha(1f).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(320).start();
            loadingView.animate().alpha(0f).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(320).start();

        }));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (Bulletin.getVisibleBulletin() != null) {
            Bulletin bulletin = Bulletin.getVisibleBulletin();
            if (bulletin.getLayout() != null && bulletin.getLayout().getParent() != null && bulletin.getLayout().getParent().getParent() instanceof Bulletin.BulletinWindow.BulletinWindowLayout) {
                bulletin.hide();
            }
        }
    }

    float minWidth = -1;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        View parent = (View) getParent();
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);

        if (minWidth < 0) {
            minWidth = 0;
            if (type == TYPE_SEEN) {
                final long date = System.currentTimeMillis();
                minWidth = Math.max(minWidth, dp(40 + 96 + 8));
                minWidth = Math.max(minWidth, dp(40 + 8) + valueTextView.getPaint().measureText(LocaleController.getString(R.string.PmReadUnknown)));

                minWidth = Math.max(minWidth, dp(40 + 8) + valueTextView.getPaint().measureText(LocaleController.formatString(R.string.PmReadTodayAt, LocaleController.getInstance().getFormatterDay().format(new Date(date)))));
                if (messageDiff > 60 * 60 * 24) {
                    minWidth = Math.max(minWidth, dp(40 + 8) + valueTextView.getPaint().measureText(LocaleController.formatString(R.string.PmReadYesterdayAt, LocaleController.getInstance().getFormatterDay().format(new Date(date)))));
                }
                if (messageDiff > 60 * 60 * 24 * 2) {
                    minWidth = Math.max(minWidth, dp(40 + 8) + valueTextView.getPaint().measureText(LocaleController.formatString(R.string.PmReadDateTimeAt, LocaleController.getInstance().getFormatterDayMonth().format(new Date(date)), LocaleController.getInstance().getFormatterDay().format(new Date(date)))));
                    minWidth = Math.max(minWidth, dp(40 + 8) + valueTextView.getPaint().measureText(LocaleController.formatString(R.string.PmReadDateTimeAt, LocaleController.getInstance().getFormatterYear().format(new Date(date)), LocaleController.getInstance().getFormatterDay().format(new Date(date)))));
                }
            } else {
                minWidth = dp(40 + 8) + valueTextView.getPaint().measureText(valueTextView.getText().toString());
            }
        }

        if (parent != null && parent.getWidth() > 0) {
            width = parent.getWidth();
            widthMode = MeasureSpec.EXACTLY;
        }
        if (width < minWidth || widthMode == MeasureSpec.AT_MOST) {
            width = (int) minWidth;
            widthMode = MeasureSpec.EXACTLY;
        }

        super.onMeasure(MeasureSpec.makeMeasureSpec(width, widthMode), heightMeasureSpec);
    }
}
