/*
 * This is the source code of Telegram for Android v. 5.x.x
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Adapters;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.LocationCell;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.RecyclerView;

public class LocationActivitySearchAdapter extends BaseLocationAdapter {

    private static final int VIEW_TYPE_LOCATION = 0;
    private static final int VIEW_TYPE_SECTION = 1;
    private static final int VIEW_TYPE_PLACE_STATUS=2,VIEW_TYPE_ATTRIBUTION=3;

    private Context mContext;
    private Theme.ResourcesProvider resourcesProvider;

    private boolean myLocationDenied = false;
    public void setMyLocationDenied(boolean myLocationDenied) {
        if (this.myLocationDenied == myLocationDenied)
            return;
        this.myLocationDenied = myLocationDenied;
    }

    private FlickerLoadingView globalGradientView;
    public LocationActivitySearchAdapter(Context context, Theme.ResourcesProvider resourcesProvider, boolean stories, boolean biz) {
        super(stories, biz);

        mContext = context;
        this.resourcesProvider = resourcesProvider;

        globalGradientView = new FlickerLoadingView(context);
        globalGradientView.setIsSingleCell(true);
    }

    @Override
    public int getItemCount() {
        if(isXmppSearch()) return (isSearching()?3:Math.max(1,places.size()))+1;
        int count = 0;
        if (!locations.isEmpty()) {
            count += 1 + locations.size();
        }
        if (!myLocationDenied) {
            if (isSearching()) {
                count += 3;
            } else {
                if (!locations.isEmpty() && !places.isEmpty()) {
                    count++;
                }
                count += places.size();
            }
        }
        return count;
    }

    public boolean isEmpty() { return places.size() == 0 && locations.size() == 0; }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view;
        if(viewType==VIEW_TYPE_PLACE_STATUS || viewType==VIEW_TYPE_ATTRIBUTION) {
            android.widget.TextView label=new android.widget.TextView(mContext);label.setTextColor(Theme.getColor(Theme.key_dialogTextGray3,resourcesProvider));label.setTextSize(14);label.setPadding(org.telegram.messenger.AndroidUtilities.dp(20),org.telegram.messenger.AndroidUtilities.dp(16),org.telegram.messenger.AndroidUtilities.dp(20),org.telegram.messenger.AndroidUtilities.dp(16));
            label.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));view=label;
        } else if (viewType == VIEW_TYPE_LOCATION) {
            view = new LocationCell(mContext, false, resourcesProvider);
        } else {
            view = new GraySectionCell(mContext, resourcesProvider);
        }
        return new RecyclerListView.Holder(view);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        if(holder.getItemViewType()==VIEW_TYPE_PLACE_STATUS || holder.getItemViewType()==VIEW_TYPE_ATTRIBUTION) {
            android.widget.TextView label=(android.widget.TextView)holder.itemView;boolean attribution=holder.getItemViewType()==VIEW_TYPE_ATTRIBUTION;
            label.setText(attribution?LocaleController.getString(R.string.MonogramPlaceAttribution):placeSearchStatus());
            label.setOnClickListener(attribution?view -> { try { mContext.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://www.openstreetmap.org/copyright")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); }catch(android.content.ActivityNotFoundException unavailable) {} }:null);return;
        }
        if (holder.getItemViewType() == VIEW_TYPE_LOCATION) {
            TLRPC.TL_messageMediaVenue place = null;
            String iconUrl = null;
            int oposition = position;
            int p = position;
            if (!locations.isEmpty()) {
                position--;
            }
            if (position >= 0 && position < locations.size()) {
                place = locations.get(position);
                iconUrl = "pin";
                p = 2;
            } else if (!isSearching()) {
                position -= locations.size();
                if (!searchingLocations && !locations.isEmpty()) {
                    position -= 1;
                }
                if (position >= 0 && position < places.size()) {
                    place = places.get(position);
                    p = position;
                }
            }
            LocationCell locationCell = (LocationCell) holder.itemView;
            locationCell.setLocation(place, p, oposition != getItemCount() - 1 && (searchingLocations || locations.isEmpty() || oposition != (locations.size())));
        } else if (holder.getItemViewType() == VIEW_TYPE_SECTION) {
            if (position == 0 && !locations.isEmpty()) {
                ((GraySectionCell) holder.itemView).setText(LocaleController.getString(R.string.LocationOnMap));
            } else {
                ((GraySectionCell) holder.itemView).setText(LocaleController.getString(R.string.NearbyVenue));
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        if(isXmppSearch()) { if(position==getItemCount()-1) return VIEW_TYPE_ATTRIBUTION;return !isSearching() && places.isEmpty()?VIEW_TYPE_PLACE_STATUS:VIEW_TYPE_LOCATION; }
        if ((position == 0 || position == (1 + locations.size())) && !locations.isEmpty()) {
            return VIEW_TYPE_SECTION;
        }
        return VIEW_TYPE_LOCATION;
    }

    public TLRPC.TL_messageMediaVenue getItem(int position) {
        if(isXmppSearch()) return currentPlaceResults() && !isSearching() && position>=0 && position<places.size()?places.get(position):null;
        if (!locations.isEmpty()) {
            position--;
        }
        if (position >= 0 && position < locations.size()) {
            return locations.get(position);
        } else if (!isSearching()) {
            position -= locations.size();
            if (!locations.isEmpty()) {
                position -= 1;
            }
            if (position >= 0 && position < places.size()) {
                return places.get(position);
            }
        }
        return null;
    }

    @Override
    public boolean isEnabled(RecyclerView.ViewHolder holder) {
        if(isXmppSearch()) return currentPlaceResults() && !isSearching() && holder.getItemViewType()==VIEW_TYPE_LOCATION;
        return true;
    }

    @Override
    protected void notifyStartSearch(boolean wasSearching, int oldItemCount, boolean animated) {
        if (wasSearching)
            return;
        notifyDataSetChanged();
    }
}
