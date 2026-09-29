package com.tajuli.digitorandroid.editor.render

/** Original procedural artwork; no downloaded textures or competitor assets. */
internal const val EYE_EFFECT_SHADER = """
    uniform vec4 uEyesA;
    uniform vec4 uEyesB;
    uniform vec4 uEyesC;
    uniform vec4 uEyesD;
    uniform vec4 uEyesE;
    uniform vec4 uFunnyA;
    uniform vec4 uFunnyB;
    uniform vec4 uFaceRegion;
    uniform vec4 uMouthRegion;
    uniform vec4 uLeftEye;
    uniform vec4 uRightEye;
    uniform vec4 uEyeState;
    uniform vec4 uHeadPose;
    uniform vec4 uGazePose;
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
    float eyeFbm(vec2 p) {
        float f=0.0;
        f+=.50*eyeNoise(p); p=p*2.03+vec2(17.1,9.2);
        f+=.25*eyeNoise(p); p=p*2.01+vec2(7.7,21.3);
        f+=.125*eyeNoise(p); p=p*2.07+vec2(13.4,5.8);
        f+=.0625*eyeNoise(p);
        return f;
    }
    vec3 realisticEyeFire(vec2 p,float t,float phase,float lengthScale) {
        // p.y is negative above the eye. Advect layered noise upward so the flame is attached to
        // the pupil at its base while the tip can sway/turbulate independently.
        float y=-p.y;
        float h=clamp((y+.12)/(3.7*lengthScale),0.0,1.0);
        float sway=(.13*sin(t*2.3+phase+h*5.0)+.07*sin(t*4.9+phase*1.7+h*9.0))*h;
        vec2 q=vec2(p.x-sway,y);
        float n=eyeFbm(vec2(q.x*2.35,q.y*1.05-t*2.75)+vec2(phase*3.1,phase*7.4));
        float fine=eyeNoise(vec2(q.x*7.5,q.y*3.1-t*6.4+phase*5.0));
        float width=mix(.92,.10,h)*(1.0+.18*(n-.5));
        float edge=1.0-smoothstep(width*.52,width*(.98+.30*n),abs(q.x));
        float vertical=smoothstep(-.12,.18,y)*(1.0-smoothstep(3.05*lengthScale,4.25*lengthScale,y));
        float split=.70+.30*smoothstep(.35,.90,eyeNoise(vec2(q.x*4.0+phase,q.y*2.3-t*3.8)));
        float body=edge*vertical*split*(.58+.70*n+.18*fine);
        float base=exp(-dot(p*vec2(1.35,2.1),p*vec2(1.35,2.1))*2.1);
        float hot=smoothstep(.58,1.08,body+base*.72);
        float warm=smoothstep(.12,.72,body);
        vec3 flame=vec3(.22,.006,0.0)*warm;
        flame+=vec3(1.0,.105,.004)*body*1.15;
        flame+=vec3(1.0,.58,.055)*hot*.90;
        flame+=vec3(1.0,.94,.55)*base*(.72+.28*sin(t*15.0+phase));
        return flame;
    }
    float gazeCameraFacing(float gazeForward) {
        return smoothstep(.80,.93,uHeadPose.w)*smoothstep(.58,.82,gazeForward);
    }
    vec3 eyeLight(vec2 uv, vec4 eye, float openness, float roll) {
        // Blink gate is per eye. Closed eyelids remove that eye's effect completely; the short
        // transition avoids a hard pop while still following the per-frame openness signal.
        float blinkGate=smoothstep(.18,.30,openness);
        if (eye.z < .0001 || blinkGate < .001) return vec3(0);
        vec2 d=(vec2(uv.x,1.0-uv.y)-eye.xy)*vec2(1.0,uTexelSize.x/uTexelSize.y);
        float c=cos(roll),s=sin(roll);
        vec2 p=vec2(c*d.x+s*d.y,-s*d.x+c*d.y)/eye.z;
        float r=length(p), a=atan(p.y,p.x), t=uEyeTime;
        float core=exp(-dot(p*vec2(1.1,2.6),p*vec2(1.1,2.6))*2.0);
        float halo=exp(-r*r*.75);
        float ring=exp(-pow((r-.8)*12.0,2.0));
        vec3 light=vec3(0);
        if(uEyesA.x>.001) {
            light+=uEyesA.x*realisticEyeFire(p,t,.37,1.00);
        }
        if(uEyesA.y>.001) {
            vec2 gaze=uGazePose.xy;
            float gazeForward=uGazePose.z;
            if(uGazePose.w<.5) {
                float eyeMid=(uLeftEye.z>.0001 && uRightEye.z>.0001)
                    ? (uLeftEye.x+uRightEye.x)*.5 : .5;
                float side=sign(eye.x-eyeMid);
                if(abs(side)<.5) side=1.0;
                gaze=vec2(side,0.0);
                gazeForward=0.0;
            }
            float projected=length(gaze);
            vec2 dir=projected>.025 ? gaze/projected : vec2(1.0,0.0);
            float along=dot(p,dir);
            float across=p.x*dir.y-p.y*dir.x;
            float rayGate=smoothstep(-.03,.11,along);
            float cameraFacing=max(
                gazeCameraFacing(gazeForward),
                smoothstep(.84,.96,gazeForward)*(1.0-smoothstep(.34,.64,projected)));
            float directional=(1.0-cameraFacing);
            directional*=directional;

            // Thin white-hot optical core + red bloom. The beam begins exactly at the eye and
            // expands only slightly with distance, so it reads as emitted light instead of a line
            // pasted across the frame.
            float coreWidth=.030+.0045*max(along,0.0);
            float glowWidth=.095+.010*max(along,0.0);
            float beamCore=exp(-pow(across/max(coreWidth,.025),2.0))*rayGate*
                exp(-max(along,0.0)*.045)*directional;
            float beamGlow=exp(-pow(across/max(glowWidth,.07),2.0))*rayGate*
                exp(-max(along,0.0)*.065)*directional;
            float eyeEmitter=exp(-r*r*3.6);

            // Looking into the lens has almost zero projected 2D beam length. A compact emitter,
            // concentric depth rings and radial diffraction spikes create the visual cue that the
            // laser is travelling out of the screen toward the camera.
            float pulse=.92+.08*sin(t*18.0);
            float lensCore=exp(-r*r*1.25);
            float lensHalo=exp(-r*r*.13)*(1.0-smoothstep(5.0,8.5,r));
            float lensRing1=exp(-pow((r-.72)*7.5,2.0));
            float lensRing2=exp(-pow((r-1.48)*4.4,2.0));
            float lensSpokes=pow(abs(cos(a*4.0)),24.0)*exp(-r*.33);
            float forwardBurst=cameraFacing*pulse;

            light+=uEyesA.y*(
                vec3(1.0,.035,.012)*(beamGlow*.95+eyeEmitter*.42+
                    forwardBurst*(lensHalo*.22+lensRing1*.62+lensRing2*.24))+
                vec3(1.0,.82,.68)*(beamCore*1.55+eyeEmitter*1.15+
                    forwardBurst*(lensCore*2.75+lensSpokes*.88))
            );
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
        if(uEyesD.x>.001) {
            vec2 gaze=uGazePose.xy;
            float gazeForward=uGazePose.z;
            if(uGazePose.w<.5) { gaze=vec2(0.0,-1.0); gazeForward=0.0; }
            float projected=length(gaze);
            vec2 dir=projected>.025 ? gaze/projected : vec2(1.0,0.0);
            float along=dot(p,dir);
            float across=p.x*dir.y-p.y*dir.x;
            float cameraFacing=max(
                gazeCameraFacing(gazeForward),
                smoothstep(.84,.96,gazeForward)*(1.0-smoothstep(.34,.64,projected)));
            float directional=(1.0-cameraFacing);
            directional*=directional;
            float rayGate=smoothstep(-.04,.12,along);
            float jitter=.055*sin(along*7.0-t*24.0)+.025*sin(along*17.0+t*31.0);
            jitter+=.035*(eyeNoise(vec2(along*2.8,t*5.0))-0.5);
            float bolt=exp(-abs(across-jitter)*34.0)*rayGate*
                exp(-max(along,0.0)*.07)*directional;
            float guide=exp(-across*across*52.0)*rayGate*
                exp(-max(along,0.0)*.09)*directional;

            // Forward electric energy uses broken concentric arcs and a cyan lens burst rather
            // than a laser line, while sharing the same gaze/camera-facing decision.
            float rr=r+.035*sin(a*7.0+t*8.0);
            float ring1=exp(-pow((rr-.72)*10.0,2.0));
            float ring2=exp(-pow((rr-1.38)*6.0,2.0));
            float broken=.35+.65*step(.25,eyeNoise(vec2(floor(a*9.0),floor(t*12.0))));
            float spokes=pow(abs(cos(a*6.0+t*.7)),30.0)*exp(-r*.48);
            float front=cameraFacing*(.90+.10*sin(t*20.0));
            float emitter=exp(-r*r*2.9);
            light+=uEyesD.x*(
                vec3(.02,.52,1.0)*(bolt*1.25+guide*.30+
                    front*(ring1*.75+ring2*.35)*broken)+
                vec3(.66,.96,1.0)*(guide*.85+emitter+
                    front*(exp(-r*r*1.4)*2.15+spokes*.70))
            );
        }
        if(uEyesD.y>.001 || uEyesD.z>.001) {
            vec3 flame1=realisticEyeFire(p*vec2(.94,.86),t,1.23,1.18);
            vec3 flame2=realisticEyeFire(p*vec2(1.10,.72),t,2.41,1.38);
            // Flame Eyes 2 is taller/more turbulent rather than unrealistically magenta.
            light+=uEyesD.y*flame1+uEyesD.z*(flame2*1.08+vec3(.10,.012,0.0)*halo);
        }
        if(uEyesD.w>.001) {
            float h=(-p.y-1.7)/3.0;
            float side=sign(eye.x-(uLeftEye.x+uRightEye.x)*.5);
            float curve=side*(.35+h*h*.9);
            float horn=exp(-pow((p.x-curve)/max(.03,.38*(1.0-h)),2.0))*step(0.0,h)*(1.0-step(1.0,h));
            light+=uEyesD.w*vec3(1,.04,.65)*horn*(1.1+.5*sin(t*8.0+h*9.0));
        }
        if(uEyesE.y>.001) {
            float flare=exp(-p.y*p.y*160.0)*exp(-abs(p.x)*.45);
            light+=uEyesE.y*(vec3(1,.05,.7)*(core+halo*.4)+vec3(1,.7,.95)*flare);
        }
        return light*blinkGate;
    }
    vec2 eyeSourceUv(vec2 uv) {
        vec2 p=uv*2.0-1.0-uEyeTranslation;
        return vec2(uEyeTransform.x*p.x+uEyeTransform.y*p.y,-uEyeTransform.y*p.x+uEyeTransform.x*p.y)/uEyeTransform.zw*.5+.5;
    }
    vec2 eyeFrameUv(vec2 uv) {
        vec2 p=(uv*2.0-1.0)*uEyeTransform.zw;
        return (vec2(uEyeTransform.x*p.x-uEyeTransform.y*p.y,uEyeTransform.y*p.x+uEyeTransform.x*p.y)+uEyeTranslation)*.5+.5;
    }
    vec2 faceMetricScale() {
        return vec2(1.0,uTexelSize.x/uTexelSize.y);
    }
    vec2 faceToLocal(vec2 d) {
        vec2 q=d*faceMetricScale();
        float c=cos(uHeadPose.z),s=sin(uHeadPose.z);
        return vec2(c*q.x+s*q.y,-s*q.x+c*q.y);
    }
    vec2 faceFromLocal(vec2 d) {
        float c=cos(uHeadPose.z),s=sin(uHeadPose.z);
        vec2 q=vec2(c*d.x-s*d.y,s*d.x+c*d.y);
        return q/faceMetricScale();
    }
    vec2 regionalWarp(vec2 uv,vec4 region,vec2 scale,float amount) {
        if(region.z<.001 || region.w<.001 || amount<.001) return uv;
        vec2 local=faceToLocal(uv-region.xy);
        vec2 extent=max(region.zw*faceMetricScale(),vec2(.001));
        vec2 p=local/extent;
        p.x+=uHeadPose.x*.14*p.y;
        float weight=1.0-smoothstep(.24,1.35,length(p));
        weight=weight*weight*(3.0-2.0*weight);
        vec2 warped=local/mix(vec2(1),scale,weight*amount);
        return region.xy+faceFromLocal(warped);
    }
    vec2 funnyUv(vec2 uv) {
        vec2 p=eyeSourceUv(uv);
        if(uFaceRegion.z<.001) return uv;
        p=regionalWarp(p,uFaceRegion,vec2(1.55,1.35),uFunnyA.w);
        p=regionalWarp(p,uMouthRegion,vec2(1.1,1.9),uFunnyA.x);
        p=regionalWarp(p,uMouthRegion,vec2(1.8,1.1),uFunnyA.z);
        p=regionalWarp(p,uMouthRegion,vec2(1.65,1.5),uFunnyB.x);
        p=regionalWarp(p,uMouthRegion,vec2(2.1,1.9),uFunnyB.y);
        p=regionalWarp(p,uMouthRegion,vec2(.55,1.2),uFunnyB.z);
        vec2 faceExtent=max(uFaceRegion.zw*faceMetricScale(),vec2(.001));
        vec2 f=faceToLocal(p-uFaceRegion.xy)/faceExtent;
        f.x+=uHeadPose.x*.10*f.y;
        float faceWeight=1.0-smoothstep(.4,1.2,length(f));
        vec2 localShift=vec2(
            uFunnyB.z*.18*faceExtent.x*sin(f.y*3.0),
            uFunnyA.y*.12*faceExtent.y*sin(f.x*3.14159)
        )*faceWeight;
        p+=faceFromLocal(localShift);
        if(uEyesE.z>.001) {
            float band=floor(p.y*70.0);
            p.x+=(eyeHash(vec2(band,floor(uEyeTime*9.0)))-.5)*.075*uEyesE.z*faceWeight;
        }
        return clamp(eyeFrameUv(p),.001,.999);
    }
    vec3 applyEyeEffects(vec3 rgb, vec2 uv) {
        vec2 ndc=uv*2.0-1.0-uEyeTranslation;
        ndc=vec2(uEyeTransform.x*ndc.x+uEyeTransform.y*ndc.y,
                 -uEyeTransform.y*ndc.x+uEyeTransform.x*ndc.y)/uEyeTransform.zw;
        uv=ndc*.5+.5;
        vec3 light=eyeLight(uv,uLeftEye,uEyeState.x,uEyeState.z)
                  +eyeLight(uv,uRightEye,uEyeState.y,uEyeState.w);
        if(uFaceRegion.z>.001 && uFaceRegion.w>.001) {
            vec2 p=(uv-uFaceRegion.xy)/uFaceRegion.zw;
            float inside=1.0-smoothstep(.85,1.05,length(p));
            float scan=exp(-pow((p.y-sin(uEyeTime*2.0))*24.0,2.0));
            light+=uEyesE.x*vec3(.02,.8,1)*scan*inside;
            float grid=exp(-abs(sin(p.x*20.0))*30.0)+exp(-abs(sin(p.y*16.0+uEyeTime))*30.0);
            light+=uEyesE.w*vec3(.02,.7,.5)*grid*inside*.45;
        }
        // Screen-like energy response retains image texture and rolls bright cores into white.
        return rgb+(1.0-rgb)*(1.0-exp(-light*1.8));
    }
"""
