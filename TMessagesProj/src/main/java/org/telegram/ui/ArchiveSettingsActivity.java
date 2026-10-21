package org.telegram.ui;


import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ListView.AdapterWithDiffUtils;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Objects;

public class ArchiveSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private ListAdapter adapter;

    private boolean changed = false;
    private TLRPC.GlobalPrivacySettings settings;
    
    private String archiveOwner;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ArchiveSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutAnimation(null);
        listView.setAdapter(adapter = new ListAdapter());
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setDurations(350);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setSupportsChangeAnimations(false);
        listView.setItemAnimator(itemAnimator);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            ItemInner item = items.get(position);
            if (item.id == 1) {
                settings.keep_archived_unmuted = !settings.keep_archived_unmuted;
                ((TextCheckCell) view).setChecked(settings.keep_archived_unmuted);
                changed = true;
            } else if (item.id == 4) {
                settings.keep_archived_folders = !settings.keep_archived_folders;
                ((TextCheckCell) view).setChecked(settings.keep_archived_folders);
                changed = true;
            } else if (item.id == 7) {

                settings.archive_and_mute_new_noncontact_peers = !settings.archive_and_mute_new_noncontact_peers;
                ((TextCheckCell) view).setChecked(settings.archive_and_mute_new_noncontact_peers);
                changed = true;
            }
            if (changed && org.telegram.messenger.xmpp.MonogramDialogState.saveArchiveSettings(currentAccount, archiveOwner, settings)) changed = false;
        });

        archiveOwner = org.telegram.messenger.xmpp.MonogramController.get(currentAccount).accountJid();
        settings = org.telegram.messenger.xmpp.MonogramDialogState.archiveSettings(currentAccount, archiveOwner);
        updateItems(false);

        return fragmentView;
    }

    private final ArrayList<ItemInner> oldItems = new ArrayList<>(), items = new ArrayList<>();

    private void updateItems(boolean animated) {
        oldItems.clear();
        oldItems.addAll(items);
        items.clear();

        items.add(new ItemInner(VIEW_TYPE_HEADER, 0, LocaleController.getString("ArchiveSettingUnmutedFolders")));
        items.add(new ItemInner(VIEW_TYPE_CHECK, 1, LocaleController.getString("ArchiveSettingUnmutedFoldersCheck")));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 2, LocaleController.getString("ArchiveSettingUnmutedFoldersInfo")));

        final boolean hasFolders = getMessagesController().getDialogFilters().size() > 1;
        if (hasFolders) {
            items.add(new ItemInner(VIEW_TYPE_HEADER, 3, LocaleController.getString("ArchiveSettingUnmutedChats")));
            items.add(new ItemInner(VIEW_TYPE_CHECK, 4, LocaleController.getString("ArchiveSettingUnmutedChatsCheck")));
            items.add(new ItemInner(VIEW_TYPE_SHADOW, 5, LocaleController.getString("ArchiveSettingUnmutedChatsInfo")));
        }

        items.add(new ItemInner(VIEW_TYPE_HEADER, 6, LocaleController.getString("NewChatsFromNonContacts")));
        items.add(new ItemInner(VIEW_TYPE_CHECK, 7, LocaleController.getString("NewChatsFromNonContactsCheck")));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 8, LocaleController.getString(R.string.MonogramArchiveAndMuteInfo)));

        if (adapter == null) {
            return;
        }

        if (animated) {
            adapter.setItems(oldItems, items);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private final static int VIEW_TYPE_HEADER = 0;
    private final static int VIEW_TYPE_CHECK = 1;
    private final static int VIEW_TYPE_SHADOW = 2;

    private static class ItemInner extends AdapterWithDiffUtils.Item {
        public CharSequence text;
        public int id;
        public ItemInner(int viewType, int id, CharSequence text) {
            super(viewType, false);
            this.id = id;
            this.text = text;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemInner item = (ItemInner) o;
            return id == item.id && Objects.equals(text, item.text);
        }
    }

    private class ListAdapter extends AdapterWithDiffUtils {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_HEADER) {
                view = new HeaderCell(getContext());
            } else if (viewType == VIEW_TYPE_CHECK) {
                view = new TextCheckCell(getContext());
            } else {
                view = new TextInfoPrivacyCell(getContext());
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            ItemInner item = items.get(position);
            final boolean divider = position + 1 < items.size() && items.get(position + 1).viewType == item.viewType;
            if (holder.getItemViewType() == VIEW_TYPE_HEADER) {
                ((HeaderCell) holder.itemView).setText(item.text);
            } else if (holder.getItemViewType() == VIEW_TYPE_SHADOW) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (TextUtils.isEmpty(item.text)) {
                    cell.setFixedSize(12);
                    cell.setText(null);
                } else {
                    cell.setFixedSize(0);
                    cell.setText(item.text);
                }
            } else if (holder.getItemViewType() == VIEW_TYPE_CHECK) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                boolean checked;
                if (item.id == 1) {
                    checked = settings.keep_archived_unmuted;
                    cell.setCheckBoxIcon(0);
                } else if (item.id == 4) {
                    checked = settings.keep_archived_folders;
                    cell.setCheckBoxIcon(0);
                } else if (item.id == 7) {
                    checked = settings.archive_and_mute_new_noncontact_peers;
                    cell.setCheckBoxIcon(0);
                } else {
                    return;
                }
                cell.setTextAndCheck(item.text, checked, divider);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != VIEW_TYPE_SHADOW && holder.getItemViewType() != VIEW_TYPE_HEADER;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= items.size()) {
                return 0;
            }
            return items.get(position).viewType;
        }
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.dialogFiltersUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogFiltersUpdated);
        super.onFragmentDestroy();

        if (changed) org.telegram.messenger.xmpp.MonogramDialogState.saveArchiveSettings(currentAccount, archiveOwner, settings);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogFiltersUpdated && listView != null) updateItems(true);
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        listView.setPadding(0, 0, 0, bottom);
        listView.setClipToPadding(false);
    }
}
