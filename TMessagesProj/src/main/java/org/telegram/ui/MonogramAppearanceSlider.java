// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;
import static org.telegram.messenger.AndroidUtilities.dp;

/** Local adaptation of the reference AltSeekbar's dimensions, header and endpoint feedback. */
final class MonogramAppearanceSlider extends FrameLayout {
    private final int max;
    private final AnimatedTextView value;
    private final SeekBarView seek;
    private final String left,right;
    private final TextView start,end;
    private int lastEndpoint=-1,current;
    private java.util.function.IntFunction<CharSequence> formatter;
    MonogramAppearanceSlider(Context context,String title,String left,String right,int max,int current,java.util.function.IntConsumer changed) {
        super(context);this.max=max;this.left=left;this.right=right;
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=new TextView(context);label.setText(title);label.setTextSize(15);label.setTypeface(AndroidUtilities.bold());label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        heading.addView(label,LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,LayoutHelper.WRAP_CONTENT,Gravity.CENTER_VERTICAL));
        value=new AnimatedTextView(context,false,true,true) {
            private final Drawable fill=Theme.createRoundRectDrawable(dp(4),Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader),.15f));
            @Override protected void onDraw(Canvas canvas) {
                fill.setBounds(0,0,(int)(getPaddingLeft()+getDrawable().getCurrentWidth()+getPaddingRight()),getHeight());fill.draw(canvas);super.onDraw(canvas);
            }
        };
        value.setAnimationProperties(.45f,0,240,org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT_QUINT);value.setAllowCancel(true);
        value.setTypeface(AndroidUtilities.bold());value.setTextSize(dp(12));value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));value.setPadding(dp(5.33f),dp(2),dp(5.33f),dp(2));
        heading.addView(value,LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,17,Gravity.CENTER_VERTICAL,6,1,0,0));
        addView(heading,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT,Gravity.TOP,21,17,21,0));
        FrameLayout ends=new FrameLayout(context);
        start=new TextView(context);end=new TextView(context);
        for(TextView text:new TextView[]{start,end}) {text.setTextSize(13);text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));}
        start.setText(left);end.setText(right);
        ends.addView(start,LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT,LayoutHelper.WRAP_CONTENT,Gravity.LEFT));ends.addView(end,LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT,LayoutHelper.WRAP_CONTENT,Gravity.RIGHT));
        addView(ends,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT,Gravity.TOP,21,52,21,0));
        seek=new SeekBarView(context,true,null);seek.setReportChanges(true);seek.setOuterColor(Theme.getColor(Theme.key_player_progress));seek.setInnerColor(Theme.getColor(Theme.key_player_progressBackground));
        seek.setDelegate((stop,progress) -> {
            int next=Math.round(progress*max);updateValue(next);
            int endpoint=next==0?0:next==max?max:-1;
            if(endpoint>=0 && endpoint!=lastEndpoint) AndroidUtilities.vibrateCursor(this);lastEndpoint=endpoint;
            changed.accept(next);
        });
        addView(seek,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,org.telegram.utils.settings.MonogramAppearance.slider.get()?56:44,Gravity.TOP,6,68,6,0));
        seek.setProgress(current/(float)max);updateValue(current);
    }
    void updateValue(int current) {
        this.current=current;
        CharSequence label=current==0?left:current==max?right:formatter==null?Integer.toString(current):formatter.apply(current);
        value.setText(label.toString().toUpperCase(java.util.Locale.getDefault()),true);
        int gray=Theme.getColor(Theme.key_windowBackgroundWhiteGrayText),blue=Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        float progress=current/(float)max;
        start.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(gray,blue,Math.max(0,1-progress*4)));
        end.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(gray,blue,Math.max(0,progress*4-3)));
    }
    MonogramAppearanceSlider setValueFormatter(java.util.function.IntFunction<CharSequence> formatter) {
        this.formatter=formatter;updateValue(current);return this;
    }
    void refreshStyle() {
        android.view.ViewGroup.LayoutParams params=seek.getLayoutParams();
        params.height=dp(org.telegram.utils.settings.MonogramAppearance.slider.get()?56:44);
        seek.setLayoutParams(params);seek.requestLayout();invalidate();
    }
    @Override protected void onMeasure(int w,int h) {super.onMeasure(w,MeasureSpec.makeMeasureSpec(dp(112),MeasureSpec.EXACTLY));}
}
