package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.lyrics.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class IslandCreditsTest {
    private static final LyricsMetadata COMMUNITY=new LyricsMetadata(LyricsSource.SPICY_TTML,LyricsSyncMode.LINE,
            "track","Song","Artist","Album","Writer","Spicy Lyrics",true,"Alice","","Bob","");
    private static LyricLine line(long start,long end,boolean backing) {
        return new LyricLine(start,end,"A phrase",List.of(),"",backing,null,null);
    }
    private static LyricsDocument doc(LyricsMetadata metadata,LyricLine... lines) {
        return new LyricsDocument(List.of(lines),null,metadata);
    }
    @Test void creditsFollowTheLastLyricAndTheWholeWindowEndsAtExactlyFivePlaybackSeconds() {
        var doc=doc(COMMUNITY,line(1000,9000,false));
        assertThat(IslandLyrics.sample(doc,8999,false).kind()).isEqualTo(IslandLyrics.Kind.LYRICS);
        assertThat(IslandLyrics.sample(doc,9000,false).kind()).isEqualTo(IslandLyrics.Kind.CREDITS);
        assertThat(IslandLyrics.sample(doc,13999,false).kind()).isEqualTo(IslandLyrics.Kind.CREDITS);
        assertThat(IslandLyrics.sample(doc,14000,false)).isSameAs(IslandLyrics.METADATA);
        assertThat(IslandLyrics.sample(doc,300000,false)).isSameAs(IslandLyrics.METADATA);
        // Pausing retains the exact card; seeking restores lyrics or the corresponding credit.
        assertThat(IslandLyrics.sample(doc,10000,false)).isEqualTo(IslandLyrics.sample(doc,10000,false));
        assertThat(IslandLyrics.sample(doc,5000,false).kind()).isEqualTo(IslandLyrics.Kind.LYRICS);
        assertThat(IslandLyrics.sample(doc,10000,false).kind()).isEqualTo(IslandLyrics.Kind.CREDITS);
    }
    @Test void anInstrumentalBetweenVersesIsStillWaitingAndBackingVocalsFinishBeforeCredits() {
        var doc=doc(COMMUNITY,line(1000,5000,false),line(12000,15000,false),line(14000,18000,true));
        assertThat(IslandLyrics.sample(doc,9000,false).kind()).isEqualTo(IslandLyrics.Kind.WAITING);
        assertThat(IslandLyrics.sample(doc,16000,false).kind()).isEqualTo(IslandLyrics.Kind.LYRICS);
        assertThat(IslandLyrics.sample(doc,17999,false).kind()).isEqualTo(IslandLyrics.Kind.LYRICS);
        assertThat(IslandLyrics.sample(doc,18000,false).kind()).isEqualTo(IslandLyrics.Kind.CREDITS);
        assertThat(IslandLyrics.sample(doc,22999,false).kind()).isEqualTo(IslandLyrics.Kind.CREDITS);
        assertThat(IslandLyrics.sample(doc,23000,false)).isSameAs(IslandLyrics.METADATA);
    }
    @Test void onlyRealAttributionIsShownAndEmptyLoadingAndStaticDocumentsNeverStartCredits() {
        var noCredits=doc(LyricsMetadata.unknown(),line(0,1000,false));
        assertThat(IslandLyrics.sample(noCredits,1000,false)).isSameAs(IslandLyrics.METADATA);
        assertThat(IslandLyrics.credits(noCredits.metadata())).isEmpty();
        assertThat(IslandLyrics.sample(doc(COMMUNITY),1000,false)).isSameAs(IslandLyrics.METADATA);
        assertThat(IslandLyrics.sample(doc(COMMUNITY,line(0,1000,false)),1500,true).kind()).isEqualTo(IslandLyrics.Kind.WAITING);
        var staticMetadata=new LyricsMetadata(LyricsSource.SPICY_TTML,LyricsSyncMode.STATIC,"","","","");
        assertThat(IslandLyrics.sample(doc(staticMetadata,line(0,1000,false)),1500,false)).isSameAs(IslandLyrics.METADATA);
        var provider=new LyricsMetadata(LyricsSource.SPICY_TTML,LyricsSyncMode.LINE,"","","","");
        assertThat(IslandLyrics.credits(provider)).containsExactly(new IslandLyrics.Credit(IslandLyrics.CreditRole.PROVIDER,"Spicy Lyrics"));
    }
    @Test void makerUploaderWritersAndProviderShareFiveSecondsInsteadOfFiveSecondsEach() {
        var credits=IslandLyrics.credits(COMMUNITY);
        assertThat(credits).extracting(IslandLyrics.Credit::role).containsExactly(IslandLyrics.CreditRole.MAKER,
                IslandLyrics.CreditRole.UPLOADER,IslandLyrics.CreditRole.WRITERS,IslandLyrics.CreditRole.PROVIDER);
        assertThat(credits).extracting(IslandLyrics.Credit::name).containsExactly("Alice","Bob","Writer","Spicy Lyrics");
        var frame=IslandLyrics.sample(doc(COMMUNITY,line(0,1000,false)),1000,false);
        for(int i=0;i<credits.size();i++) {
            assertThat(IslandLyrics.creditPage(credits,frame,1000+i*1250).credit()).isEqualTo(credits.get(i));
            assertThat(IslandLyrics.creditPage(credits,frame,1000+i*1250+1249).credit()).isEqualTo(credits.get(i));
        }
        assertThat(IslandLyrics.creditPage(credits,IslandLyrics.METADATA,6000)).isNull();
        assertThat(IslandLyrics.creditPage(List.of(),frame,1000)).isNull();
    }
    @Test void anUnnamedCommunityContributionHasACreditWithoutInventingUserNames() {
        var metadata=new LyricsMetadata(LyricsSource.UNKNOWN,LyricsSyncMode.LINE,
                "track","Song","Artist","Album","","",true,"  ","","","");
        assertThat(IslandLyrics.credits(metadata)).containsExactly(new IslandLyrics.Credit(IslandLyrics.CreditRole.COMMUNITY,""));
        assertThat(IslandLyrics.sample(doc(metadata,line(0,1000,false)),1000,false).kind()).isEqualTo(IslandLyrics.Kind.CREDITS);
    }
}
