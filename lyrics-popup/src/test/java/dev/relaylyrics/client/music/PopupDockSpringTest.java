package dev.relaylyrics.client.music;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.offset;
class PopupDockSpringTest {
    @Test void longerReturnsHaveLargerArrivalBounceAndStayInsideTheViewport(){
        double near=arrivalPeak(6),far=arrivalPeak(350);assertThat(far).isGreaterThan(near+10);
    }
    private double arrivalPeak(double y){
        var full=new PopupDockLayout.Rect(375,y,210,130);var motion=new PopupDockMotion();motion.initialize(false,PopupDockLayout.Edge.TOP,.5);motion.minimize(full,960,540);
        double peak=0;
        for(int i=0;i<180;i++){
            var f=motion.step(full,960,540,1,-100,-100,false,false,1./120,true);
            assertThat(f.bounds().x()).isGreaterThanOrEqualTo(0);assertThat(f.bounds().y()).isGreaterThanOrEqualTo(0);
            assertThat(f.bounds().right()).isLessThanOrEqualTo(960);assertThat(f.bounds().bottom()).isLessThanOrEqualTo(540);
            assertThat(f.bounds().height()).isPositive();if(f.folded()>=.999)peak=Math.max(peak,f.bounds().width());
        }return peak;
    }
    @Test void reversingAtArrivalPreservesItsDeformationAndDisabledAnimationsSettleExactly(){
        var full=new PopupDockLayout.Rect(375,350,210,130);var motion=new PopupDockMotion();motion.initialize(false,PopupDockLayout.Edge.TOP,.5);motion.minimize(full,960,540);
        PopupDockMotion.Frame before=null;
        for(int i=0;i<30;i++){before=motion.step(full,960,540,1,-100,-100,false,false,1./120,true);if(before.folded()==1 && before.bounds().width()>224)break;}
        assertThat(before.bounds().width()).isGreaterThan(224);
        motion.restore(full,960,540,1);var reversed=motion.step(full,960,540,1,-100,-100,false,false,0,true);
        assertThat(reversed.bounds()).isEqualTo(before.bounds());
        assertThat(motion.step(full,960,540,1,-100,-100,false,false,0,false).bounds()).isEqualTo(full);assertThat(motion.active()).isFalse();
    }
    @Test void islandOpeningAndClosingBothRetainTheirElasticRebound(){
        var full=new PopupDockLayout.Rect(10,200,210,130);var m=new PopupDockMotion();m.initialize(true,PopupDockLayout.Edge.TOP,.5);
        double widest=0,shortest=999;
        for(int i=0;i<120;i++){var f=m.step(full,960,540,1,480,22,true,false,1./120,true);widest=Math.max(widest,f.bounds().width());}
        for(int i=0;i<120;i++){var f=m.step(full,960,540,1,-100,-100,true,false,1./120,true);shortest=Math.min(shortest,f.bounds().height());}
        assertThat(widest).isGreaterThan(345);assertThat(shortest).isBetween(20.,31.5);
    }
    @Test void retuningPreservesMomentumAndAnalyticSteppingDoesNotDependOnFrameRate(){
        var a=new SpringValue(0,4.5,.84);var b=new SpringValue(0,4.5,.84);a.setGoal(1);b.setGoal(1);a.step(.06);b.step(.06);
        double before=a.position();a.retune(4.5,.56);assertThat(a.step(0)).isEqualTo(before);
        assertThat(a.step(.000001)).isCloseTo(b.step(.000001),offset(.00000001));
        var low=new SpringValue(0,4.5,.56);var high=new SpringValue(0,4.5,.56);low.setGoal(1);high.setGoal(1);
        for(int i=0;i<9;i++)low.step(1./30);for(int i=0;i<72;i++)high.step(1./240);
        assertThat(low.position()).isCloseTo(high.position(),offset(.000000001));
    }
}
