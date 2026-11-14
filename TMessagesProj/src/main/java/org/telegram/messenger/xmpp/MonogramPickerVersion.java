// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;
import android.content.*;import org.telegram.messenger.*;
/** One account-scoped last actual user edit; exports/imports never manufacture user edits. */
public final class MonogramPickerVersion {
 private MonogramPickerVersion(){}
 private static SharedPreferences prefs(){Context context=ApplicationLoader.applicationContext;return context==null?null:context.getSharedPreferences("monogram-picker-revisions",Context.MODE_PRIVATE);}
 private static String owner(int account){Context context=ApplicationLoader.applicationContext;return context==null?"":context.getSharedPreferences("monogram-"+account,Context.MODE_PRIVATE).getString("jid","");}
 public static synchronized long revision(int account){SharedPreferences p=prefs();String owner=owner(account);return p==null||owner.isEmpty()?0:p.getLong(owner,0);}
 public static synchronized void changed(int account){SharedPreferences p=prefs();String owner=owner(account);if(p==null||owner.isEmpty())return;p.edit().putLong(owner,Math.max(System.currentTimeMillis(),revision(account)+1)).apply();}
 public static void changedSelected(){changed(UserConfig.selectedAccount);}
 public static synchronized void observe(int account,long revision){SharedPreferences p=prefs();String owner=owner(account);if(p!=null&&!owner.isEmpty()&&revision>revision(account))p.edit().putLong(owner,revision).apply();}
 /** Baseline1 cannot defeat any real user edit, including delayed first exports from old installs. */
 public static long publication(long revision,long acknowledged,boolean existing){if(revision==0)return existing?0:1;return revision>acknowledged?revision:0;}
}
