package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smackx.commands.AdHocCommand.Action;
import org.jivesoftware.smackx.commands.AdHocCommand.Status;
import org.jivesoftware.smackx.commands.packet.AdHocCommandData;
import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppDeveloperToolsTest {
    @Test void submissionsPreserveHiddenFieldsAndTypedAnswersWithoutFixedInstructions() {
        DataForm form=DataForm.builder(DataForm.Type.form).setFormType("urn:test:command")
            .addField(FormField.hiddenBuilder("token").setValue("opaque<&>").build())
            .addField(FormField.fixedBuilder().setValue("Instructions").build())
            .addField(FormField.textSingleBuilder("name").setRequired().build())
            .addField(FormField.booleanBuilder("enabled").setValue(false).build())
            .addField(FormField.listSingleBuilder("choice").addOption(new FormField.Option("Human label","machine-value")).build())
            .addField(FormField.textMultiBuilder("lines").build())
            .addField(FormField.jidMultiBuilder("users").build()).build();
        Map<String,List<String>> answers=new LinkedHashMap<>();
        answers.put("token",Collections.singletonList("tamper"));answers.put("name",Collections.singletonList("Alice <&>"));
        answers.put("enabled",Collections.singletonList("1"));answers.put("choice",Collections.singletonList("machine-value"));
        answers.put("lines",Arrays.asList("one","two"));answers.put("users",Arrays.asList("alice@example.org","bob@example.org/device"));
        DataForm sent=XmppDeveloperTools.submit(form,answers);
        assertEquals(DataForm.Type.submit,sent.getType());assertEquals("urn:test:command",sent.getFormType());
        assertEquals("opaque<&>",sent.getField("token").getFirstValue());assertEquals("Alice <&>",sent.getField("name").getFirstValue());
        assertTrue(Arrays.asList("1","true").contains(sent.getField("enabled").getFirstValue()));assertEquals(2,sent.getField("lines").getValues().size());
        assertEquals(2,sent.getField("users").getValues().size());assertEquals(7,sent.getFields().size());
    }
    @Test void requiredValuesOptionsAndJidsAreValidatedBeforeSending() {
        DataForm form=DataForm.builder(DataForm.Type.form).addField(FormField.textSingleBuilder("required").setRequired().build()).build();
        assertThrows(IllegalArgumentException.class,()->XmppDeveloperTools.submit(form,Collections.emptyMap()));
        DataForm select=DataForm.builder(DataForm.Type.form).addField(FormField.listSingleBuilder("choice").addOption(new FormField.Option("yes")).build()).build();
        assertThrows(IllegalArgumentException.class,()->XmppDeveloperTools.submit(select,Collections.singletonMap("choice",Collections.singletonList("forged"))));
        DataForm jid=DataForm.builder(DataForm.Type.form).addField(FormField.jidSingleBuilder("jid").build()).build();
        assertThrows(IllegalArgumentException.class,()->XmppDeveloperTools.submit(jid,Collections.singletonMap("jid",Collections.singletonList("a@b@c"))));
    }
    @Test void emptyOptionalFieldsClearDefaultsIncludingListsAndJids() {
        DataForm form=DataForm.builder(DataForm.Type.form)
            .addField(FormField.listMultiBuilder("many").addOption(new FormField.Option("a")).addValue("a").build())
            .addField(FormField.jidSingleBuilder("jid").build()).addField(FormField.booleanBuilder("off").build()).addField(FormField.textMultiBuilder("lines").addValue("old").build()).build();
        Map<String,List<String>> answers=new HashMap<>();answers.put("many",Collections.emptyList());answers.put("lines",Collections.emptyList());
        DataForm sent=XmppDeveloperTools.submit(form,answers);
        assertTrue(sent.hasField("many"));assertTrue(sent.getField("many").getValues().isEmpty());
        assertTrue(sent.hasField("jid"));assertTrue(sent.getField("jid").getValues().isEmpty());
        assertTrue(Arrays.asList("0","false").contains(sent.getField("off").getFirstValue()));
        assertTrue(sent.hasField("lines"));assertTrue(sent.getField("lines").getValues().isEmpty());
    }
    @Test void actionsFollowTheServiceAndCompletedSessionsOfferNothing() {
        AdHocCommandData data=new AdHocCommandData();data.setStatus(Status.executing);data.addAction(Action.prev);data.addAction(Action.next);
        assertEquals(Arrays.asList(Action.prev,Action.next,Action.cancel),XmppDeveloperTools.actions(data));
        assertFalse(XmppDeveloperTools.actions(data).contains(Action.complete));
        data.setStatus(Status.completed);assertTrue(XmppDeveloperTools.actions(data).isEmpty());
        AdHocCommandData simple=new AdHocCommandData();simple.setStatus(Status.executing);
        assertEquals(Arrays.asList(Action.complete,Action.cancel),XmppDeveloperTools.actions(simple));
    }
    @Test void resultPayloadsAreNotAccidentallyResubmitted() {
        assertNull(XmppDeveloperTools.submit(DataForm.builder(DataForm.Type.result).addField(FormField.textSingleBuilder("value").setValue("result").build()).build(),Collections.emptyMap()));
        assertNull(XmppDeveloperTools.submit(null,Collections.emptyMap()));
    }
}
