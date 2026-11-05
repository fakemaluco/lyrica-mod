package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.lyrics.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class IslandLyricsTest {
    private static LyricLine line(long start,long end,String text,boolean backing) {
        return new LyricLine(start,end,text,List.of(),"",backing,null,null);
    }
    @Test void oneLeadRowHoldsAcrossBreathsButInstrumentalGapsShowMetadataAndWaitingDots() {
        var first=line(4000,6000,"First",false);
        var second=line(6500,8000,"Second",false);
        var third=line(14000,18000,"Third",false);
        var doc=new LyricsDocument(List.of(first,line(4500,12000,"Backing",true),second,third));
        var intro=IslandLyrics.sample(doc,2000,false);
        assertThat(intro.kind()).isEqualTo(IslandLyrics.Kind.WAITING);
        assertThat(intro.waitStart()).isZero(); assertThat(intro.waitEnd()).isEqualTo(4000);
        assertThat(IslandLyrics.sample(doc,4000,false).line()).isSameAs(first);
        assertThat(IslandLyrics.sample(doc,6200,false).line()).isSameAs(first);
        assertThat(IslandLyrics.sample(doc,6500,false).line()).isSameAs(second);
        var gap=IslandLyrics.sample(doc,11000,false);
        assertThat(gap.kind()).isEqualTo(IslandLyrics.Kind.WAITING);
        assertThat(gap.waitStart()).isEqualTo(8000); assertThat(gap.waitEnd()).isEqualTo(14000);
        assertThat(gap.waitProgress(11000)).isBetween(0.,1.);
        assertThat(IslandLyrics.sample(doc,18000,false)).isSameAs(IslandLyrics.METADATA);
        // A seek to an earlier line immediately restores that line, without retained page state.
        assertThat(IslandLyrics.sample(doc,5000,false).line()).isSameAs(first);
    }
    @Test void overlappingLeadVoicesUseTheLatestActiveLineAndCanReturnToASustainedVoice() {
        var held=line(0,10000,"Held",false); var shortLine=line(2000,3000,"Short",false);
        var doc=new LyricsDocument(List.of(held,shortLine,line(15000,18000,"Next",false)));
        assertThat(IslandLyrics.sample(doc,2500,false).line()).isSameAs(shortLine);
        assertThat(IslandLyrics.sample(doc,4000,false).line()).isSameAs(held);
        assertThat(IslandLyrics.sample(doc,11000,false).waitStart()).isEqualTo(10000);
    }
    @Test void noLyricsAndStaticLyricsKeepNormalMetadataAndLoadingNeverLeaksTheOldPhrase() {
        assertThat(IslandLyrics.sample(new LyricsDocument(List.of()),2000,false)).isSameAs(IslandLyrics.METADATA);
        var doc=new LyricsDocument(List.of(line(0,5000,"Old",false)));
        assertThat(IslandLyrics.sample(doc,2000,true).kind()).isEqualTo(IslandLyrics.Kind.WAITING);
        var staticDoc=doc.withMetadata(new LyricsMetadata(LyricsSource.UNKNOWN,LyricsSyncMode.STATIC,"","","",""));
        assertThat(IslandLyrics.sample(staticDoc,2000,false)).isSameAs(IslandLyrics.METADATA);
    }
    private static final List<IslandLyrics.Cell> CELLS=List.of(
            new IslandLyrics.Cell(1000,2000,0,40,false),new IslandLyrics.Cell(2000,2500,45,40,false),
            new IslandLyrics.Cell(3000,4000,90,40,false),new IslandLyrics.Cell(4000,5000,135,40,false));
    private static IslandLyrics.Viewport view(long now,boolean words) {
        return IslandLyrics.viewport(CELLS,90,now,1000,5000,words);
    }
    @Test void lineScrollUsesTheCompleteExactDurationAndSeeksAndPausesAreDeterministic() {
        assertThat(view(0,false).offset()).isZero(); assertThat(view(1000,false).offset()).isZero();
        assertThat(view(3000,false).offset()).isEqualTo(42.5);
        assertThat(view(5000,false).offset()).isEqualTo(85);
        assertThat(view(6000,false).offset()).isEqualTo(85);
        assertThat(view(3000,false)).isEqualTo(view(3000,false));
        assertThat(view(2000,false).offset()).isEqualTo(21.25);
        assertThat(view(3000,false).end()).isEqualTo(4);
    }
    @Test void wordPagesChangeExactlyAtTheNextSungWordAndKeepAShortBreathOnTheOldPage() {
        var first=view(1000,true);
        assertThat(first.first()).isZero();assertThat(first.end()).isEqualTo(2);assertThat(first.width()).isEqualTo(85);
        assertThat(view(2999,true)).isEqualTo(first);
        var second=view(3000,true);
        assertThat(second.first()).isEqualTo(2);assertThat(second.end()).isEqualTo(4);assertThat(second.offset()).isEqualTo(90);
        assertThat(view(4500,true)).isEqualTo(second);
        assertThat(view(1500,true)).isEqualTo(first);
        var fits=IslandLyrics.viewport(CELLS,200,4500,1000,5000,true);
        assertThat(fits.first()).isZero();assertThat(fits.end()).isEqualTo(4);assertThat(fits.offset()).isZero();
    }
    @Test void anOversizedWordScrollsWithinItsOwnTimeIncludingRightToLeftInsteadOfDroppingItsEnding() {
        for(boolean rtl:new boolean[]{false,true}) {
            var cells=List.of(new IslandLyrics.Cell(1000,5000,0,200,rtl),new IslandLyrics.Cell(6000,7000,205,20,false));
            var start=IslandLyrics.viewport(cells,80,1000,0,7000,true);
            var middle=IslandLyrics.viewport(cells,80,3000,0,7000,true);
            var end=IslandLyrics.viewport(cells,80,5000,0,7000,true);
            assertThat(start.end()).isEqualTo(1); assertThat(middle.offset()).isEqualTo(60);
            assertThat(start.offset()).isEqualTo(rtl?120.:0.); assertThat(end.offset()).isEqualTo(rtl?0.:120.);
            assertThat(IslandLyrics.viewport(cells,80,6000,0,7000,true).first()).isEqualTo(1);
        }
        assertThat(IslandLyrics.viewport(List.of(),80,0,0,0,false).end()).isZero();
        assertThat(IslandLyrics.viewport(CELLS,0,0,0,0,true).end()).isZero();
    }
}
