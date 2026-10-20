// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;

/** Stable exteraGram action order and IDs; an empty preference disables the gesture. */
public enum MonogramSwipeAction {
    REACTION(1,R.drawable.msg_reactions2,1f), REPLY(2,R.drawable.menu_reply,1.09f),
    COPY(3,R.drawable.msg_copy,1f), FORWARD(4,R.drawable.msg_forward,1.09f),
    EDIT(5,R.drawable.msg_edit,1f), SAVE(6,R.drawable.msg_saved,1f),
    REPEAT(7,R.drawable.monogram_double_tap_repeat,1.09f), DELETE(8,R.drawable.msg_delete,1.09f),
    TRANSLATE(9,R.drawable.msg_translate,1.09f);

    public final int actionId,iconRes;
    public final float iconTrim;
    MonogramSwipeAction(int id,int icon,float trim) { actionId=id;iconRes=icon;iconTrim=trim; }
    public static MonogramSwipeAction of(int id) {
        for(MonogramSwipeAction action:values()) if(action.actionId==id) return action;
        return null;
    }
    public static List<MonogramSwipeAction> enabled(int account) {
        return parse(MessagesController.getMainSettings(account).getString("monogram-extera-swipe-actions","2"));
    }
    static List<MonogramSwipeAction> parse(String value) {
        ArrayList<MonogramSwipeAction> result=new ArrayList<>();
        if(value!=null) for(String item:value.split(",")) try {
            MonogramSwipeAction action=of(Integer.parseInt(item.trim()));
            if(action!=null && !result.contains(action)) result.add(action);
        } catch(NumberFormatException ignored) { }
        return result;
    }
    public static List<MonogramSwipeAction> disabled(int account) {
        ArrayList<MonogramSwipeAction> result=new ArrayList<>(Arrays.asList(values()));
        result.removeAll(enabled(account));return result;
    }
    public void setEnabled(int account,boolean enabled) {
        List<MonogramSwipeAction> ordered=enabled(account);
        if(enabled==ordered.contains(this)) return;
        if(!enabled) ordered.remove(this);
        else {
            int at=0;
            for(MonogramSwipeAction action:values()) { if(action==this) break;if(ordered.contains(action)) at++; }
            ordered.add(at,this);
        }
        setEnabled(account,ordered);
    }
    public static void setEnabled(int account,List<MonogramSwipeAction> actions) {
        StringBuilder value=new StringBuilder();List<MonogramSwipeAction> unique=new ArrayList<>();
        for(MonogramSwipeAction action:actions) if(action!=null && !unique.contains(action)) {
            if(value.length()>0) value.append(',');value.append(action.actionId);unique.add(action);
        }
        MessagesController.getMainSettings(account).edit().putString("monogram-extera-swipe-actions",value.toString()).apply();
    }
}
