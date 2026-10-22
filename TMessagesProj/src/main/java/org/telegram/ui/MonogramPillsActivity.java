// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import java.util.*;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.*;
public final class MonogramPillsActivity extends MonogramSettingsActivity {
    private int orderSection;
    public MonogramPillsActivity(int account){currentAccount=account;}
    @Override protected CharSequence getTitle(){return "Pills";}
    @Override public View createView(Context context){View view=super.createView(context);listView.allowReorder(true);listView.listenReorder((s,rows)->{if(s!=orderSection)return;ArrayList<String> order=new ArrayList<>();for(UItem row:rows)order.add(MonogramPillView.KEYS[row.id-100]);MonogramPillView.save(order);});return view;}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        items.add(UItem.asCheck(1,"Infinite scrolling").setChecked(MonogramPillView.preferences().getBoolean("loop",true)));
        items.add(UItem.asShadow("Swipe a pill beside the home search field to switch between enabled widgets. Hold it to open these settings."));
        items.add(UItem.asHeader("Active pills"));orderSection=adapter.reorderSectionStart();List<String> active=MonogramPillView.active();
        for(String key:active)items.add(UItem.asCheck(100+Arrays.asList(MonogramPillView.KEYS).indexOf(key),MonogramPillView.label(key)).setChecked(true).setReordering(true));adapter.reorderSectionEnd();
        if(active.isEmpty())items.add(UItem.asShadow("No pills enabled"));
        items.add(UItem.asHeader("Available pills"));for(int i=0;i<MonogramPillView.KEYS.length;i++)if(!active.contains(MonogramPillView.KEYS[i]))items.add(UItem.asCheck(100+i,MonogramPillView.label(MonogramPillView.KEYS[i])).setChecked(false));
        items.add(UItem.asShadow(null));items.add(UItem.asButton(2,"Weather location",MonogramPillView.preferences().getString("city","Choose city")));
        items.add(UItem.asShadow("Weather: Open-Meteo. City search: GeoNames through Open-Meteo. Only your chosen city is sent to these services; no device location permission is needed."));
        items.add(UItem.asButton(4,"USD quote currency",MonogramPillView.preferences().getString("currency","EUR")));
        items.add(UItem.asShadow("Exchange reference rates: European Central Bank. Refreshed daily."));
        items.add(UItem.asButton(3,"Reset pills"));
    }
    @Override protected void onClick(UItem item,View v,int p,float x,float y){
        if(item.id>=100&&item.id-100<MonogramPillView.KEYS.length){List<String> active=MonogramPillView.active();String key=MonogramPillView.KEYS[item.id-100];if(!active.remove(key))active.add(key);MonogramPillView.save(active);refresh();}
        else if(item.id==1){MonogramPillView.preferences().edit().putBoolean("loop",!MonogramPillView.preferences().getBoolean("loop",true)).apply();refresh();}
        else if(item.id==2){EditText input=new EditText(getContext());input.setSingleLine();input.setText(MonogramPillView.preferences().getString("city",""));input.setPadding(AndroidUtilities.dp(24),AndroidUtilities.dp(12),AndroidUtilities.dp(24),AndroidUtilities.dp(12));input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));showDialog(new AlertDialog.Builder(getContext()).setTitle("Weather location").setView(input).setPositiveButton("Search",(d,w)->MonogramPillView.findCity(input.getText().toString(),cities->{
            if(getParentActivity()==null)return;
            if(cities.isEmpty()){showDialog(new AlertDialog.Builder(getContext()).setTitle("Weather").setMessage("No locations found. Check the city name and connection.").setPositiveButton("OK",null).create());return;}
            CharSequence[] labels=new CharSequence[cities.size()];for(int i=0;i<labels.length;i++)labels[i]=cities.get(i).optString("name")+", "+cities.get(i).optString("country");
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Choose location").setItems(labels,(dialog,i)->{org.json.JSONObject city=cities.get(i);MonogramPillView.preferences().edit().putString("city",labels[i].toString()).putString("lat",city.optString("latitude")).putString("lon",city.optString("longitude")).remove("weather-time").apply();refresh();}).setNegativeButton("Cancel",null).create());
        })).setNegativeButton("Cancel",null).create());}
        else if(item.id==4){showDialog(new AlertDialog.Builder(getContext()).setTitle("USD quote currency").setItems(MonogramPillView.CURRENCIES,(d,i)->{MonogramPillView.preferences().edit().putString("currency",MonogramPillView.CURRENCIES[i]).remove("usd-time").apply();refresh();}).setNegativeButton("Cancel",null).create());}
        else if(item.id==3){MonogramPillView.save(Collections.emptyList());refresh();}
    }
    private void refresh(){listView.adapter.update(true);if(parentLayout!=null)parentLayout.rebuildFragments(0);}
    @Override protected boolean onLongClick(UItem item,View v,int p,float x,float y){return false;}
}
