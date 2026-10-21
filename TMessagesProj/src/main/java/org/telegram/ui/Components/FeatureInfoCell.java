package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/** Shared icon, title and description cell without service integration. */
public class FeatureInfoCell extends FrameLayout {

    private final Theme.ResourcesProvider resourcesProvider;
    private ImageView imageView;
    private LinearLayout textLayout;
    private TextView titleView;
    private TextView textView;

    public FeatureInfoCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        this(context, false, resourcesProvider);
    }

    public FeatureInfoCell(Context context, boolean compact, Theme.ResourcesProvider resourcesProvider) {
        super(context);

        this.resourcesProvider = resourcesProvider;

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), PorterDuff.Mode.SRC_IN));
        addView(imageView, LayoutHelper.createFrame(24, 24, Gravity.TOP | Gravity.LEFT, 20, 2 + 9.46f, 0, 0));

        textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        addView(textLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL, 64, compact ? 2 : 2 + 7.8f, 24, compact ? 4 : 2 + 7.8f));

        titleView = new TextView(context);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP, 0, 0, 0, 1));

        textView = new TextView(context);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP, 0, 0, 0, 0));
    }

    public void set(int iconResId, CharSequence title, CharSequence text) {
        imageView.setImageResource(iconResId);
        titleView.setText(title);
        textView.setText(text);
    }

    public void setText(CharSequence text) {
        textView.setText(text);
    }

    public static class Factory extends UItem.UItemFactory<FeatureInfoCell> {
        static { setup(new Factory()); }

        @Override
        public FeatureInfoCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new FeatureInfoCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((FeatureInfoCell) view).set(item.iconResId, item.text, item.subtext);
        }

        public static UItem as(int iconResId, CharSequence title, CharSequence text) {
            UItem item = UItem.ofFactory(Factory.class);
            item.iconResId = iconResId;
            item.text = title;
            item.subtext = text;
            return item;
        }

        @Override
        public boolean isClickable() {
            return false;
        }
    }
}
