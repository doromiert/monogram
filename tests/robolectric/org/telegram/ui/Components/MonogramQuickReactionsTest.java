package org.telegram.ui.Components;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble.VisibleReaction;
import org.telegram.messenger.xmpp.MonogramFavoriteEmojiTest;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramFavoriteEmojiTest.Display.class,MonogramFavoriteEmojiTest.Settings.class})
public class MonogramQuickReactionsTest {
    @Test public void defaultMessageStripKeepsFavoritePrefixWithoutDuplicateOrLostReactions() {
        TLRPC.TL_messageReactions current=new TLRPC.TL_messageReactions();
        for(String code:Arrays.asList("🔥","🐢","🦊")) {
            TLRPC.TL_reactionCount count=new TLRPC.TL_reactionCount(); TLRPC.TL_reactionEmoji emoji=new TLRPC.TL_reactionEmoji();
            emoji.emoticon=code; count.reaction=emoji; current.results.add(count);
        }
        ReactionsContainerLayout.QuickReactions quick=new ReactionsContainerLayout.QuickReactions(Arrays.asList("🐢","🔥","❤","❤️"),current);
        assertEquals(3,quick.favoriteCount);
        assertEquals(Arrays.asList(VisibleReaction.fromEmojicon("🐢"),VisibleReaction.fromEmojicon("🔥"),VisibleReaction.fromEmojicon("❤️")),quick.reactions.subList(0,3));
        assertEquals(10,quick.reactions.size());
        assertTrue(quick.reactions.contains(VisibleReaction.fromEmojicon("🦊")));
        assertEquals(8,new ReactionsContainerLayout.QuickReactions(Collections.emptyList(),null).reactions.size());
    }
}
