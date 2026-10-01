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
    vec4 electricResolvedGaze(vec4 gaze) {
        if(gaze.w>=.055) return gaze;
        if(uGazePose.w>=.5) return vec4(uGazePose.xyz,.12);
        return vec4(uHeadPose.xy,uHeadPose.w,.12);
    }
    vec4 electricRenderGaze(vec4 ownGaze) {
        vec4 own=electricResolvedGaze(ownGaze);
        bool paired=uLeftEye.z>.0001 && uRightEye.z>.0001 &&
            uEyeState.x>.18 && uEyeState.y>.18;
        if(!paired) return own;

        // Treat both eyes as one binocular emitter: two separate pupil origins, one render axis.
        vec4 left=electricResolvedGaze(uLeftGaze);
        vec4 right=electricResolvedGaze(uRightGaze);
        float lw=max(left.w,.08), rw=max(right.w,.08);
        vec2 shared=(left.xy*lw+right.xy*rw)/(lw+rw);
        float sharedForward=(left.z*lw+right.z*rw)/(lw+rw);

        // Opposing noisy eye estimates must never split the visual beams in two directions.
        if(length(shared)<.06 && max(length(left.xy),length(right.xy))>.16) {
            bool useLeft=left.w>=right.w;
            shared=useLeft ? left.xy : right.xy;
            sharedForward=useLeft ? left.z : right.z;
        }
        return vec4(shared,sharedForward,max(left.w,right.w));
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
            vec4 renderGaze=electricRenderGaze(eyeGaze);
            float gazeConfidence=renderGaze.w;
            vec2 gaze=renderGaze.xy;
            float gazeForward=renderGaze.z;

            // Each beam remains welded to its own pupil and paired eyes share one stable base
            // gaze. CapCut-style geometry is not perfectly parallel: add only a tiny outward
            // bias along the tracked eye-pair axis so beam separation grows gradually with reach.
            vec2 localGaze=vec2(c*gaze.x+s*gaze.y,-s*gaze.x+c*gaze.y);
            float projected=length(localGaze);
            vec2 dir=projected>.025 ? localGaze/projected : vec2(1.0,0.0);
            bool pairedBeams=uLeftEye.z>.0001 && uRightEye.z>.0001;
            vec2 outwardLocal=vec2(0.0);
            if(pairedBeams) {
                vec2 pairMetric=(uRightEye.xy-uLeftEye.xy)*
                    vec2(1.0,uTexelSize.x/uTexelSize.y);
                float pairLength=length(pairMetric);
                if(pairLength>.0001) {
                    vec2 pairLocal=vec2(
                        c*pairMetric.x+s*pairMetric.y,
                        -s*pairMetric.x+c*pairMetric.y);
                    float eyeSide=eye.x<(uLeftEye.x+uRightEye.x)*.5 ? -1.0 : 1.0;
                    outwardLocal=normalize(pairLocal)*eyeSide;
                    dir=normalize(dir+outwardLocal*.035);
                }
            }
            float along=dot(p,dir);
            float across=p.x*dir.y-p.y*dir.x;
            float cameraFacing=max(
                gazeCameraFacing(gazeForward),
                smoothstep(.86,.97,gazeForward)*(1.0-smoothstep(.28,.58,projected)));
            float confidenceGate=smoothstep(.10,.50,gazeConfidence);
            float directional=(1.0-cameraFacing)*mix(.28,1.0,confidenceGate);
            float forward=max(along,0.0);
            float rayGate=smoothstep(-.08,.10,along);

            // Straight-at-lens gaze keeps two short tubes, with the same very small outward
            // separation used by the long ray so the far endpoints do not look mechanically
            // parallel. The origins remain exactly on the tracked pupils.
            vec2 frontScreenDir=length(gaze)>.025 ? normalize(gaze) : vec2(0.0,-1.0);
            vec2 grazeDir=vec2(
                c*frontScreenDir.x+s*frontScreenDir.y,
                -s*frontScreenDir.x+c*frontScreenDir.y);
            if(pairedBeams && length(outwardLocal)>.001) {
                grazeDir=normalize(grazeDir+outwardLocal*.040);
            }
            float grazeAlong=dot(p,grazeDir);
            float grazeForward=max(grazeAlong,0.0);
            float grazeAcross=p.x*grazeDir.y-p.y*grazeDir.x;
            float grazeWindow=smoothstep(-.08,.08,grazeAlong)*
                (1.0-smoothstep(5.2,8.2,grazeForward));
            float grazeWidth=.108+.074*grazeForward;
            float frontTubeCore=exp(-pow(
                grazeAcross/max(.039+.012*grazeForward,.039),2.0))*
                grazeWindow*cameraFacing;
            float frontTubeBody=exp(-pow(
                grazeAcross/max(grazeWidth,.075),2.25))*
                grazeWindow*cameraFacing;

            // CapCut-reference behavior without copying assets: the beam is not a thin GLSL line.
            // It starts narrow at the pupil, widens into a blown-out volumetric cone, has a
            // white-hot center, warm gold bloom and animated ragged/electric plasma at the edges.
            // Calm procedural motion: one low-frequency FBM layer plus one cheap detail noise.
            // The previous fast secondary filament created occasional "extra two lines" beside the
            // two real eye beams, so it is intentionally removed.
            float coarse=eyeFbm(vec2(forward*.16-t*.16,across*1.42+t*.018));
            float fine=eyeNoise(vec2(forward*.34+t*.07,across*3.15-t*.045));
            float coneWidth=.135+forward*.064;
            float coreWidth=.043+forward*.011;
            float hazeWidth=.315+forward*.118;
            float sway=(coarse-.5)*coneWidth*.055+
                sin(forward*.92-t*.75)*(.0020+.00055*forward);
            float shifted=across-sway;
            float raggedWidth=coneWidth*(.88+.20*fine);

            float beamCore=exp(-pow(shifted/max(coreWidth,.037),2.0));
            float beamBody=exp(-pow(abs(shifted)/max(raggedWidth,.090),2.45));
            float beamHaze=exp(-pow(shifted/max(hazeWidth,.235),2.0))*
                (.68+.32*coarse);
            // Broad edge glow keeps an electric/plasma contour without becoming another line.
            float edgeGlow=max(beamHaze-beamBody*.58,0.0)*(.32+.28*fine);

            float reach=mix(.115,.008,confidenceGate);
            float axial=rayGate*directional;
            beamCore*=axial*exp(-forward*reach);
            beamBody*=axial*exp(-forward*(reach+.004));
            beamHaze*=axial*exp(-forward*(reach+.014));
            edgeGlow*=axial*exp(-forward*(reach+.012));

            // Reuse the already-computed slow noise instead of two extra FBM evaluations. This
            // keeps the smoke subtle, slower than the beam edge, and materially lowers GPU cost.
            float smokeNoise=mix(coarse,eyeNoise(vec2(
                forward*.10-t*.025,shifted*.62+t*.010)),.38);
            float smokeEnvelope=exp(-pow(
                shifted/max(hazeWidth*1.65,.29),2.0))*rayGate*directional*
                exp(-forward*(reach+.027));
            float smoke=smokeEnvelope*
                smoothstep(.30,.72,smokeNoise)*
                (1.0-.48*clamp(beamBody,0.0,1.0));

            float frontSmokeNoise=mix(coarse,fine,.42);
            float frontSmoke=exp(-pow(
                grazeAcross/max(.28+.085*grazeForward,.28),2.0))*
                grazeWindow*cameraFacing*
                smoothstep(.32,.72,frontSmokeNoise);

            // Bright eye socket/root flare visually joins the beam to the actual pupil/eyelid.
            float lidWhite=exp(-dot(p*vec2(.58,2.40),p*vec2(.58,2.40))*1.70);
            float rootFlash=exp(-r*r*2.75);
            float socketBloom=exp(-dot(p*vec2(.42,1.15),p*vec2(.42,1.15))*.50);

            // Looking straight into the camera collapses the projected cone into a large soft
            // lens-facing bloom rather than a fake arbitrary screen-space ray.
            float pulse=.994+.006*sin(t*1.1);
            float localFrontHalo=exp(-r*r*.14)*(1.0-smoothstep(5.8,8.6,r));
            float front=cameraFacing*pulse;

            // Do not "burn" the eye when the light is aimed at the lens. The local emitter stays
            // visible, but most frontal energy moves into the two graze tubes and the frame-space
            // lens flare added below.
            float localEmitterScale=1.0-.68*cameraFacing;
            light+=uEyesA.y*(
                vec3(1.00,.995,.93)*(
                    beamCore*2.35+beamBody*1.30+
                    (lidWhite*1.22+rootFlash*.82)*localEmitterScale+
                    frontTubeCore*2.05+frontTubeBody*.78
                )+
                vec3(1.00,.67,.18)*(
                    beamBody*.34+beamHaze*.80+edgeGlow*.34+
                    socketBloom*.17*localEmitterScale+
                    frontTubeBody*.42+localFrontHalo*.09*front+
                    smoke*.20+frontSmoke*.19
                )+
                vec3(.72,.68,.61)*(smoke*.13+frontSmoke*.12)+
                vec3(1.00,.28,.015)*(edgeGlow*.08+beamHaze*.07)
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
    float electricLensHitScore(vec4 eye,vec4 gaze) {
        if(eye.z<.0001 || gaze.w<.055) return 0.0;
        vec4 resolved=electricResolvedGaze(gaze);
        float projected=length(resolved.xy);
        float front3d=smoothstep(.84,.96,uHeadPose.w)*smoothstep(.82,.96,resolved.z);
        if(front3d<.001) return 0.0;

        // A lens flare is allowed only when the actual eye ray passes through the virtual camera
        // lens. This prevents a fake full-frame flare while both beams visibly point elsewhere.
        vec2 lensUv=eyeSourceUv(vec2(.5,.5));
        vec2 toLens=(lensUv-eye.xy)*faceMetricScale();
        float lensDistance=length(toLens);
        if(lensDistance<.0001) return front3d;
        vec2 aim=projected>.035 ? normalize(resolved.xy) : toLens/lensDistance;
        float along=dot(toLens,aim);
        float miss=abs(toLens.x*aim.y-toLens.y*aim.x);
        float rayForward=smoothstep(-.01,.05,along);
        float rayHit=1.0-smoothstep(.014,.060,miss);
        float nearFront=1.0-smoothstep(.20,.52,projected);
        return front3d*nearFront*rayForward*rayHit;
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

        // The frame-space flare is now geometric, not just "face looks forward". Each eye ray
        // must actually intersect the virtual camera lens. A single hit gives only a very faint
        // response; two aligned hits produce the still-subtle CapCut-like lens graze.
        vec4 effectiveLeft=uLeftGaze.w<.055 ? vec4(uGazePose.xyz,.12) : uLeftGaze;
        vec4 effectiveRight=uRightGaze.w<.055 ? vec4(uGazePose.xyz,.12) : uRightGaze;
        float leftHit=electricLensHitScore(uLeftEye,effectiveLeft)*
            smoothstep(.18,.32,uEyeState.x);
        float rightHit=electricLensHitScore(uRightEye,effectiveRight)*
            smoothstep(.18,.32,uEyeState.y);
        float lensHit=.5*(leftHit+rightHit)*uEyesA.y;
        float pairFront=lensHit*lensHit*.18;
        vec2 lensP=vec2(frameUv.x-.5,(frameUv.y-.5)*(uTexelSize.x/uTexelSize.y));
        float lensNoise=eyeNoise(lensP*2.5+vec2(-uEyeTime*.018,uEyeTime*.010));
        float lensBurn=exp(-dot(lensP*vec2(.96,1.16),lensP*vec2(.96,1.16))*10.5);
        float lensHalo=exp(-dot(lensP,lensP)*4.4)*(.84+.16*lensNoise);
        float lensStreak=exp(-lensP.y*lensP.y*145.0)*exp(-abs(lensP.x)*2.8);
        float lensMist=exp(-dot(lensP*vec2(.82,1.02),lensP*vec2(.82,1.02))*5.6)*
            smoothstep(.34,.76,lensNoise);
        light+=pairFront*(
            vec3(1.00,.995,.92)*(lensBurn*.72+lensStreak*.18)+
            vec3(1.00,.67,.20)*(lensHalo*.16+lensMist*.09)+
            vec3(.72,.69,.64)*lensMist*.05
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
