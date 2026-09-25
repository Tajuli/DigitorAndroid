package com.tajuli.digitorandroid.editor.render

/** Original procedural artwork; no downloaded textures or competitor assets. */
internal const val EYE_EFFECT_SHADER = """
    uniform vec4 uEyesA;
    uniform vec4 uEyesB;
    uniform vec4 uEyesC;
    uniform vec4 uLeftEye;
    uniform vec4 uRightEye;
    uniform vec4 uEyeState;
    uniform float uEyeTime;
    uniform vec4 uEyeTransform;
    uniform vec2 uEyeTranslation;

    float eyeHash(vec2 p) { return fract(sin(dot(p, vec2(127.1,311.7)))*43758.5453); }
    float eyeNoise(vec2 p) {
        vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
        return mix(mix(eyeHash(i),eyeHash(i+vec2(1,0)),f.x),
                   mix(eyeHash(i+vec2(0,1)),eyeHash(i+vec2(1,1)),f.x),f.y);
    }
    vec3 eyePalette(float t) { return .5+.5*cos(6.28318*(t+vec3(0,.33,.67))); }
    vec3 eyeLight(vec2 uv, vec4 eye, float openness, float roll) {
        if (eye.z < .0001 || openness < .02) return vec3(0);
        vec2 d=(vec2(uv.x,1.0-uv.y)-eye.xy)*vec2(1.0,uTexelSize.x/uTexelSize.y);
        float c=cos(roll),s=sin(roll);
        vec2 p=vec2(c*d.x+s*d.y,-s*d.x+c*d.y)/eye.z;
        float r=length(p), a=atan(p.y,p.x), t=uEyeTime;
        float core=exp(-dot(p*vec2(1.1,2.6),p*vec2(1.1,2.6))*2.0);
        float halo=exp(-r*r*.75);
        float ring=exp(-pow((r-.8)*12.0,2.0));
        vec3 light=vec3(0);
        if(uEyesA.x>.001) {
            float n=eyeNoise(vec2(p.x*3.0,p.y*2.0+t*4.0));
            float flame=(1.0-smoothstep(.1,1.0,abs(p.x)/(1.05+min(p.y,0.0)*.18)+n*.45));
            flame*=smoothstep(-3.5,-.3,p.y)*(1.0-smoothstep(.1,.65,p.y));
            light+=uEyesA.x*(vec3(1,.17,.015)*flame*.9+vec3(1,.75,.2)*core+vec3(.3,.045,0)*halo);
        }
        if(uEyesA.y>.001) {
            float beam=exp(-p.y*p.y*210.0)*exp(-abs(p.x)*.065);
            float bloom=exp(-p.y*p.y*16.0)*exp(-abs(p.x)*.12);
            light+=uEyesA.y*(vec3(1,.04,.02)*bloom*.8+vec3(1,.75,.6)*(beam+core));
        }
        if(uEyesA.z>.001) {
            float arc=abs(p.y-.2*sin(p.x*13.0+t*12.0)-.12*sin(p.x*29.0-t*9.0));
            float bolts=exp(-arc*45.0)*exp(-abs(p.x)*.5);
            light+=uEyesA.z*(vec3(.15,.4,1)*(bolts*1.3+halo*.3)+vec3(.7,.9,1)*(core+bolts*.4));
        }
        if(uEyesA.w>.001) {
            float waves=exp(-pow((r-.9-.18*sin(a*5.0-t*4.0))*8.0,2.0));
            light+=uEyesA.w*(vec3(.7,.03,1)*waves+vec3(.1,.8,1)*core+vec3(.2,.01,.4)*halo);
        }
        if(uEyesB.x>.001) {
            float shards=pow(max(0.0,cos(a*6.0+r*7.0)),18.0)*exp(-r*r*.5);
            light+=uEyesB.x*(vec3(.12,.7,1)*(ring*.7+shards)+vec3(.6,.95,1)*core);
        }
        if(uEyesB.y>.001) {
            float spiral=.5+.5*sin(a*3.0-r*10.0+t*2.0);
            float stars=pow(eyeNoise(p*25.0),16.0)*14.0;
            light+=uEyesB.y*(mix(vec3(.3,.03,.7),vec3(.1,.45,1),spiral)*halo*.8+vec3(1,.7,1)*stars*halo);
        }
        if(uEyesB.z>.001) {
            float ellipse=length(p*vec2(.9,1.8));
            float neon=exp(-pow((ellipse-1.0)*16.0,2.0));
            light+=uEyesB.z*(vec3(.02,1,.6)*(neon+halo*.2)+vec3(.6,1,.9)*core*.35);
        }
        if(uEyesB.w>.001) {
            float corona=pow(max(0.0,sin(a*16.0+t*1.5)),5.0)*exp(-r*.8);
            light+=uEyesB.w*(vec3(1,.36,.015)*(ring+corona)+vec3(1,.85,.3)*core);
        }
        if(uEyesC.x>.001) {
            float scan=exp(-pow((p.y-.7*sin(t*2.0))*35.0,2.0))*(1.0-smoothstep(.8,1.2,abs(p.x)));
            float reticle=ring*step(.25,abs(sin(a*4.0+t)));
            light+=uEyesC.x*(vec3(.02,.85,1)*(reticle+scan*.8)+vec3(.1,.3,.45)*halo);
        }
        if(uEyesC.y>.001) {
            vec2 q=p*1.3; q.y=-q.y;
            float heartBase=q.x*q.x+q.y*q.y-1.0;
            float heart=heartBase*heartBase*heartBase-q.x*q.x*q.y*q.y*q.y;
            float fill=1.0-smoothstep(-.05,.08,heart);
            light+=uEyesC.y*(vec3(1,.02,.23)*fill*.75+vec3(.5,.02,.12)*halo);
        }
        if(uEyesC.z>.001) {
            float starRadius=.60+.27*cos(a*5.0+t*.8);
            float star=1.0-smoothstep(starRadius,starRadius+.07,r);
            light+=uEyesC.z*(vec3(1,.75,.15)*star+vec3(.4,.2,.02)*halo);
        }
        if(uEyesC.w>.001) {
            light+=uEyesC.w*eyePalette(a/6.28318+r*.18-t*.15)*(ring+core*.8+halo*.18);
        }
        return light*openness;
    }
    vec3 applyEyeEffects(vec3 rgb, vec2 uv) {
        vec2 ndc=uv*2.0-1.0-uEyeTranslation;
        ndc=vec2(uEyeTransform.x*ndc.x+uEyeTransform.y*ndc.y,
                 -uEyeTransform.y*ndc.x+uEyeTransform.x*ndc.y)/uEyeTransform.zw;
        uv=ndc*.5+.5;
        vec3 light=eyeLight(uv,uLeftEye,uEyeState.x,uEyeState.z)
                  +eyeLight(uv,uRightEye,uEyeState.y,uEyeState.w);
        // Screen-like energy response retains image texture and rolls bright cores into white.
        return rgb+(1.0-rgb)*(1.0-exp(-light*1.8));
    }
"""
