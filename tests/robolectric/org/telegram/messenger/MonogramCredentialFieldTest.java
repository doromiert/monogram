package org.telegram.messenger;

import android.text.InputType;
import android.view.View;
import android.view.autofill.AutofillValue;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.MonogramCredentialField;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
    shadows={MonogramSystemEmojiTest.Device.class,MonogramSystemEmojiTest.Config.class,MonogramSystemEmojiTest.Colors.class})
public class MonogramCredentialFieldTest {
    @Before public void setup(){ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();AndroidUtilities.density=1;}
    @Test public void onlyCredentialEditorsExposeAutofillAndAcceptProviderValues(){
        EditTextBoldCursor chat=new EditTextBoldCursor(RuntimeEnvironment.getApplication());
        assertEquals(View.AUTOFILL_TYPE_NONE,chat.getAutofillType());
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO,chat.getImportantForAutofill());
        MonogramCredentialField address=new MonogramCredentialField(RuntimeEnvironment.getApplication());
        address.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        assertEquals(View.AUTOFILL_TYPE_TEXT,address.getAutofillType());
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_YES,address.getImportantForAutofill());
        address.autofill(AutofillValue.forText("alice@example.invalid"));
        assertEquals("alice@example.invalid",address.getText().toString());
        MonogramCredentialField password=new MonogramCredentialField(RuntimeEnvironment.getApplication());
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        password.autofill(AutofillValue.forText("fictional password"));
        assertEquals("fictional password",password.getText().toString());
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD,password.getInputType()&InputType.TYPE_MASK_VARIATION);
    }
}
