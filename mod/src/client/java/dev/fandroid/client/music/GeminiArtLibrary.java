package dev.fandroid.client.music;
import dev.fandroid.client.ClientPreferences;
import java.util.List;
import java.util.Locale;
/** Executable scene-art modules. References requiring geometry/history are not advertised as implementations. */
public final class GeminiArtLibrary {
    public record Style(String id,String technique,String function,String cost){
        public String category(){return switch(id){case "terminal","onebit","blueprint"->"graphic";case "inferno","abyss","dreamscape","aurora","storm","neon"->"atmosphere";default->"illustration";};}
        public String support(){return "World color/depth post-process, before HUD; no geometry/material replacement";}
        public String fallback(){return "Original world remains visible if generation or GPU compilation fails";}
    }
    public record Recipe(String id,String primary,String secondary,int scale,int stroke,int grain,int blend,boolean animated){
        public void apply(ClientPreferences p){p.setGeminiArtStyle(primary);p.setGeminiArtSecondary(secondary);p.setGeminiArtScale(scale);p.setGeminiArtStroke(stroke);p.setGeminiArtGrain(grain);p.setGeminiArtBlend(blend);p.setGeminiArtAnimated(animated);}
        public boolean matches(ClientPreferences p){return primary.equals(p.getGeminiArtStyle()) && secondary.equals(p.getGeminiArtSecondary()) && scale==p.getGeminiArtScale() && stroke==p.getGeminiArtStroke() && grain==p.getGeminiArtGrain() && blend==p.getGeminiArtBlend() && animated==p.isGeminiArtAnimated();}
    }
    public static final List<Recipe> RECIPES=List.of(
        new Recipe("pencil_notebook","pencil","crosshatch",100,90,35,25,false),
        new Recipe("living_ink","ink","none",120,130,20,0,true),
        new Recipe("dreamy_watercolor","watercolor","pencil",150,70,25,15,false),
        new Recipe("vintage_comic","comic","none",130,120,15,0,false),
        new Recipe("anime_opening","cel","none",100,90,0,0,false),
        new Recipe("retro_terminal","terminal","none",100,100,15,0,false),
        new Recipe("handdrawn_motion","handdrawn","pencil",110,110,25,20,true),
        new Recipe("pure_lineart","lineart","none",100,90,0,0,false),
        new Recipe("infernal_rage","inferno","none",120,110,25,0,false),
        new Recipe("deep_abyss","abyss","none",100,100,10,0,false),
        new Recipe("lucid_dream","dreamscape","none",150,80,15,0,false),
        new Recipe("northern_lights","aurora","none",150,100,10,0,false),
        new Recipe("heavy_storm","storm","none",120,110,20,0,false),
        new Recipe("electric_night","neon","none",100,110,10,0,false),
        new Recipe("technical_dream","blueprint","none",130,90,0,0,false),
        new Recipe("graphic_duo","duotone","none",100,100,0,0,false),
        new Recipe("onebit_world","onebit","none",120,100,0,0,false));
    public static int recipeIndex(ClientPreferences p){for(int i=0;i<RECIPES.size();i++)if(RECIPES.get(i).matches(p))return i+1;return 0;}
    public static final List<Style> STYLES=List.of(
        new Style("handdrawn","Organic ink edges, warm paper, gently irregular thin strokes, stable paper grain","lyricaHanddrawn","medium: 4 scene samples"),
        new Style("pencil","Graphite on ivory paper, fine contours and shadow-dependent graphite texture","lyricaPencil","medium: 4 scene samples"),
        new Style("crosshatch","Diagonal strokes; progressively layer crossing directions in shadows","lyricaCrosshatch","medium: 4 scene samples"),
        new Style("ink","High contrast black ink, thick contours, controlled solid shadow masses","lyricaInk","medium: 4 scene samples"),
        new Style("watercolor","Approximate pigment wash with soft local color patches, paper grain, dark pigment edges; not fluid simulation","lyricaWatercolor","medium: 6 scene samples"),
        new Style("cel","Quantized light bands and thin silhouette edges; post-process approximation, not relighting materials","lyricaCel","medium: 4 scene samples"),
        new Style("comic","Cel bands, dark ink contours and luminance-dependent halftone dots","lyricaComic","medium: 4 scene samples"),
        new Style("terminal","Green phosphor, scan lines, stepped luminance; no temporal persistence buffer","lyricaTerminal","low"),
        new Style("lineart","Pure white space and crisp BLACK outlines, flat white surfaces, zero beige paper/noise/hatching; structural color/depth edges, no grayscale fill. Reference: sparse psychological pencil line art","lyricaLineart","medium: 8 scene/depth samples"),
        new Style("inferno","Charred terrain with preserved relief, molten edge accents, layered fiery sky, sparse rising embers. Anger, hostile industrial or metal atmosphere; not a flat red tint","lyricaInferno","medium: 4 scene samples + procedural noise"),
        new Style("abyss","Deep blue-black landscape, silhouettes, cool depth haze and a dark oceanic sky; spacious, lonely or ominous","lyricaAbyss","low"),
        new Style("dreamscape","Pastel terrain, softly luminous highlights and layered lavender/peach cloud atmosphere; dreamy, tender or surreal","lyricaDreamscape","low: procedural sky"),
        new Style("aurora","Readable dark landscape under sweeping green/cyan aurora ribbons and sparse stars; wonder, space or ambient","lyricaAurora","low: procedural sky"),
        new Style("storm","Desaturated storm landscape, turbulent charcoal sky and restrained slanted rain streaks; tension and aggression without flashing","lyricaStorm","low: procedural sky/rain"),
        new Style("neon","Dark readable terrain, emissive cyan/magenta edges driven by actual scene edges, sparse city-like sky accents; synth and electronic","lyricaNeon","medium: 4 scene samples"),
        new Style("blueprint","Deep blueprint blue, clean white scene contours with sparse technical grid; mechanical or analytical","lyricaBlueprint","medium: 8 scene/depth samples"),
        new Style("duotone","Bold navy shadows versus warm coral lights, discrete graphic tone regions and thin contours; expressive poster-print base with recolorable output","lyricaDuotone","medium: 4 scene samples"),
        new Style("onebit","Strict black/white threshold with spatially ordered dithering, readable scene silhouettes, no sepia","lyricaOnebit","low"));
    public static List<String> ids(boolean secondary){var ids=new java.util.ArrayList<String>();ids.add(secondary?"none":"auto");for(var s:STYLES)ids.add(s.id());return List.copyOf(ids);}
    public static String direction(ClientPreferences p){
        var first=STYLES.stream().filter(s->s.id().equals(p.getGeminiArtStyle())).findFirst();
        var second=STYLES.stream().filter(s->s.id().equals(p.getGeminiArtSecondary())).findFirst();
        String args=String.format(Locale.ROOT,", %.3f, %.3f, %.3f, %.1f, %.1f)",p.getGeminiArtScale()/100.,p.getGeminiArtStroke()/100.,p.getGeminiArtGrain()/100.,(double)p.getGeminiArtSeed(),p.isGeminiArtAnimated()?1.:0.);
        String instruction="WORLD ART DIRECTION. Host supplies sampleScene(uv), sampleDepth(uv) and these art functions: "+STYLES.stream().map(s->s.function()+": "+s.technique()+" ("+s.cost()+")").toList()+". Each art function signature is vec3 function(vec3 color, vec2 uv, float depth, float scale, float stroke, float grain, float seed, float animated). Use them when useful, or write original bounded GLSL helpers; DO NOT redeclare host functions. They are ingredients, not mandatory presets in automatic mode. Coordinate patterns are stable screen/paper coordinates, not world-surface UV. Camera movement may move geometry beneath paper. No actual material normals, motion vectors, previous-frame buffers or true volumetrics are supplied. Do not claim unsupported geometry, fluid or temporal effects. Order: original scene -> chosen primary art -> optional secondary art -> sky treatment -> gentle audio accents. Preserve the original scene geometry. ";
        if(first.isPresent())instruction+="Required primary: "+first.get().technique()+". Start with vec3 art="+first.get().function()+"(sceneColor,uv,depth"+args+"; ";
        else instruction+="Choose an appropriate artistic treatment for this song, including a supplied art function when appropriate. Initial trailing arguments after sceneColor,uv,depth: "+args+". ";
        if(second.isPresent())instruction+="Secondary: "+second.get().technique()+". Mix primary with "+second.get().function()+"(sceneColor,uv,depth"+args+" at weight "+p.getGeminiArtBlend()/100.+". Avoid stacking expensive filters. ";
        instruction+=p.isGeminiArtAudio()?"Audio reaction enabled; use real provided energy/bands gently, never invent phonemes. ":"Do not use energy, beat or audio bands. ";
        instruction+=p.isGeminiArtAnimated()?"Optional stroke redraw is quantized at 8 Hz; no high-frequency flicker. ":"Keep grain/strokes static; do not animate paper patterns. ";
        instruction+="Creative freedom="+p.getGeminiCreativeFreedom()+"/100. "+(p.getGeminiCreativeFreedom()>=65?"Design a bespoke world, not a filter demo. In automatic mode you may adapt the starting scale/stroke/grain to the musical concept and derive a song-specific seed, while honoring redraw/audio switches. Host functions are building blocks; you may write your own small helpers, change palettes, combine spatial masks, invent sky treatments and use a partial base-function mix. In AUTOMATIC mode do not treat this list as a closed menu. ":"Favor a coherent restrained treatment with fewer layers and clearer original scene detail. ");
        instruction+="Song interpretation="+p.isGeminiSongContext()+". At any freedom level obey any explicit chosen primary/secondary style and extra user reference. For lineart use pure white and black, not a gray or sepia sketch. Never mix a colorful secondary into a requested monochrome look. ";
        return instruction+"Extra user direction: "+p.getGeminiShaderDirection();
    }
    /** Per-request novelty without changing the stable per-track cache identity. No song lookup table. */
    public static String generationDirection(String direction,long interpretation,List<String> recent){
        return direction+"\nINTERPRETATION "+interpretation+". This is a new visual commission, not a reroll of grain. "+
                "When regenerating, reinterpret composition, silhouette language, negative space, palette and atmosphere while keeping the song's identity. "+
                "Recent looks in this listening session (avoid repeating their signature unless the musical context calls for it): "+recent+". "+
                "Do not use the same beige paper, magenta sky, generic outline or full-screen noise for every song. "+
                "A monochrome drawing can be confident and almost empty; an aggressive piece can have spatial fire, ash, hostile skies and charred terrain. "+
                "These are general visual possibilities, not rules or a table mapping songs to effects. Choose your own fitting concept.";
    }
    public static String functions(){return """
        vec3 sampleScene(vec2 uv){return texture(SceneSampler,clamp(uv,vec2(0.),vec2(1.))).rgb;}
        float sampleDepth(vec2 uv){float d=texture(DepthSampler,clamp(uv,vec2(0.),vec2(1.))).r;return mix(d,1.-d,MusicAccent.w);}
        float lyricaNoise(vec2 p,float seed);
        float lyricaHash(vec2 p,float seed){return fract(sin(dot(p,vec2(127.1,311.7))+seed*17.13)*43758.5453);}
        float lyricaLuma(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
        float lyricaEdge(vec2 uv,float stroke){vec2 d=max(stroke,.25)/uResolution;vec3 x=sampleScene(uv+vec2(d.x,0))-sampleScene(uv-vec2(d.x,0));vec3 y=sampleScene(uv+vec2(0,d.y))-sampleScene(uv-vec2(0,d.y));return clamp(length(x)+length(y),0.,1.);}
        vec2 lyricaPaper(vec2 uv,float scale,float seed,float animated){float redraw=floor(uTime*8.)*animated;return uv*uResolution/max(.25,scale)+vec2(lyricaHash(vec2(redraw),seed)*2.*animated);}
        vec3 lyricaHanddrawn(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){vec2 p=lyricaPaper(uv,scale,seed,animated);float n=lyricaNoise(floor(p/6.),seed);float edge=lyricaEdge(uv,stroke*(.95+.1*n));float shade=lyricaLuma(c);vec3 paper=vec3(1.,.99,.97)*(1.-grain*.025*n);return paper*(.84+.16*shade)*(1.-smoothstep(.18,.68,edge)*.96);}
        vec3 lyricaPencil(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){vec2 p=lyricaPaper(uv,scale,seed,animated);float l=lyricaLuma(c);float graphite=(1.-l)*(.1+.45*lyricaHash(floor(p),seed));float edge=smoothstep(.035,.5,lyricaEdge(uv,stroke));return vec3(1.,.985,.95)*clamp(1.-graphite-edge*.82-grain*.04*lyricaHash(floor(p*1.7),seed),.03,1.);}
        vec3 lyricaCrosshatch(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){vec2 p=lyricaPaper(uv,scale,seed,animated);float l=lyricaLuma(c);float a=1.-smoothstep(.08,.08+.12*stroke,abs(fract((p.x+p.y)/8.)-.5));float b=1.-smoothstep(.08,.08+.12*stroke,abs(fract((p.x-p.y)/9.)-.5));float lines=a*(1.-smoothstep(.2,.8,l))+b*(1.-smoothstep(.1,.5,l));return vec3(1.,.98,.92)*clamp(1.-lines*.72-lyricaEdge(uv,stroke)*.65-grain*.08*lyricaHash(floor(p),seed),.02,1.);}
        vec3 lyricaInk(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){vec2 p=lyricaPaper(uv,scale,seed,animated);float l=lyricaLuma(c);float ink=smoothstep(.22,.32+grain*.1,l-lyricaEdge(uv,stroke)*.65);return mix(vec3(.025,.018,.03),vec3(1.,.98,.94),ink)*(1.-grain*.06*lyricaHash(floor(p),seed));}
        vec3 lyricaWatercolor(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){vec2 p=lyricaPaper(uv,scale,seed,animated);vec2 d=vec2(2.*scale)/uResolution;vec3 wash=(sampleScene(uv+d)+sampleScene(uv-d)+c*2.)*.25;wash=floor(wash*9.+.5)/9.;return clamp(mix(vec3(1.,.97,.9),wash,.78)-lyricaEdge(uv,stroke)*.12-grain*.1*lyricaHash(floor(p),seed),0.,1.);}
        vec3 lyricaCel(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){vec3 bands=floor(c*4.+.5)/4.;return bands*(1.-smoothstep(.12,.6,lyricaEdge(uv,stroke))*.85);}
        vec3 lyricaComic(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){vec2 p=lyricaPaper(uv,scale,seed,animated);float dots=1.-smoothstep(.12,.3,length(fract(p/7.)-.5));vec3 cel=lyricaCel(c,uv,depth,scale,stroke,grain,seed,animated);return cel*(1.-dots*(1.-lyricaLuma(c))*.7);}
        vec3 lyricaTerminal(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){vec2 p=lyricaPaper(uv,scale,seed,animated);float l=floor(lyricaLuma(c)*6.)/6.;float scan=.75+.25*step(.35,fract(p.y*.5));return vec3(.06,.95,.25)*l*scan*(1.-grain*.2*lyricaHash(floor(p),seed));}


        float lyricaNoise(vec2 p,float seed){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(lyricaHash(i,seed),lyricaHash(i+vec2(1,0),seed),f.x),mix(lyricaHash(i+vec2(0,1),seed),lyricaHash(i+vec2(1),seed),f.x),f.y);}
        float lyricaCloud(vec2 p,float seed){return lyricaNoise(p,seed)*.57+lyricaNoise(p*2.03,seed+7.)*.28+lyricaNoise(p*4.01,seed+13.)*.15;}
        float lyricaSky(float d){return step(.999999,d);}
        float lyricaDistance(float d){return clamp(.1/max(1.-min(d,.999998),.000002)/120.,0.,1.);}
        float lyricaStructure(vec2 uv,float depth,float stroke){
            vec2 d=vec2(max(.5,stroke)*1.35)/uResolution;
            float center=1.-depth;float dx=(1.-sampleDepth(uv+vec2(d.x,0)))+(1.-sampleDepth(uv-vec2(d.x,0)))-2.*center;
            float dy=(1.-sampleDepth(uv+vec2(0,d.y)))+(1.-sampleDepth(uv-vec2(0,d.y)))-2.*center;
            float geometry=smoothstep(.012,.065,(abs(dx)+abs(dy))/max(.000015,center));
            float detail=smoothstep(.38,.88,lyricaEdge(uv,stroke));return max(geometry,detail*.82);
        }
        vec3 lyricaLineart(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){
            float edge=lyricaStructure(uv,depth,stroke);return vec3(1.-smoothstep(.16,.72,edge));
        }
        vec3 lyricaInferno(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){
            float sky=lyricaSky(depth),l=lyricaLuma(c);vec2 q=vec2(uv.x*3.5,uv.y*2.2)/max(scale,.25);
            float fire=lyricaCloud(q*vec2(2.,3.)+vec2(.025*uTime,-.08*uTime),seed);
            float smoke=smoothstep(.27,.78,fire);float tongue=sin(q.x*9.+sin(q.x*4.+uTime*.13)*1.8)*.1;
            float flame=smoothstep(.28,.78,fire*.65+(1.-uv.y)*.65+tongue);
            vec3 air=mix(vec3(.018,.009,.018),vec3(.19,.035,.025),smoke)+mix(vec3(.72,.055,.008),vec3(1.,.4,.025),flame)*flame*.85;
            vec3 terrain=mix(vec3(.035,.015,.024),c*vec3(.64,.32,.24),.68)+vec3(.75,.12,.012)*lyricaEdge(uv,stroke)*(.14+.16*uBass);
            vec2 ep=uv*uResolution/(30.*max(scale,.25))+vec2(0.,uTime*.28);vec2 cell=floor(ep);float ember=smoothstep(.965,.99,lyricaHash(cell,seed))* (1.-smoothstep(.018,.065,length(fract(ep)-.5)));
            return clamp(mix(terrain,air,sky)+vec3(1.,.35,.04)*ember*(.5+.5*grain),0.,1.);
        }
        vec3 lyricaAbyss(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){
            float l=lyricaLuma(c),fog=lyricaDistance(depth);vec3 terrain=mix(c*.28+vec3(.008,.012,.025),vec3(.025,.12,.18),fog*.68);
            vec3 air=mix(vec3(.002,.004,.018),vec3(.035,.12,.18),pow(uv.y,2.));return mix(terrain+vec3(.02,.065,.08)*l,air,lyricaSky(depth));
        }
        vec3 lyricaDreamscape(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){
            float l=lyricaLuma(c);vec3 terrain=mix(c,vec3(l),.24)*.78+vec3(.19,.13,.23);terrain=mix(terrain,vec3(.83,.74,.91),lyricaDistance(depth)*.48);
            float n=lyricaCloud(vec2(uv.x*3.,uv.y*4.)/max(scale,.25)+vec2(uTime*.012,0),seed);
            vec3 air=mix(vec3(.45,.37,.7),vec3(1.,.79,.72),smoothstep(.22,.82,n+uv.y*.3));return mix(terrain,air,lyricaSky(depth));
        }
        vec3 lyricaAurora(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){
            float wave=sin(uv.x*7./max(scale,.25)+uTime*.07+seed)*.12+sin(uv.x*17.+seed)*.035;
            float ribbon=exp(-abs(uv.y-(.43+wave))*20.)*(.3+.7*lyricaNoise(vec2(uv.x*18.,uTime*.06),seed));
            float ribbon2=exp(-abs(uv.y-(.66-wave*.6))*35.);vec2 starCell=floor(uv*uResolution/3.);
            float star=step(.998,lyricaHash(starCell,seed))*step(.42,uv.y);
            vec3 air=vec3(.008,.012,.045)+vec3(.05,.75,.44)*ribbon+vec3(.18,.19,.55)*ribbon2+vec3(star*.65);
            return mix(c*.48+vec3(.008,.018,.032),air,lyricaSky(depth));
        }
        vec3 lyricaStorm(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){
            float l=lyricaLuma(c);vec3 land=mix(c,vec3(l),.78)*vec3(.46,.5,.57);land=mix(land,vec3(.13,.16,.2),lyricaDistance(depth)*.6);
            float n=lyricaCloud(vec2(uv.x*5.,uv.y*7.)/max(scale,.25)+vec2(uTime*.035,0),seed);vec3 air=mix(vec3(.025,.03,.05),vec3(.26,.29,.32),smoothstep(.2,.9,n));
            vec2 rain=vec2(uv.x*uResolution.x+uv.y*uResolution.y*.3,uv.y*uResolution.y+uTime*180.);vec2 cell=floor(rain/vec2(16.,36.));
            float streak=(1.-smoothstep(.5,1.2,abs(mod(rain.x,16.)-8.)))*step(.88,lyricaHash(cell,seed))*grain*.1;
            return mix(land,air,lyricaSky(depth))+vec3(streak);
        }
        vec3 lyricaNeon(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){
            float edge=smoothstep(.1,.6,lyricaEdge(uv,stroke));vec3 neon=mix(vec3(.03,.88,1.),vec3(1.,.07,.48),smoothstep(.3,.7,lyricaNoise(uv*3.,seed)));
            vec3 land=c*.4+neon*edge*(.5+.15*uEnergy);vec3 air=mix(vec3(.012,.009,.045),vec3(.09,.025,.14),uv.y);return mix(land,air,lyricaSky(depth));
        }
        vec3 lyricaBlueprint(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){
            vec2 p=uv*uResolution/(48.*max(scale,.25));vec2 grid=abs(fract(p)-.5);float g=smoothstep(.48,.5,max(grid.x,grid.y))*.085;
            float e=lyricaStructure(uv,depth,stroke);return mix(vec3(.015,.065,.18)+vec3(.04,.09,.15)*lyricaLuma(c)+g,vec3(.78,.94,1.),e);
        }
        vec3 lyricaDuotone(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){
            float l=floor(lyricaLuma(c)*5.+.5)/5.;vec3 poster=mix(vec3(.035,.065,.18),vec3(1.,.52,.35),l);return poster*(1.-lyricaEdge(uv,stroke)*.6);
        }
        vec3 lyricaOnebit(vec3 c,vec2 uv,float depth,float scale,float stroke,float grain,float seed,float animated){
            vec2 p=floor(uv*uResolution/max(.5,scale));float b=mod(p.x+2.*p.y,4.)/4.;float l=lyricaLuma(c);return vec3(step(.27+b*.46,l));
        }
        """;}
    private GeminiArtLibrary(){}
}
