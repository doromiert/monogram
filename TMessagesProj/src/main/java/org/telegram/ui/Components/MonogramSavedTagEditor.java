package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

/** Uses native dialog/edit controls; edits account-local labels without sending XMPP reactions. */
public final class MonogramSavedTagEditor {
    private MonogramSavedTagEditor() {}
    public static void show(Context context,int account,TLRPC.Reaction original,Theme.ResourcesProvider resources) {
        MonogramController controller=MonogramController.get(account);
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(24),0,AndroidUtilities.dp(24),0);
        EditTextBoldCursor name=field(context,resources,LocaleController.getString(R.string.SavedTagLabelPlaceholder));
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        name.setText(MessagesController.getInstance(account).getSavedTagName(original));
        content.addView(name,LayoutHelper.createLinear(-1,48));
        EditTextBoldCursor emoji=field(context,resources,LocaleController.getString(R.string.Emoji));
        emoji.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        emoji.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        if(original instanceof TLRPC.TL_reactionEmoji) emoji.setText(((TLRPC.TL_reactionEmoji)original).emoticon);
        else emoji.setHint(LocaleController.getString(R.string.MonogramTagEmojiHint));
        content.addView(emoji,LayoutHelper.createLinear(-1,48));
        TextView label=new TextView(context);label.setText(LocaleController.getString(R.string.MonogramTagTextColor));label.setTextSize(14);
        label.setTextColor(Theme.getColor(Theme.key_dialogTextBlack,resources));
        content.addView(label,LayoutHelper.createLinear(-1,36));
        int[] selected={controller.savedTagColor(original,0)};
        int[] colors={0,0xffef5350,0xffffa726,0xffe3b340,0xff66bb6a,0xff26a69a,0xff42a5f5,0xffab78e8};
        android.widget.HorizontalScrollView scroll=new android.widget.HorizontalScrollView(context);scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout palette=new LinearLayout(context);scroll.addView(palette);content.addView(scroll,LayoutHelper.createLinear(-1,48));
        for(int color:colors) {
            View swatch=new View(context) {
                final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas canvas) {
                    int fallback=Theme.getColor(Theme.key_dialogTextBlack,resources);
                    paint.setColor(color==0 ? fallback : color);paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(getWidth()/2f,getHeight()/2f,AndroidUtilities.dp(12),paint);
                    if(selected[0]==color) {
                        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(AndroidUtilities.dp(2));
                        canvas.drawCircle(getWidth()/2f,getHeight()/2f,AndroidUtilities.dp(17),paint);
                    }
                }
            };
            swatch.setContentDescription(color==0 ? LocaleController.getString(R.string.Default) : String.format("#%06X",color&0xffffff));
            swatch.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector,resources),1));
            swatch.setOnClickListener(v->{selected[0]=color;name.setTextColor(color==0 ? Theme.getColor(Theme.key_dialogTextBlack,resources) : color);for(int i=0;i<palette.getChildCount();i++)palette.getChildAt(i).invalidate();});
            palette.addView(swatch,LayoutHelper.createLinear(44,48));
        }
        name.setTextColor(selected[0]==0 ? Theme.getColor(Theme.key_dialogTextBlack,resources) : selected[0]);
        TextView error=new TextView(context);error.setTextSize(14);error.setTextColor(Theme.getColor(Theme.key_text_RedRegular,resources));
        content.addView(error,LayoutHelper.createLinear(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(context,resources).setTitle(LocaleController.getString(R.string.MonogramEditSavedTag)).setView(content)
            .setPositiveButton(LocaleController.getString(R.string.Save),(d,which)->{})
            .setNegativeButton(LocaleController.getString(R.string.Cancel),(d,which)->{}).create();
        dialog.setDismissDialogByButtons(false);
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->dialog.dismiss());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String value=emoji.getText().toString().trim();TLRPC.Reaction replacement=original;
                if(!value.isEmpty()) { TLRPC.TL_reactionEmoji chosen=new TLRPC.TL_reactionEmoji();chosen.emoticon=value;replacement=chosen; }
                else if(original instanceof TLRPC.TL_reactionEmoji) {error.setText("Choose an emoji for this tag.");return;}
                v.setEnabled(false);error.setText("");
                controller.editSavedTag(original,replacement,name.getText().toString(),selected[0],failure->{
                    v.setEnabled(true);if(failure==null)dialog.dismiss();else error.setText(failure);
                });
            });
        });
        dialog.setOnDismissListener(d->AndroidUtilities.hideKeyboard(name));dialog.show();
    }
    private static EditTextBoldCursor field(Context context,Theme.ResourcesProvider resources,String hint) {
        EditTextBoldCursor field=new EditTextBoldCursor(context);field.setSingleLine(true);field.setTextSize(18);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack,resources));field.setHintColor(Theme.getColor(Theme.key_groupcreate_hintText,resources));
        field.setHintText(hint);field.setPadding(0,0,0,0);return field;
    }
}
