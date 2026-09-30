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
    uniform vec4 uLeftGaze;
    uniform vec4 uRightGaze;
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
    float electricFrontScore(vec4 gaze) {
        if(gaze.w<.055) return 0.0;
        float projected=length(gaze.xy);
        return smoothstep(.78,.93,uHeadPose.w)*
            smoothstep(.56,.84,gaze.z)*
            (1.0-smoothstep(.22,.60,projected));
    }
    vec3 eyeLight(vec2 uv, vec4 eye, float openness, float roll, vec4 eyeGaze) {
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
            float gazeConfidence=eyeGaze.w;
            vec2 gaze=eyeGaze.xy;
            float gazeForward=eyeGaze.z;
            if(gazeConfidence<.055) {
                gaze=uGazePose.xy;
                gazeForward=uGazePose.z;
                if(uGazePose.w<.5) {
                    gaze=vec2(uHeadPose.x,uHeadPose.y);
                    gazeForward=uHeadPose.w;
                }
                gazeConfidence=.12;
            }

            // Render in the same eye-local basis used by the pupil tracker. This keeps the emitter
            // welded to the eye while the beam angle changes naturally with real per-eye gaze.
            vec2 localGaze=vec2(c*gaze.x+s*gaze.y,-s*gaze.x+c*gaze.y);
            float projected=length(localGaze);
            vec2 dir=projected>.025 ? localGaze/projected : vec2(1.0,0.0);
            float along=dot(p,dir);
            float across=p.x*dir.y-p.y*dir.x;
            float cameraFacing=max(
                gazeCameraFacing(gazeForward),
                smoothstep(.86,.97,gazeForward)*(1.0-smoothstep(.28,.58,projected)));
            float confidenceGate=smoothstep(.10,.50,gazeConfidence);
            float directional=(1.0-cameraFacing)*mix(.28,1.0,confidenceGate);
            float forward=max(along,0.0);
            float rayGate=smoothstep(-.08,.10,along);

            // Straight-at-lens gaze still keeps two visible tubes. Each eye gets a short,
            // slightly inward "graze" direction so the pair appears to pass very close to the
            // camera instead of collapsing into two glowing eye blobs.
            float eyeMid=(uLeftEye.z>.0001 && uRightEye.z>.0001)
                ? (uLeftEye.x+uRightEye.x)*.5 : .5;
            float eyeSide=sign(eye.x-eyeMid);
            if(abs(eyeSide)<.5) eyeSide=1.0;
            vec2 grazeDir=normalize(vec2(-eyeSide*.16,-.025)+localGaze*.24);
            float grazeAlong=dot(p,grazeDir);
            float grazeForward=max(grazeAlong,0.0);
            float grazeAcross=p.x*grazeDir.y-p.y*grazeDir.x;
            float grazeWindow=smoothstep(-.08,.08,grazeAlong)*
                (1.0-smoothstep(5.2,8.2,grazeForward));
            float grazeWidth=.085+.062*grazeForward;
            float frontTubeCore=exp(-pow(
                grazeAcross/max(.030+.010*grazeForward,.030),2.0))*
                grazeWindow*cameraFacing;
            float frontTubeBody=exp(-pow(
                grazeAcross/max(grazeWidth,.075),2.25))*
                grazeWindow*cameraFacing;

            // CapCut-reference behavior without copying assets: the beam is not a thin GLSL line.
            // It starts narrow at the pupil, widens into a blown-out volumetric cone, has a
            // white-hot center, warm gold bloom and animated ragged/electric plasma at the edges.
            float coarse=eyeFbm(vec2(forward*.19-t*1.45,across*1.65+t*.17));
            float fine=eyeFbm(vec2(forward*.47+t*.92,across*4.20-t*.63));
            float coneWidth=.10+forward*.055;
            float coreWidth=.032+forward*.010;
            float hazeWidth=.28+forward*.115;
            float sway=(coarse-.5)*coneWidth*.30+
                sin(forward*1.18-t*7.4)*(.012+.003*forward);
            float shifted=across-sway;
            float raggedWidth=coneWidth*(.78+.42*fine);

            float beamCore=exp(-pow(shifted/max(coreWidth,.028),2.0));
            float beamBody=exp(-pow(abs(shifted)/max(raggedWidth,.070),2.55));
            float beamHaze=exp(-pow(shifted/max(hazeWidth,.20),2.0))*
                (.62+.58*coarse);
            float edgeDistance=abs(shifted)-raggedWidth*.78;
            float electricEdge=exp(-pow(
                edgeDistance/max(.026+.006*forward,.026),2.0))*(.32+.78*fine);
            float filamentOffset=
                sin(forward*2.25-t*14.0)*(.018+.0025*forward)+
                (fine-.5)*(.020+.002*forward);
            float filament=exp(-pow(
                (shifted-filamentOffset)/max(.017+.003*forward,.017),2.0));

            float reach=mix(.115,.008,confidenceGate);
            float axial=rayGate*directional;
            beamCore*=axial*exp(-forward*reach);
            beamBody*=axial*exp(-forward*(reach+.004));
            beamHaze*=axial*exp(-forward*(reach+.014));
            electricEdge*=axial*exp(-forward*(reach+.010));
            filament*=axial*exp(-forward*(reach+.006));

            // Subtle ionized smoke/haze sits outside the hot core. It drifts more slowly than the
            // plasma edge and changes shape over time, which keeps the effect from reading as a
            // clean computer-drawn tube.
            float smokeNoise=eyeFbm(vec2(
                forward*.115-t*.34,
                shifted*.72+t*.095));
            float smokeEnvelope=exp(-pow(
                shifted/max(hazeWidth*1.72,.30),2.0))*rayGate*directional*
                exp(-forward*(reach+.026));
            float smoke=smokeEnvelope*
                smoothstep(.24,.78,smokeNoise)*
                (1.0-.42*clamp(beamBody,0.0,1.0));

            float frontSmokeNoise=eyeFbm(vec2(
                grazeForward*.16-t*.29,
                grazeAcross*.82+t*.12));
            float frontSmoke=exp(-pow(
                grazeAcross/max(.30+.10*grazeForward,.30),2.0))*
                grazeWindow*cameraFacing*
                smoothstep(.25,.76,frontSmokeNoise);

            // Bright eye socket/root flare visually joins the beam to the actual pupil/eyelid.
            float lidWhite=exp(-dot(p*vec2(.58,2.40),p*vec2(.58,2.40))*1.70);
            float rootFlash=exp(-r*r*2.75);
            float socketBloom=exp(-dot(p*vec2(.42,1.15),p*vec2(.42,1.15))*.50);

            // Looking straight into the camera collapses the projected cone into a large soft
            // lens-facing bloom rather than a fake arbitrary screen-space ray.
            float pulse=.94+.06*sin(t*10.5);
            float localFrontHalo=exp(-r*r*.105)*(1.0-smoothstep(6.5,9.5,r));
            float front=cameraFacing*pulse;

            // Do not "burn" the eye when the light is aimed at the lens. The local emitter stays
            // visible, but most frontal energy moves into the two graze tubes and the frame-space
            // lens flare added below.
            float localEmitterScale=1.0-.68*cameraFacing;
            light+=uEyesA.y*(
                vec3(1.00,.995,.93)*(
                    beamCore*2.45+beamBody*1.42+filament*.82+
                    (lidWhite*1.30+rootFlash*.88)*localEmitterScale+
                    frontTubeCore*2.65+frontTubeBody*1.05
                )+
                vec3(1.00,.67,.18)*(
                    beamBody*.40+beamHaze*.98+electricEdge*.64+
                    socketBloom*.20*localEmitterScale+
                    frontTubeBody*.58+localFrontHalo*.14*front+
                    smoke*.26+frontSmoke*.28
                )+
                vec3(.72,.68,.61)*(smoke*.18+frontSmoke*.20)+
                vec3(1.00,.28,.015)*(electricEdge*.16+beamHaze*.10)
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
        // uEyesD.x (slot 12) is reserved after removing the legacy Electric Eyes effect.
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
        vec2 frameUv=uv;
        vec2 ndc=uv*2.0-1.0-uEyeTranslation;
        ndc=vec2(uEyeTransform.x*ndc.x+uEyeTransform.y*ndc.y,
                 -uEyeTransform.y*ndc.x+uEyeTransform.x*ndc.y)/uEyeTransform.zw;
        uv=ndc*.5+.5;
        vec3 light=eyeLight(uv,uLeftEye,uEyeState.x,uEyeState.z,uLeftGaze)
                  +eyeLight(uv,uRightEye,uEyeState.y,uEyeState.w,uRightGaze);

        // When both eyes look into the camera, move the strongest overexposure to the virtual lens
        // rather than the eye sockets. The two local front tubes remain separate; this broad
        // frame-space flare is the "lens scorching" cue seen when they skim close to the viewer.
        vec4 effectiveLeft=uLeftGaze.w<.055 ? vec4(uGazePose.xyz,.12) : uLeftGaze;
        vec4 effectiveRight=uRightGaze.w<.055 ? vec4(uGazePose.xyz,.12) : uRightGaze;
        float pairFront=min(electricFrontScore(effectiveLeft),electricFrontScore(effectiveRight));
        pairFront*=smoothstep(.18,.32,uEyeState.x)*smoothstep(.18,.32,uEyeState.y)*uEyesA.y;
        vec2 lensP=vec2(frameUv.x-.5,(frameUv.y-.5)*(uTexelSize.x/uTexelSize.y));
        float lensNoise=eyeFbm(lensP*3.1+vec2(-uEyeTime*.18,uEyeTime*.11));
        float lensBurn=exp(-dot(lensP*vec2(.88,1.06),lensP*vec2(.88,1.06))*7.5);
        float lensHalo=exp(-dot(lensP,lensP)*2.6)*(.76+.28*lensNoise);
        float lensStreak=exp(-lensP.y*lensP.y*115.0)*exp(-abs(lensP.x)*2.1);
        float lensMist=exp(-dot(lensP*vec2(.72,.92),lensP*vec2(.72,.92))*3.8)*
            smoothstep(.22,.76,lensNoise);
        light+=pairFront*(
            vec3(1.00,.995,.92)*(lensBurn*1.28+lensStreak*.48)+
            vec3(1.00,.67,.20)*(lensHalo*.34+lensMist*.20)+
            vec3(.72,.69,.64)*lensMist*.12
        );
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
