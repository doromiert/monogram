package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramAccountSlotsTest {
    @Test public void allAllocatedSlotsAreFreeAndOccupiedAccountsAreNeverSelected() {
        UserConfig[] original=ReflectionHelpers.getStaticField(UserConfig.class,"Instance");
        UserConfig[] accounts=new UserConfig[UserConfig.MAX_ACCOUNT_COUNT];
        ReflectionHelpers.setStaticField(UserConfig.class,"Instance",accounts);
        try {
            assertEquals(accounts.length,UserConfig.getMaxAccountCount());
            for(int remaining=accounts.length;remaining>0;remaining--) {
                int account=UserConfig.getFreeAccount();
                assertTrue(account>=0 && account<accounts.length);
                assertFalse(accounts[account].isClientActivated());
                TLRPC.User user=new TLRPC.TL_user();user.id=account+1;user.premium=false;
                ReflectionHelpers.setField(accounts[account],"currentUser",user);
                accounts[account].clientUserId=user.id;
            }
            assertEquals(-1,UserConfig.getFreeAccount());
            int released=1;
            ReflectionHelpers.setField(accounts[released],"currentUser",null);
            assertEquals(released,UserConfig.getFreeAccount());
            for(int account=0;account<accounts.length;account++) {
                if(account!=released) assertEquals(account+1,accounts[account].getClientUserId());
            }
        } finally {
            ReflectionHelpers.setStaticField(UserConfig.class,"Instance",original);
        }
    }
}
