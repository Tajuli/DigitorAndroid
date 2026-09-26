package com.tajuli.digitorandroid.editor.render

/** Vector/particle artwork generated in source coordinates and anchored to the detected silhouette. */
internal const val BODY_DECORATION_SHADER = """
    uniform vec4 uDecor0; uniform vec4 uDecor1; uniform vec4 uDecor2; uniform vec4 uDecor3;
    uniform vec4 uDecor4; uniform vec4 uDecor5; uniform vec4 uDecor6; uniform vec4 uDecor7;
    uniform vec4 uBodyBounds;
    float dh(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
    float lineGlow(float d){return exp(-abs(d)*65.0)+.16*exp(-abs(d)*12.0);}
    float heartShape(vec2 p){p.y+=.2;float q=p.x*p.x+p.y*p.y-1.0;return q*q*q-p.x*p.x*p.y*p.y*p.y;}
    float starShape(vec2 p){float a=atan(p.y,p.x);return length(p)-(.7+.28*cos(a*5.0));}
    vec3 decorateBody(vec3 rgb,vec2 uv,float body,float edge){
        if(dot(uDecor0+uDecor1+uDecor2+uDecor3+uDecor4+uDecor5+uDecor6+uDecor7,vec4(1.0))<.001)return rgb;
        if(uBodyBounds.z<.001 || uBodyBounds.w<.001)return rgb;
        vec2 p=(uv-uBodyBounds.xy)/uBodyBounds.zw;
        float t=uTime;float r=length(p);float a=atan(p.y,p.x);vec3 light=vec3(0);
        float behind=1.0-body;
        vec3 cyan=vec3(.05,.8,1),pink=vec3(1,.08,.6),gold=vec3(1,.55,.04);
        if(uDecor0.x>.001){
            float rings=lineGlow(length(p*vec2(.65,2.5))-.95)+.6*lineGlow(length(p*vec2(.72,3.8))-.95);
            float ticks=pow(max(0.0,cos(a*24.0-t*2.0)),18.0)*lineGlow(length(p*vec2(.65,2.5))-1.15);
            light+=uDecor0.x*cyan*(rings+ticks)*(p.y<0.0?1.0:behind);
        }
        for(int i=0;i<12;i++){
            float f=float(i);float seed=dh(vec2(f,2));
            vec2 orbit=vec2(cos(f*2.4+t*(.3+seed)),sin(f*2.4+t*.4))*vec2(1.35,1.1);
            float particle=exp(-dot(p-orbit,p-orbit)*700.0);
            light+=uDecor0.y*gold*particle*(.5+.5*sin(t*3.0+f));
            vec2 note=(p-orbit)*12.0;
            float musical=exp(-dot(note*vec2(.7,1.1),note*vec2(.7,1.1))*3.0);
            musical+=exp(-abs(note.x-.5)*18.0)*step(0.0,note.y)*step(note.y,1.8);
            musical+=exp(-abs(note.y-1.7+.3*note.x)*16.0)*step(.5,note.x)*step(note.x,1.4);
            light+=uDecor0.z*cyan*musical;
            light+=uDecor1.x*cyan*lineGlow(length((p-orbit)*9.0)-.8)*.5;
            float heart=heartShape((p-orbit)*7.0);
            light+=uDecor1.y*pink*exp(-abs(heart)*8.0)*step(length((p-orbit)*7.0),1.7);
            light+=uDecor2.x*gold*lineGlow(starShape((p-orbit)*7.0))*.65;
            light+=uDecor3.w*cyan*lineGlow(starShape((p-orbit)*4.0))*behind;
        }
        if(uDecor0.w>.001 || uDecor3.z>.001){
            vec2 w=vec2(abs(p.x)-.25,p.y-.2);
            float flap=.85+.15*sin(t*3.0);w.x/=flap;
            float wing=pow(w.x-1.1,2.0)/1.35+pow(w.y-.25,2.0)/.65-1.0;
            float lower=pow(w.x-.7,2.0)/.6+pow(w.y+.5,2.0)/.28-1.0;
            float veins=pow(max(0.0,sin(atan(w.y,w.x)*14.0+w.x*3.0)),22.0)*step(wing,0.0);
            light+=behind*step(0.0,w.x)*(uDecor0.w*(cyan*lineGlow(wing)+pink*lineGlow(lower)+cyan*veins*.45));
            float feathers=0.0;
            for(int j=0;j<7;j++){
                float f=float(j);vec2 q=w-vec2(.2+f*.18,.55-f*.14);
                feathers+=lineGlow(length(q*vec2(.7,5.0))-.72)*step(0.0,q.x+.7);
            }
            light+=uDecor3.z*behind*step(0.0,w.x)*vec3(.45,.8,1)*feathers*.6;
        }
        if(uDecor1.z>.001){float helix=lineGlow(p.x-1.25*sin(p.y*5.0+t*4.0));light+=uDecor1.z*mix(cyan,pink,.5+.5*sin(p.y*4.0))*helix*exp(-p.y*p.y*.6);}
        if(uDecor1.w>.001)rgb=overlayBodyClone(rgb,uv-vec2(.12*sin(t),.02),uDecor1.w*.35);
        if(uDecor2.y>.001)light+=uDecor2.y*gold*lineGlow(length((p-vec2(0,1.12))*vec2(1,4))-.6);
        if(uDecor2.z>.001)light+=uDecor2.z*cyan*lineGlow(sin(a*3.0+r*9.0-t*4.0))*.18*exp(-r*r*.5)*behind;
        if(uDecor2.w>.001){float diamond=abs(p.x)*.6+abs(p.y)*.7;light+=uDecor2.w*mix(cyan,pink,.5+.5*sin(a+t))*lineGlow(diamond-1.0)*behind;}
        if(uDecor3.x>.001){float fire=.5+.5*sin(p.x*19.0+p.y*11.0+t*8.0);light+=uDecor3.x*gold*(edge*(.7+fire)+lineGlow(p.x-1.1*sin(p.y*5.0+t*3.0))*.5*exp(-p.y*p.y));}
        if(uDecor3.y>.001){float band=step(.82,dh(vec2(floor(p.y*18.0),floor(t*7.0))));rgb=mix(rgb,rgb.brg,uDecor3.y*body*band*.8);light+=cyan*edge*band*uDecor3.y;}
        if(uDecor4.x>.001){float wheel=lineGlow(r-1.35)+lineGlow(r-1.1);float spoke=pow(max(0.0,cos(a*8.0+t)),36.0)*step(r,1.35);light+=uDecor4.x*gold*(wheel+spoke)*behind;}
        if(uDecor4.y>.001){float waves=lineGlow(length((p-vec2(0,.6*sin(t)))*vec2(.7,3.0))-1.0);light+=uDecor4.y*cyan*waves*(.4+.6*behind);}
        if(uDecor4.z>.001){float bolt=sin(p.y*23.0+t*11.0)*.15+sin(p.y*51.0-t*7.0)*.07;light+=uDecor4.z*vec3(.55,.2,1)*lineGlow(abs(p.x)-1.0-bolt)*exp(-p.y*p.y*.5);}
        light+=uDecor4.w*gold*(edge+max(0.0,maxRing(uv,12.0)-body)*.35);
        if(uDecor5.x>.001){float scan=exp(-pow((p.y-sin(t*2.0))*18.0,2.0));light+=uDecor5.x*cyan*(scan*body+edge*.3);}
        if(uDecor5.y>.001){float dots=step(.89,dh(floor(uv/uTexelSize/3.0)+floor(t*6.0)));light+=uDecor5.y*vec3(1)*dots*body;}
        light+=uDecor5.z*vec3(1)*edge*(.8+.2*sin(p.y*80.0+t*5.0));
        light+=uDecor5.w*(.5+.5*cos(vec3(0,2,4)+a*2.0+t*2.0))*max(0.0,maxRing(uv,16.0)-body)*1.2;
        if(uDecor6.x>.001)rgb=mix(rgb,pink,uDecor6.x*body*(.35+.35*sin(t*10.0)));
        if(uDecor6.y>.001){float sparkle=pow(dh(floor(uv/uTexelSize/2.0)+floor(t*8.0)),12.0)*5.0;light+=uDecor6.y*vec3(1)*edge*sparkle;}
        if(uDecor6.z>.001){float c=cos(.35*sin(t)),s=sin(.35*sin(t));vec2 q=uv-uBodyBounds.xy;q=mat2(c,-s,s,c)*q;rgb=overlayBodyClone(rgb,q+uBodyBounds.xy+vec2(.2,0),uDecor6.z*.8);}
        if(uDecor6.w>.001){float k=.55+.45*fract(t*.45);rgb=overlayBodyClone(rgb,(uv-uBodyBounds.xy)/k+uBodyBounds.xy+vec2((1.0-k)*.5,0),uDecor6.w*(1.0-fract(t*.45)));}
        if(uDecor7.x>.001){float k=fract(t*.45);rgb=overlayBodyClone(rgb,uv-vec2(k*.35,0),uDecor7.x*(1.0-k)*.8);}
        if(uDecor7.y>.001){for(int i=1;i<=3;i++){float f=float(i);rgb=overlayBodyClone(rgb,uv-vec2(.04*f*sin(t*2.0),0),uDecor7.y*(.35/f));}}
        if(uDecor7.z>.001){float streak=pow(max(0.0,sin(p.y*65.0+t*16.0)),28.0)*exp(-abs(p.x)*.65);light+=uDecor7.z*cyan*streak*behind*.65;}
        return rgb+(1.0-rgb)*(1.0-exp(-light*1.5));
    }
"""
