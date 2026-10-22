package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

/** Compact native picker for account-local chat-folder icons. */
public final class FolderIconPickerSheet extends BottomSheet {

    public interface Delegate {
        void onIconSelected(int drawableRes);
    }

    public static final int[] ICONS;
    static {
        int[] choices=org.telegram.messenger.xmpp.MonogramFolderIcons.choices();
        ICONS=new int[choices.length+1];
        System.arraycopy(choices,0,ICONS,1,choices.length);
    }
    private static final String[] LABELS={"Default folder icon","Cat","Book","Money","Games","Ideas","Like",
            "Music","Art","Travel","Sports","Favorites","Study","Airplane","Private chats","Groups",
            "All chats","Unread","Bots","Crown","Flower","Home","Love","Theatre","Party","Trading",
            "Work","Unmuted","Channels","Folder","Clipboard"};

    public static void show(BaseFragment fragment, int selected, Delegate delegate) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        new FolderIconPickerSheet(fragment, selected, delegate).show();
    }

    private FolderIconPickerSheet(BaseFragment fragment, int selected, Delegate delegate) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        final Context context = fragment.getParentActivity();

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), dp(18));

        TextView title = new TextView(context);
        title.setText("Folder icon");
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
        title.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 12, 0, 12, 0));

        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(5);
        grid.setRowCount((ICONS.length+4)/5);
        android.widget.ScrollView scroll=new android.widget.ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(grid,new android.widget.ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT));
        content.addView(scroll,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,Math.min(384,((ICONS.length+4)/5)*64)));

        for (int i = 0; i < ICONS.length; i++) {
            final int value = ICONS[i];
            FrameLayout cell = new FrameLayout(context);
            cell.setBackground(Theme.createSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector, fragment.getResourceProvider()),
                    Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            cell.setContentDescription(LABELS[i] + (selected == value ? ", selected" : ""));
            cell.setFocusable(true);

            ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setImageResource(value == 0 ? R.drawable.msg_folders : value);
            final int iconColor = selected == value
                    ? Theme.getColor(Theme.key_featuredStickers_buttonText, fragment.getResourceProvider())
                    : Theme.getColor(Theme.key_dialogIcon, fragment.getResourceProvider());
            icon.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.MULTIPLY));
            if (selected == value) {
                icon.setBackground(Theme.createCircleDrawable(dp(44),
                        Theme.getColor(Theme.key_featuredStickers_addButton, fragment.getResourceProvider())));
            }
            cell.addView(icon, LayoutHelper.createFrame(48, 48, Gravity.CENTER));
            cell.setOnClickListener(v -> {
                delegate.onIconSelected(value);
                dismiss();
            });

            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 5), GridLayout.spec(i % 5, 1f));
            params.width = 0;
            params.height = dp(64);
            grid.addView(cell, params);
        }

        setCustomView(content);
    }
}
