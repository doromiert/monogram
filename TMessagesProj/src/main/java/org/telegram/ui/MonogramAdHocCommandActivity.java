// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.*;
import com.negzero.monogram.xmpp.XmppDeveloperTools;
import org.jivesoftware.smackx.commands.AdHocCommand.Action;
import org.jivesoftware.smackx.commands.AdHocCommand.Status;
import org.jivesoftware.smackx.commands.packet.AdHocCommandData;
import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.*;

/** Server-defined XEP-0004 forms and XEP-0050 multi-stage command actions. */
public final class MonogramAdHocCommandActivity extends MonogramSettingsActivity {
    private final String address,node,name;
    private XmppDeveloperTools.Command command;
    private boolean busy,destroyed,started;
    private String error;
    private final ArrayList<FormField> fields=new ArrayList<>();
    private final Map<String,List<String>> answers=new LinkedHashMap<>();
    private List<Action> actions=Collections.emptyList();
    public MonogramAdHocCommandActivity(int account,String address,String node,String name) {
        currentAccount=account;this.address=address;this.node=node;this.name=name==null || name.isEmpty() ? node : name;
    }
    @Override protected CharSequence getTitle() {return name==null ? "Ad-hoc command" : name;}
    private void update() {if(listView!=null) listView.adapter.update(true);}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asShadow(address+"\n"+node));
        if(command==null) {
            items.add(UItem.asButton(1,busy ? "Starting…" : started ? "Start a new command" : "Run command").accent().setEnabled(!busy));
            items.add(UItem.asShadow("Running this command sends an action to this service. Some commands take effect immediately; others ask for details first."));
        } else {
            AdHocCommandData response=command.response();DataForm form=response.getForm();
            items.add(UItem.asHeader(response.getStatus()==Status.completed ? "Completed" : response.getStatus()==Status.canceled ? "Canceled" : "Command in progress"));
            for(org.jivesoftware.smackx.commands.AdHocCommandNote note:response.getNotes()) items.add(UItem.asShadow((note.getType()==org.jivesoftware.smackx.commands.AdHocCommandNote.Type.error ? "Error: " : note.getType()==org.jivesoftware.smackx.commands.AdHocCommandNote.Type.warn ? "Warning: " : "")+note.getValue()));
            fields.clear();
            if(form!=null) {
                if(form.getTitle()!=null) items.add(UItem.asHeader(form.getTitle()));
                for(String instruction:form.getInstructions()) items.add(UItem.asShadow(instruction));
                boolean editable=command.active() && form.getType()==DataForm.Type.form;
                for(FormField field:form.getFields()) {
                    if(field.getType()==FormField.Type.hidden) continue;
                    String label=field.getLabel()==null ? field.getFieldName() : field.getLabel();
                    List<String> value=answers.getOrDefault(field.getFieldName(),field.getValuesAsString());
                    if(field.getType()==FormField.Type.fixed || !editable) {
                        items.add(UItem.asShadow((label==null ? "" : label+"\n")+(field.getType()==FormField.Type.text_private ? "••••••" : String.join("\n",value))));continue;
                    }
                    int id=100+fields.size();fields.add(field);if(field.isRequired()) label+=" *";
                    if(field.getType()==FormField.Type.bool) items.add(UItem.asCheck(id,label).setChecked(!value.isEmpty() && ("1".equals(value.get(0)) || "true".equals(value.get(0)))).setEnabled(!busy));
                    else items.add(UItem.asButton(id,label,field.getType()==FormField.Type.text_private ? value.isEmpty() || value.get(0).isEmpty() ? "Enter" : "••••••" : summary(field,value)).setEnabled(!busy));
                    if(field.getDescription()!=null) items.add(UItem.asShadow(field.getDescription()));
                }
                if(form.getReportedData()!=null) {
                    items.add(UItem.asHeader("Results"));
                    for(DataForm.Item row:form.getItems()) {
                        StringBuilder text=new StringBuilder();
                        for(FormField cell:row.getFields()) {
                            FormField column=form.getReportedData().getField(cell.getFieldName());
                            String label=column==null || column.getLabel()==null ? cell.getFieldName() : column.getLabel();
                            if(text.length()>0) text.append('\n');text.append(label).append(": ").append(String.join(", ",cell.getValuesAsString()));
                        }
                        items.add(UItem.asShadow(text.toString()));
                    }
                }
            }
            actions=command.active() ? XmppDeveloperTools.actions(response) : Collections.emptyList();
            for(int i=0;i<actions.size();i++) {
                Action action=actions.get(i);String label=action==Action.prev ? "Back" : action==Action.next ? "Next" : action==Action.complete ? "Complete" : "Cancel command";
                items.add(UItem.asButton(10000+i,busy ? "Please wait…" : label).setEnabled(!busy));
            }
            if(!command.active() && response.getStatus()==Status.executing) items.add(UItem.asShadow("The response was interrupted. Check the service before starting this command again; the last action may have taken effect."));
            items.add(UItem.asButton(2,R.drawable.msg_copy,"View response XML").setEnabled(!busy));
        }
        if(error!=null) items.add(UItem.asShadow(error));
    }
    private static String summary(FormField field,List<String> values) {
        List<String> labels=new ArrayList<>();
        for(String value:values) {
            String label=value;
            for(FormField.Option option:XmppDeveloperTools.options(field)) if(option.getValueString().equals(value)) { label=option.getLabel()==null ? value : option.getLabel();break; }
            labels.add(label);
        }
        String text=String.join(", ",labels);return text.isEmpty() ? "Enter" : text;
    }
    private void run(Action action) {
        if(busy || destroyed) return;
        busy=true;started=true;error=null;AndroidUtilities.hideKeyboard(fragmentView);update();
        Map<String,List<String>> snapshot=new LinkedHashMap<>();answers.forEach((key,value)->snapshot.put(key,new ArrayList<>(value)));
        MonogramController.get(currentAccount).developerRequest(tools -> command==null ? tools.start(address,node) : tools.advance(command,action,snapshot),(value,failure) -> {
            busy=false;
            if(destroyed) {cancel(value);return;}
            if(value!=null && failure==null) {command=value;answers.clear();DataForm form=value.response().getForm();if(form!=null) for(FormField field:form.getFields()) answers.put(field.getFieldName(),new ArrayList<>(field.getValuesAsString()));}
            error=failure;update();
        });
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(busy) return;
        if(item.id==1) run(Action.execute);
        else if(item.id==2 && command!=null) MonogramDebugActivity.showText(this,"Command response XML",command.response().toXML().toString());
        else if(item.id>=10000 && item.id-10000<actions.size()) run(actions.get(item.id-10000));
        else if(item.id>=100 && item.id-100<fields.size()) edit(fields.get(item.id-100));
    }
    private void edit(FormField field) {
        String key=field.getFieldName(),label=field.getLabel()==null ? key : field.getLabel();
        List<String> current=answers.getOrDefault(key,field.getValuesAsString());
        if(field.getType()==FormField.Type.bool) {
            answers.put(key,Collections.singletonList(!current.isEmpty() && ("1".equals(current.get(0)) || "true".equals(current.get(0))) ? "0" : "1"));update();return;
        }
        List<FormField.Option> options=XmppDeveloperTools.options(field);
        if(field.getType()==FormField.Type.list_single && !options.isEmpty()) {
            int offset=field.isRequired() ? 0 : 1;CharSequence[] labels=new CharSequence[options.size()+offset];if(offset==1) labels[0]="None";
            for(int i=0;i<options.size();i++) labels[i+offset]=options.get(i).getLabel()==null ? options.get(i).getValueString() : options.get(i).getLabel();
            showDialog(new AlertDialog.Builder(getContext()).setTitle(label).setItems(labels,(dialog,which)->{answers.put(key,Collections.singletonList(which<offset ? "" : options.get(which-offset).getValueString()));update();}).setNegativeButton("Cancel",null).create());return;
        }
        if(field.getType()==FormField.Type.list_multi && !options.isEmpty()) {
            Set<String> chosen=new LinkedHashSet<>(current);LinearLayout rows=new LinearLayout(getContext());rows.setOrientation(LinearLayout.VERTICAL);
            for(FormField.Option option:options) {
                TextCheckCell cell=new TextCheckCell(getContext());cell.setTextAndCheck(option.getLabel()==null ? option.getValueString() : option.getLabel(),chosen.contains(option.getValueString()),false);
                cell.setOnClickListener(v->{String value=option.getValueString();if(!chosen.remove(value))chosen.add(value);cell.setChecked(chosen.contains(value));});rows.addView(cell);
            }
            ScrollView scroll=new ScrollView(getContext());scroll.addView(rows);
            showDialog(new AlertDialog.Builder(getContext()).setTitle(label).setView(scroll).setPositiveButton("Save",(dialog,which)->{answers.put(key,new ArrayList<>(chosen));update();}).setNegativeButton("Cancel",null).create());return;
        }
        boolean multi=field.getType()==FormField.Type.text_multi || field.getType()==FormField.Type.jid_multi || field.getType()==FormField.Type.list_multi;
        EditTextCell input=new EditTextCell(getContext(),multi ? "One value per line" : label,false);
        input.editText.setSingleLine(!multi);
        input.editText.setInputType(InputType.TYPE_CLASS_TEXT|(field.getType()==FormField.Type.text_private ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)|(multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        input.editText.setText(String.join("\n",current));input.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(65536)});
        showDialog(new AlertDialog.Builder(getContext()).setTitle(label).setView(input).setPositiveButton("Save",(dialog,which)->{
            String text=input.getText().toString();answers.put(key,multi ? text.isEmpty() ? Collections.emptyList() : new ArrayList<>(Arrays.asList(text.split("\n",-1))) : Collections.singletonList(text));
            AndroidUtilities.hideKeyboard(input.editText);update();
        }).setNegativeButton("Cancel",null).create());
    }
    private void cancel(XmppDeveloperTools.Command value) {
        if(value!=null && value.active()) MonogramController.get(currentAccount).developerRequest(tools->tools.advance(value,Action.cancel,Collections.emptyMap()),(ignored,failure)->{});
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) {return false;}
    @Override public boolean onBackPressed(boolean invoked) {return !busy && super.onBackPressed(invoked);}
    @Override public void onFragmentDestroy() {destroyed=true;if(!busy)cancel(command);answers.clear();super.onFragmentDestroy();}
}
