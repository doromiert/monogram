package org.telegram.ui.web;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralStringComma;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Text;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class WebBrowserSettings extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private Drawable addIcon;

    private Utilities.Callback<BrowserHistory.Entry> whenHistoryClicked;
    public WebBrowserSettings(Utilities.Callback<BrowserHistory.Entry> whenHistoryClicked) {
        this.whenHistoryClicked = whenHistoryClicked;
    }

    private long cacheSize, cookiesSize, historySize;

    @Override
    public boolean onFragmentCreate() {
        loadSizes();
        getNotificationCenter().addObserver(this, NotificationCenter.webBrowserSettingsUpdate);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.webBrowserSettingsUpdate);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.webBrowserSettingsUpdate) {
            if (listView != null) {
                /*
                if (enableRow != -1) {
                    View view = listView.findViewByPosition(enableRow);
                    if (view instanceof TextCheckCell) {
                        final boolean inAppBrowserEnabled = getMessagesController().isWebBrowserInAppEnabled();
                        ((TextCheckCell) view).setChecked(inAppBrowserEnabled);
                        ((TextCheckCell) view).setBackgroundColorAnimated(inAppBrowserEnabled, Theme.getColor(inAppBrowserEnabled ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked));
                    }
                }
                */

                listView.adapter.update(true);
            }
        }
    }

    private void loadSizes() {
        ArrayList<BrowserHistory.Entry> history = BrowserHistory.getHistory(loadedHistory -> {
            this.historySize = loadedHistory.size();
            if (listView != null && listView.adapter != null && listView.isAttachedToWindow()) {
                listView.adapter.update(true);
            }
        });
        if (history != null) {
            this.historySize = history.size();
            if (listView != null && listView.adapter != null && listView.isAttachedToWindow()) {
                listView.adapter.update(true);
            }
        }

        Utilities.globalQueue.postRunnable(() -> {
            long cacheSize = 0;
            File cache1 = ApplicationLoader.applicationContext.getDatabasePath("webview.db");
            if (cache1 != null && cache1.exists()) {
                cacheSize += cache1.length();
            }
            File cache2 = ApplicationLoader.applicationContext.getDatabasePath("webviewCache.db");
            if (cache2 != null && cache2.exists()) {
                cacheSize += cache2.length();
            }
            File dir = new File(ApplicationLoader.applicationContext.getApplicationInfo().dataDir, "app_webview");
            if (dir.exists()) {
                cacheSize += getDirectorySize(dir, false);
            }
            File dir2 = new File(ApplicationLoader.applicationContext.getApplicationInfo().dataDir, "cache/WebView");
            if (dir2.exists()) {
                cacheSize += getDirectorySize(dir2, null);
            }

            long cookieSize = 0;
            File dir3 = new File(ApplicationLoader.applicationContext.getApplicationInfo().dataDir, "app_webview");
            if (dir3.exists()) {
                cookieSize += getDirectorySize(dir3, true);
            }

            final long finalCacheSize = cacheSize;
            final long finalCookiesSize = cookieSize;

            AndroidUtilities.runOnUIThread(() -> {
                this.cacheSize = finalCacheSize;
                this.cookiesSize = finalCookiesSize;
                if (listView != null && listView.adapter != null && listView.isAttachedToWindow()) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    @Override
    public View createView(Context context) {

//        if (parentLayout != null && parentLayout.isSheet()) {
//            actionBar.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
//            actionBar.setActionModeColor(Theme.getColor(Theme.key_windowBackgroundWhite));
//            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
//            actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
//            actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_actionBarActionModeDefaultSelector), false);
//            actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
//            actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), true);
//            actionBar.setCastShadows(true);
//        }

        Drawable drawable1 = context.getResources().getDrawable(R.drawable.poll_add_circle).mutate();
        Drawable drawable2 = context.getResources().getDrawable(R.drawable.poll_add_plus).mutate();
        drawable1.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_switchTrackChecked), PorterDuff.Mode.MULTIPLY));
        drawable2.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_checkboxCheck), PorterDuff.Mode.MULTIPLY));
        addIcon = new CombinedDrawable(drawable1, drawable2) {
            { translateX = dp(2); }
            @Override
            public void setColorFilter(ColorFilter colorFilter) {

            }
        };
        updateAddIconColors();

        fragmentView = super.createView(context);

        listView.setSections();
        actionBar.setAdaptiveBackground(listView);

        // ((ViewGroup) fragmentView).addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        // listView.setPadding(0, AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight(), 0, 0);
        // actionBar.setAddToContainer(false);

        return fragmentView;
    }

    @Override
    public boolean isLightStatusBar() {
//        if (parentLayout != null && parentLayout.isSheet()) {
//            return AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundWhite)) > .721f;
//        }
        return super.isLightStatusBar();
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.BrowserSettingsTitle);
    }

    public static final int BUTTON_TOGGLE = 1;
    public static final int BUTTON_CLEAR_CACHE = 2;
    public static final int BUTTON_CLEAR_COOKIES = 3;
    public static final int BUTTON_CLEAR_LIST = 5;
    public static final int BUTTON_SEARCH_ENGINE = 6;
    public static final int BUTTON_CLEAR_HISTORY = 7;
    public static final int BUTTON_OPEN_HISTORY = 9;
    public static final int BUTTON_CUSTOMTABS_ON = 10;
    public static final int BUTTON_CUSTOMTABS_OFF = 11;
    public static final int BUTTON_ADD_IN_APP_EXCEPTION = 15;
    public static final int BUTTON_ADD_EXTERNAL_EXCEPTION = 16;
    public static final int BUTTON_BROWSER_CLOSE_BUTTON = 17;

    public int enableRow;
    public int clearCookiesRow;
    public int clearCacheRow;
    public int historyRow;
    public int clearHistoryRow;
    public int neverOpenRow;
    public int clearListRow;
    public int searchRow;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        enableRow = -1;
        clearCookiesRow = -1;
        clearCacheRow = -1;
        historyRow = -1;
        clearHistoryRow = -1;
        clearListRow = -1;
        searchRow = -1;

        final boolean inAppBrowserEnabled = getMessagesController().isWebBrowserInAppEnabled();

        enableRow = items.size();
        items.add(UItem.asRippleCheck(BUTTON_TOGGLE, getString(R.string.BrowserSettingsEnable)).setChecked(inAppBrowserEnabled));
        items.add(UItem.asShadow(LocaleController.getString(R.string.BrowserSettingsEnableInfo)));
        if (!inAppBrowserEnabled) {
            final boolean customTabs = getMessagesController().isWebBrowserUseCustomTabs();

            /*
            items.add(UItem.asHeader(getString(R.string.BrowserSettingsCustomTabsTitle)));
            items.add(UItem.asRadio(BUTTON_CUSTOMTABS_ON, getString(R.string.BrowserSettingsCustomTabs)).setChecked(customTabs));
            items.add(UItem.asRadio(BUTTON_CUSTOMTABS_OFF, getString(R.string.BrowserSettingsNoCustomTabs)).setChecked(!customTabs));
            items.add(UItem.asShadow(getString(R.string.BrowserSettingsNoCustomTabsInfo)));
            */

            items.add(UItem.asCheck(BUTTON_BROWSER_CLOSE_BUTTON, getString(R.string.WebBrowserShowCloseButton)).setChecked(getMessagesController().isWebBrowserUseCustomTabs()));
            items.add(UItem.asShadow(LocaleController.getString(R.string.WebBrowserShowCloseButtonInfo)));

            items.add(UItem.asHeader(LocaleController.getString(R.string.BrowserSettingsAlwaysOpenInTitle2)));
            neverOpenRow = items.size();
            items.add(UItem.asButton(BUTTON_ADD_EXTERNAL_EXCEPTION, addIcon, LocaleController.getString(R.string.BrowserSettingsNeverOpenInAdd)).accent());
            final List<TL_account.WebDomainException> exceptions = getMessagesController().getWebBrowserExceptionsList(false);
            for (TL_account.WebDomainException exception : exceptions) {
                items.add(WebsiteView.Factory.as(exception.domain, exception.title, exception.favicon));
            }

            items.add(UItem.asShadow(LocaleController.getString(R.string.BrowserSettingsAlwaysOpenInInfo2)));
            if (!exceptions.isEmpty()) {
                clearListRow = items.size();
                items.add(UItem.asButton(BUTTON_CLEAR_LIST, LocaleController.getString(R.string.BrowserSettingsNeverOpenInClearList2)).red());
                items.add(UItem.asShadow(null));
            }
        } else {
            clearCookiesRow = items.size();
            items.add(UItem.asButton(BUTTON_CLEAR_COOKIES, R.drawable.menu_clear_cookies, LocaleController.getString(R.string.BrowserSettingsCookiesClear), cookiesSize > 0 ? AndroidUtilities.formatFileSize(cookiesSize) : ""));
            clearCacheRow = items.size();
            items.add(UItem.asButton(BUTTON_CLEAR_CACHE, R.drawable.menu_clear_cache, LocaleController.getString(R.string.BrowserSettingsCacheClear), cacheSize > 0 ? AndroidUtilities.formatFileSize(cacheSize) : ""));
            items.add(UItem.asShadow(getString(R.string.BrowserSettingsCookiesInfo)));
            if (historySize > 0) {
                historyRow = items.size();
                items.add(UItem.asButton(BUTTON_OPEN_HISTORY, R.drawable.menu_clear_recent, getString(R.string.BrowserSettingsHistoryShow)));
                clearHistoryRow = items.size();
                items.add(UItem.asButton(BUTTON_CLEAR_HISTORY, R.drawable.menu_clear_cache, getString(R.string.BrowserSettingsHistoryClear), formatPluralStringComma("BrowserSettingsHistoryPages", (int) historySize, ',')));
                items.add(UItem.asShadow(null));
            }

            items.add(UItem.asHeader(LocaleController.getString(R.string.BrowserSettingsNeverOpenInTitle2)));
            neverOpenRow = items.size();
            items.add(UItem.asButton(BUTTON_ADD_IN_APP_EXCEPTION, addIcon, LocaleController.getString(R.string.BrowserSettingsNeverOpenInAdd)).accent());
            final List<TL_account.WebDomainException> exceptions = getMessagesController().getWebBrowserExceptionsList(true);
            for (TL_account.WebDomainException exception : exceptions) {
                items.add(WebsiteView.Factory.as(exception.domain, exception.title, exception.favicon));
            }

            items.add(UItem.asShadow(LocaleController.getString(R.string.BrowserSettingsNeverOpenInInfo2)));
            if (!exceptions.isEmpty()) {
                clearListRow = items.size();
                items.add(UItem.asButton(BUTTON_CLEAR_LIST, LocaleController.getString(R.string.BrowserSettingsNeverOpenInClearList2)).red());
                items.add(UItem.asShadow(null));
            }

            searchRow = items.size();
            items.add(UItem.asButton(BUTTON_SEARCH_ENGINE, R.drawable.msg_search, LocaleController.getString(R.string.SearchEngine), SearchEngine.getCurrent().name));
            items.add(UItem.asShadow(LocaleController.getString(R.string.BrowserSettingsSearchEngineInfo)));

            if (BuildVars.DEBUG_PRIVATE_VERSION) {
                items.add(UItem.asCheck(12, "adaptable colors").setChecked(SharedConfig.adaptableColorInBrowser));
                items.add(UItem.asCheck(13, "only local IV").setChecked(SharedConfig.onlyLocalInstantView));
            }
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 12) {
            SharedConfig.toggleBrowserAdaptableColors();
            ((TextCheckCell) view).setChecked(SharedConfig.adaptableColorInBrowser);
        } else if (item.id == 13) {
            SharedConfig.toggleLocalInstantView();
            ((TextCheckCell) view).setChecked(SharedConfig.onlyLocalInstantView);
        } else if (item.id == BUTTON_BROWSER_CLOSE_BUTTON) {
            final boolean newUseCustomTabs = !getMessagesController().isWebBrowserUseCustomTabs();

            getMessagesController().toggleWebBrowserUseCustomTabs(newUseCustomTabs);
            ((TextCheckCell) view).setChecked(newUseCustomTabs);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_TOGGLE) {
            getMessagesController().toggleWebBrowserInAppEnabled();
            final boolean inAppBrowserEnabled = getMessagesController().isWebBrowserInAppEnabled();

            ((TextCheckCell) view).setChecked(inAppBrowserEnabled);
            ((TextCheckCell) view).setBackgroundColorAnimated(inAppBrowserEnabled, getThemedColor(inAppBrowserEnabled ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked));
            listView.adapter.update(true);
        } else if (item.id == BUTTON_CUSTOMTABS_ON) {
            getMessagesController().toggleWebBrowserUseCustomTabs(true);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_CUSTOMTABS_OFF) {
            getMessagesController().toggleWebBrowserUseCustomTabs(false);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_CLEAR_CACHE) {
            new AlertDialog.Builder(getContext(), getResourceProvider())
                .setTitle(getString(R.string.BrowserSettingsCacheClear))
                .setMessage(formatString(R.string.BrowserSettingsCacheClearText, cacheSize == 0 ? "" : " (" + AndroidUtilities.formatFileSize(cacheSize)+")"))
                .setPositiveButton(getString(R.string.Clear), (di, w) -> {
                    ApplicationLoader.applicationContext.deleteDatabase("webview.db");
                    ApplicationLoader.applicationContext.deleteDatabase("webviewCache.db");
                    WebStorage.getInstance().deleteAllData();
                    try {
                        WebView webView = new WebView(getContext());
                        webView.clearCache(true);
                        webView.clearHistory();
                        webView.destroy();
                    } catch (Exception e) {}
                    try {
                        File dir = new File(ApplicationLoader.applicationContext.getApplicationInfo().dataDir, "app_webview");
                        if (dir.exists()) {
                            deleteDirectory(dir, false);
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    try {
                        File dir = new File(ApplicationLoader.applicationContext.getApplicationInfo().dataDir, "cache/WebView");
                        if (dir.exists()) {
                            deleteDirectory(dir, null);
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    WebMetadataCache.getInstance().clear();
                    loadSizes();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
        } else if (item.id == BUTTON_CLEAR_COOKIES) {
            new AlertDialog.Builder(getContext(), getResourceProvider())
                .setTitle(getString(R.string.BrowserSettingsCookiesClear))
                .setMessage(formatString(R.string.BrowserSettingsCookiesClearText, cookiesSize == 0 ? "" : " (" + AndroidUtilities.formatFileSize(cookiesSize)+")"))
                .setPositiveButton(getString(R.string.Clear), (di, w) -> {
                    CookieManager cookieManager = CookieManager.getInstance();
                    cookieManager.removeAllCookies(null);
                    cookieManager.flush();
                    try {
                        File dir = new File(ApplicationLoader.applicationContext.getApplicationInfo().dataDir, "app_webview");
                        if (dir.exists()) {
                            deleteDirectory(dir, true);
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    loadSizes();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
        } else if (item.id == BUTTON_CLEAR_HISTORY) {
            long firstDate = Long.MAX_VALUE;
            ArrayList<BrowserHistory.Entry> entries = BrowserHistory.getHistory();
            for (BrowserHistory.Entry e : entries) {
                firstDate = Math.min(firstDate, e.time);
            }
            new AlertDialog.Builder(getContext(), getResourceProvider())
                .setTitle(getString(R.string.BrowserSettingsHistoryClear))
                .setMessage(formatString(R.string.BrowserSettingsHistoryClearText, LocaleController.formatDateChat(firstDate / 1000L)))
                .setPositiveButton(getString(R.string.Clear), (di, w) -> {
                    BrowserHistory.clearHistory();
                    historySize = 0;
                    listView.adapter.update(true);
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
        } else if (item.id == BUTTON_OPEN_HISTORY) {
            final HistoryFragment[] fragment = new HistoryFragment[] { null };
            fragment[0] = new HistoryFragment(null, e -> {
                fragment[0].finishFragment();
                if (whenHistoryClicked != null) {
                    finishFragment();
                    whenHistoryClicked.run(e);
                } else {
                    Browser.openUrl(getContext(), e.url);
                }
            });
            presentFragment(fragment[0]);
        } else if (item.id == BUTTON_CLEAR_LIST) {
            new AlertDialog.Builder(getContext(), getResourceProvider())
                .setTitle(getString(R.string.WebBrowserDeleteAllExceptionsTitle))
                .setMessage(getString(R.string.WebBrowserDeleteAllExceptionsMessage))
                .setPositiveButton(getString(R.string.Delete), (di, w) -> {
                    getMessagesController().clearAllWebBrowserExceptions();
                    listView.adapter.update(true);
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
        } else if (item.instanceOf(WebsiteView.Factory.class)) {
            final WebsiteView websiteView = (WebsiteView) view;
            final String domain = websiteView.domain;
            ItemOptions.makeOptions((ViewGroup) fragmentView, websiteView)
                .setDimAlpha(40)
                .add(R.drawable.menu_delete_old, LocaleController.getString(R.string.Remove), () -> {
                    getMessagesController().removeWebBrowserException(domain);
                    listView.adapter.update(true);
                })
                .show();
        } else if (item.id == BUTTON_SEARCH_ENGINE) {
            if (getParentActivity() == null) {
                return;
            }
            AtomicReference<Dialog> dialogRef = new AtomicReference<>();

            LinearLayout linearLayout = new LinearLayout(getContext());
            linearLayout.setOrientation(LinearLayout.VERTICAL);

            ArrayList<SearchEngine> searchEngines = SearchEngine.getSearchEngines();
            CharSequence[] items = new CharSequence[ searchEngines.size() ];

            for (int i = 0; i < items.length; ++i) {
                final SearchEngine engine = searchEngines.get(i);
                items[i] = engine.name;

                final int index = i;
                RadioColorCell cell = new RadioColorCell(getParentActivity());
                cell.setPadding(dp(4), 0, dp(4), 0);
                cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
                cell.setTextAndValue(items[index], index == SharedConfig.searchEngineType);
                cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
                linearLayout.addView(cell);
                cell.setOnClickListener(v -> {
                    SharedConfig.setSearchEngineType(index);
                    ((TextCell) view).setValue(SearchEngine.getCurrent().name, true);
                    dialogRef.get().dismiss();
                });
            }

            Dialog dialog = new AlertDialog.Builder(getParentActivity())
                    .setTitle(getString(R.string.SearchEngine))
                    .setView(linearLayout)
                    .setNegativeButton(getString(R.string.Cancel), null)
                    .create();
            dialogRef.set(dialog);
            showDialog(dialog);
        } else if (item.id == BUTTON_ADD_IN_APP_EXCEPTION || item.id == BUTTON_ADD_EXTERNAL_EXCEPTION) {
            final boolean inAppBrowserEnabled = getMessagesController().isWebBrowserInAppEnabled();
            if (getMessagesController().isWebBrowserExceptionsLimitReached(inAppBrowserEnabled)) {
                AlertsCreator.showSimpleAlert(this,
                    getString(R.string.WebBrowserExceptionsLimitTitle),
                    getString(R.string.WebBrowserExceptionsLimitMessage));
            } else {
                AlertsCreator.showAddBrowserException(getContext(), getResourceProvider(), inAppBrowserEnabled, domain -> {
                    getMessagesController().addWebBrowserException(domain, inAppBrowserEnabled);
                    listView.adapter.update(true);
                });
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        ThemeDescription.ThemeDescriptionDelegate colorDelegate = this::updateVisibleColors;

        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, colorDelegate, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_settings_listSelector));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{HeaderCell.class, TextCell.class, TextCheckCell.class, WebsiteView.class}, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackBlue));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackBlueChecked));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackBlueThumb));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackBlueThumbChecked));
        for (int key : new int[]{Theme.key_windowBackgroundWhiteBlackText, Theme.key_windowBackgroundWhiteBlueText4, Theme.key_text_RedRegular}) {
            descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKTAG, new Class[]{TextCell.class}, new String[]{"textView"}, null, null, null, key));
        }
        for (int key : new int[]{Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlueText4, Theme.key_text_RedBold}) {
            descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKTAG, new Class[]{TextCell.class}, new String[]{"imageView"}, null, null, null, key));
        }
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LINKCOLOR, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteLinkText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{WebsiteView.class}, new String[]{"titleView"}, null, null, colorDelegate, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{WebsiteView.class}, new String[]{"subtitleView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{WebsiteView.class}, new String[]{"optionsView"}, null, null, colorDelegate, Theme.key_windowBackgroundWhiteGrayText3));
        descriptions.add(new ThemeDescription(null, 0, null, null, null, colorDelegate, Theme.key_windowBackgroundChecked));
        descriptions.add(new ThemeDescription(null, 0, null, null, null, colorDelegate, Theme.key_windowBackgroundUnchecked));
        descriptions.add(new ThemeDescription(null, 0, null, null, null, colorDelegate, Theme.key_windowBackgroundCheckText));
        descriptions.add(new ThemeDescription(null, 0, null, null, null, colorDelegate, Theme.key_switchTrackChecked));
        descriptions.add(new ThemeDescription(null, 0, null, null, null, colorDelegate, Theme.key_checkboxCheck));

        return descriptions;
    }

    private void updateVisibleColors() {
        updateAddIconColors();
        if (listView == null) {
            return;
        }
        listView.forAllChild(this::updateBrowserRowColors);
        for (int i = 0; i < listView.getCachedChildCount(); i++) {
            updateBrowserRowColors(listView.getCachedChildAt(i));
        }
        listView.invalidate();
    }

    private void updateBrowserRowColors(View child) {
        if (child instanceof TextCell) {
            ((TextCell) child).updateColors();
        } else if (child instanceof TextCheckCell && ((TextCheckCell) child).itemId == BUTTON_TOGGLE) {
            TextCheckCell checkCell = (TextCheckCell) child;
            checkCell.setColors(
                    Theme.key_windowBackgroundCheckText,
                    Theme.key_switchTrackBlue,
                    Theme.key_switchTrackBlueChecked,
                    Theme.key_switchTrackBlueThumb,
                    Theme.key_switchTrackBlueThumbChecked
            );
            checkCell.setBackgroundColor(getThemedColor(checkCell.isChecked() ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked));
            checkCell.invalidate();
        } else if (child instanceof WebsiteView) {
            ((WebsiteView) child).updateColors();
        }
    }

    private void updateAddIconColors() {
        if (!(addIcon instanceof CombinedDrawable)) {
            return;
        }
        CombinedDrawable icon = (CombinedDrawable) addIcon;
        icon.getBackground().setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_switchTrackChecked), PorterDuff.Mode.MULTIPLY));
        icon.getIcon().setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_checkboxCheck), PorterDuff.Mode.MULTIPLY));
    }

    public static class WebsiteView extends FrameLayout implements Theme.Colorable {
        public final ImageView imageView;
        public final TextView titleView;
        public final TextView subtitleView;
        public final ImageView optionsView;
        private final Theme.ResourcesProvider resourcesProvider;

        public WebsiteView(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;

            imageView = new ImageView(context);
            addView(imageView, LayoutHelper.createFrameRelatively(32, 32, Gravity.CENTER_VERTICAL | Gravity.START, 16, 0, 0, 0));

            titleView = new TextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setMaxLines(1);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(titleView, LayoutHelper.createFrameRelatively(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 64 + 4, 7, 54, 0));

            subtitleView = new TextView(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    subtitleView.setPivotY(getMeasuredHeight() / 2f);
                    subtitleView.setPivotX(LocaleController.isRTL ? getMeasuredWidth() : 0);
                }
            };
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setMaxLines(1);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(subtitleView, LayoutHelper.createFrameRelatively(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 64 + 4, 30, 54, 0));

            optionsView = new ImageView(context);
            optionsView.setScaleType(ImageView.ScaleType.CENTER);
            optionsView.setImageResource(R.drawable.ic_ab_other);
            optionsView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider), PorterDuff.Mode.SRC_IN));
            addView(optionsView, LayoutHelper.createFrameRelatively(32, 32, Gravity.CENTER_VERTICAL | Gravity.END, 0, 0, 18, 0));
        }

        private AnimatedEmojiDrawable animatedEmojiDrawable;
        private String domain;
        private String fallbackText = "";
        private boolean needDivider;
        public void set(
            CharSequence title,
            String domain,
            long favicon,
            boolean divider
        ) {
            titleView.setText(title);
            subtitleView.setText(domain);
            if (TextUtils.isEmpty(title)) {
                subtitleView.setTranslationY(-dp(14));
                subtitleView.setScaleX(1.3f);
                subtitleView.setScaleY(1.3f);
            } else {
                subtitleView.setTranslationY(0);
                subtitleView.setScaleX(1f);
                subtitleView.setScaleY(1f);
            }
            this.domain = domain;
            fallbackText = (TextUtils.isEmpty(title) ? domain.isEmpty() || TextUtils.isEmpty(domain) ? "" : domain : title).toString();

            if (animatedEmojiDrawable != null) {
                animatedEmojiDrawable.removeView(imageView);
                animatedEmojiDrawable = null;
            }

            if (favicon != 0) {
                animatedEmojiDrawable = AnimatedEmojiDrawable.make(UserConfig.selectedAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES_LARGE, favicon);
                animatedEmojiDrawable.addView(imageView);
                imageView.setImageDrawable(animatedEmojiDrawable);
            } else {
                setFallbackIcon();
            }
            needDivider = divider;
            invalidate();
        }

        private void setFallbackIcon() {
            CombinedDrawable drawable = new CombinedDrawable(
                    Theme.createRoundRectDrawable(dp(6), Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), .1f)),
                    new Drawable() {
                        private final Text text = new Text(fallbackText.substring(0, !fallbackText.isEmpty() ? 1 : 0), 14, AndroidUtilities.bold());
                        @Override
                        public void draw(@NonNull Canvas canvas) {
                            text.draw(canvas, getBounds().centerX() - text.getCurrentWidth() / 2f, getBounds().centerY(), Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), 1f);
                        }
                        @Override
                        public void setAlpha(int alpha) {}
                        @Override
                        public void setColorFilter(@Nullable ColorFilter colorFilter) {}
                        @Override
                        public int getOpacity() {
                            return PixelFormat.TRANSPARENT;
                        }
                    }
                );
            drawable.setCustomSize(dp(28), dp(28));
            imageView.setImageDrawable(drawable);
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            optionsView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider), PorterDuff.Mode.SRC_IN));
            if (animatedEmojiDrawable == null) {
                setFallbackIcon();
            }
            invalidate();
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            super.dispatchDraw(canvas);
            if (needDivider) {
                canvas.drawRect(LocaleController.isRTL ? 0 : dp(64), getHeight() - 1, LocaleController.isRTL ? getWidth() - dp(64) : getWidth(), getHeight(), Theme.dividerPaint);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY)
            );
        }

        public static class Factory extends UItem.UItemFactory<WebsiteView> {
            static { setup(new Factory()); }
            @Override
            public WebsiteView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new WebsiteView(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                final WebsiteView websiteView = (WebsiteView) view;
                websiteView.set(item.textValue, (String) item.text, item.longValue, divider);
            }

            public static UItem as(String domain, String title, long favicon) {
                UItem i = UItem.ofFactory(WebsiteView.Factory.class);
                i.text = domain;
                i.textValue = title;
                i.longValue = favicon;
                return i;
            }
        }
    }

    private static long getDirectorySize(File dir, Boolean cookies) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        long size = 0;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    size += getDirectorySize(file, cookies);
                }
            }
        } else {
            if (cookies != null && (cookies != dir.getName().startsWith("Cookies"))) {
                return size;
            }
            size += dir.length();
        }
        return size;
    }

    private static boolean deleteDirectory(File dir, Boolean cookies) {
        if (dir == null || !dir.exists()) {
            return false;
        }
        if (dir.isDirectory()) {
            boolean allDeleted = true;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (cookies != null && (cookies != file.getName().startsWith("Cookies"))) {
                        continue;
                    }
                    if (!deleteDirectory(file, cookies)) {
                        allDeleted = false;
                    }
                }
            }
            if (allDeleted) {
                dir.delete();
            }
        } else {
            if (cookies != null && (cookies != dir.getName().startsWith("Cookies"))) {
                return false;
            }
            dir.delete();
        }
        return true;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom);
        listView.setPadding(0, listView.getPaddingTop(), 0, bottom);
        listView.setClipToPadding(false);
    }
}
