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
        // Origin and direction are intentionally separate. Per-eye/pupil X/Y/Z never steer the
        // beam; uGazePose is produced by fused face/nose/ear/mouth 3D tracking. Keep only the
        // per-eye confidence metadata so the uniforms remain part of the runtime GL contract and
        // confidence can soften energy/reach without changing the beam angle.
        float confidence=max(ownGaze.w,.08);
        if(uGazePose.w>=.5) return vec4(uGazePose.xyz,confidence);
        return vec4(uHeadPose.xy,uHeadPose.w,confidence);
    }
    vec2 electricLensDirection(vec4 eye) {
        // Source-space position of the virtual camera/lens center. This is the same transform used
        // later by the frame-space lens-hit test, kept here so exact frontal faces never fall back
        // to an arbitrary screen-up beam.
        vec2 p=-uEyeTranslation;
        vec2 lensUv=vec2(
            uEyeTransform.x*p.x+uEyeTransform.y*p.y,
            -uEyeTransform.y*p.x+uEyeTransform.x*p.y
        )/uEyeTransform.zw*.5+.5;
        vec2 toLens=(lensUv-eye.xy)*vec2(1.0,uTexelSize.x/uTexelSize.y);
        return length(toLens)>.0001 ? normalize(toLens) : vec2(0.0,-1.0);
    }
    vec2 electricBeamScreenDir(vec4 gaze) {
        vec4 resolved=electricResolvedGaze(gaze);
        float projected=length(resolved.xy);
        // Both visible long beams MUST share the same fused face projection. Never aim each eye
        // independently at the lens center: that makes the left ray point inward/right and the
        // right ray inward/left, producing the crossed-X seen in real footage. Exact frontal pose
        // has no meaningful 2D long-ray projection; its camera-facing energy is rendered separately.
        return projected>.045 ? normalize(resolved.xy) : vec2(0.0,-1.0);
    }
    vec2 electricLensAim(vec4 gaze,vec4 eye) {
        vec4 resolved=electricResolvedGaze(gaze);
        float projected=length(resolved.xy);
        vec2 lensDir=electricLensDirection(eye);
        vec2 faceDir=projected>.045 ? normalize(resolved.xy) : lensDir;
        float frontAmount=smoothstep(.84,.96,uHeadPose.w)*
            smoothstep(.82,.96,resolved.z)*
            (1.0-smoothstep(.08,.30,projected));
        return normalize(mix(faceDir,lensDir,frontAmount));
    }
    vec3 eyeLight(vec2 uv, vec4 eye, float openness, float roll, vec4 eyeGaze) {
        // Most eye effects remain blink-aware, but Electric Eyes is intentionally continuous:
        // eyelid closure must not switch the electric beam off or change its direction.
        float blinkGate=smoothstep(.18,.30,openness);
        if (eye.z < .0001) return vec3(0);
        vec2 d=(vec2(uv.x,1.0-uv.y)-eye.xy)*vec2(1.0,uTexelSize.x/uTexelSize.y);
        float c=cos(roll),s=sin(roll);
        vec2 p=vec2(c*d.x+s*d.y,-s*d.x+c*d.y)/eye.z;
        float r=length(p), a=atan(p.y,p.x), t=uEyeTime;
        float core=exp(-dot(p*vec2(1.1,2.6),p*vec2(1.1,2.6))*2.0);
        float halo=exp(-r*r*.75);
        float ring=exp(-pow((r-.8)*12.0,2.0));
        vec3 light=vec3(0);
        vec3 electricLight=vec3(0);
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
            vec2 screenDir=electricBeamScreenDir(renderGaze);
            vec2 localGaze=vec2(
                c*screenDir.x+s*screenDir.y,
                -s*screenDir.x+c*screenDir.y);
            float projected=length(gaze);
            vec2 dir=normalize(localGaze);
            // V-divergence is a screen-space cue for off-axis beams. When the fused 3D pose is
            // aimed straight into the camera the two rays converge on the lens, so running the
            // paired outward math is both physically wrong and can collapse the frontal fixture.
            float exactCameraFront=gazeCameraFacing(gazeForward)*
                (1.0-smoothstep(.08,.20,projected));
            bool pairedBeams=uLeftEye.z>.0001 && uRightEye.z>.0001 &&
                exactCameraFront<.92;
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
                    // Keep the roots nearly parallel. Most separation is added progressively
                    // farther down the beam so the tips drift outward without a strong V-angle.
                    dir=normalize(dir+outwardLocal*.034);
                }
            }
            float along=dot(p,dir);
            float across=p.x*dir.y-p.y*dir.x;
            float cameraFacing=max(
                gazeCameraFacing(gazeForward),
                smoothstep(.86,.97,gazeForward)*(1.0-smoothstep(.28,.58,projected)));
            float confidenceGate=smoothstep(.10,.50,gazeConfidence);
            // Camera-facing no longer kills the long beam. The reference keeps a large visible
            // plasma column even when the 3D face/gaze estimate is close to frontal.
            float directional=mix(.84,1.0,confidenceGate)*(1.0-.08*cameraFacing);
            float forward=max(along,0.0);
            float rayGate=smoothstep(-.08,.10,along);

            // Straight-at-lens gaze keeps two short tubes, with the same very small outward
            // separation used by the long ray so the far endpoints do not look mechanically
            // parallel. The origins remain exactly on the tracked pupils.
            vec2 frontScreenDir=screenDir;
            vec2 grazeDir=vec2(
                c*frontScreenDir.x+s*frontScreenDir.y,
                -s*frontScreenDir.x+c*frontScreenDir.y);
            if(pairedBeams && length(outwardLocal)>.001) {
                grazeDir=normalize(grazeDir+outwardLocal*.028);
            }
            float grazeAlong=dot(p,grazeDir);
            float grazeForward=max(grazeAlong,0.0);
            float grazeAcross=p.x*grazeDir.y-p.y*grazeDir.x;
            float grazeWindow=smoothstep(-.08,.08,grazeAlong)*
                (1.0-smoothstep(5.2,8.2,grazeForward));
            float grazeWidth=.22+.105*grazeForward;
            float frontTubeCore=exp(-pow(
                grazeAcross/max(.082+.020*grazeForward,.082),2.0))*
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
            // Slow, heavy plasma motion. The previous time rates made the beam jitter/flow too
            // quickly compared with the reference, so all temporal advection is deliberately low.
            float coarse=eyeFbm(vec2(forward*.115-t*.080,across*.78+t*.010));
            float fine=eyeNoise(vec2(forward*.42-t*.110,across*3.65+t*.021));
            float torn=eyeNoise(vec2(forward*.255+t*.055,across*1.92-t*.027));

            // Reference-matched scale: a bright ~eye-width root rapidly grows into a much wider,
            // blown-out beam. Digitor's previous values were still a thin laser line.
            float coneWidth=.34+forward*.095;
            float coreWidth=.105+forward*.026;
            float hazeWidth=.74+forward*.175;
            float sway=(coarse-.5)*(.070+.018*forward)+
                sin(forward*.58-t*.280)*(.007+.0014*forward);

            // Progressive true-V separation. Since both eyes now share the same fused base ray,
            // outwardLocal is guaranteed to point away from the pair midpoint. Project that
            // OUTWARD vector onto the beam-normal once, keep only its sign, and grow separation
            // monotonically with forward distance. The two centerlines can no longer converge or
            // cross as they get longer.
            float progressiveSpread=0.0;
            if(pairedBeams && length(outwardLocal)>.001) {
                vec2 beamNormal=vec2(dir.y,-dir.x);
                float outwardAcross=dot(outwardLocal,beamNormal);
                float outwardSign=outwardAcross<0.0 ? -1.0 : 1.0;
                float spreadProgress=smoothstep(.35,6.2,forward);
                float spreadAmount=forward*(.060*spreadProgress);
                progressiveSpread=outwardSign*spreadAmount;
            }
            float shifted=across-sway-progressiveSpread;
            float raggedWidth=coneWidth*(.72+.36*coarse+.18*fine);
            raggedWidth*=mix(.86,1.18,smoothstep(.30,.76,torn));

            float beamCore=exp(-pow(shifted/max(coreWidth,.090),2.0));
            float beamBody=exp(-pow(abs(shifted)/max(raggedWidth,.22),2.05))*
                (.84+.34*fine);
            float beamHaze=exp(-pow(shifted/max(hazeWidth,.52),2.0))*
                (.62+.45*coarse);
            // Ragged hot rim and soft smoke create the torn plasma edge visible in the reference
            // without adding separate fake filament lines.
            float rimDistance=abs(abs(shifted)-raggedWidth*.78);
            float edgeGlow=exp(-pow(rimDistance/max(raggedWidth*.30,.085),2.0))*
                (.30+.70*torn)*(.55+.45*coarse);

            float reach=mix(.032,.0055,confidenceGate);
            float axial=rayGate*directional;
            beamCore*=axial*exp(-forward*reach);
            beamBody*=axial*exp(-forward*(reach+.004));
            beamHaze*=axial*exp(-forward*(reach+.014));
            edgeGlow*=axial*exp(-forward*(reach+.012));

            // Reuse the already-computed slow noise instead of two extra FBM evaluations. This
            // keeps the smoke subtle, slower than the beam edge, and materially lowers GPU cost.
            float smokeNoise=mix(coarse,eyeNoise(vec2(
                forward*.082-t*.014,shifted*.48+t*.005)),.42);
            float smokeEnvelope=exp(-pow(
                shifted/max(hazeWidth*1.72,.88),2.0))*rayGate*directional*
                exp(-forward*(reach+.018));
            float smoke=smokeEnvelope*
                smoothstep(.28,.70,smokeNoise)*
                (1.0-.34*clamp(beamBody,0.0,1.0));

            float frontSmokeNoise=mix(coarse,fine,.42);
            float frontSmoke=exp(-pow(
                grazeAcross/max(.56+.15*grazeForward,.56),2.0))*
                grazeWindow*cameraFacing*
                smoothstep(.32,.72,frontSmokeNoise);

            // Bright eye socket/root flare visually joins the beam to the actual pupil/eyelid.
            float lidWhite=exp(-dot(p*vec2(.58,2.40),p*vec2(.58,2.40))*1.70);
            float rootFlash=exp(-r*r*2.75);
            float socketBloom=exp(-dot(p*vec2(.42,1.15),p*vec2(.42,1.15))*.50);

            // Looking straight into the camera collapses the projected cone into a large soft
            // lens-facing bloom rather than a fake arbitrary screen-space ray.
            float pulse=.996+.004*sin(t*.40);
            float localFrontHalo=exp(-r*r*.14)*(1.0-smoothstep(5.8,8.6,r));
            float front=cameraFacing*pulse;

            // Three nested beam colors: narrow blue ray in the center, yellow energy around it,
            // then a red turbulent outer ray/haze. Subtract the inner masks from the wider layers
            // so the bands remain visibly distinct instead of simply adding to white.
            float blueRay=beamCore;
            float yellowRay=beamBody*
                pow(max(0.0,1.0-clamp(beamCore,0.0,1.0)),.55);
            float redRay=(beamHaze+edgeGlow*.55)*
                pow(max(0.0,1.0-clamp(beamBody,0.0,1.0)),.65)*
                pow(max(0.0,1.0-clamp(beamCore,0.0,1.0)),.30);
            float frontBlue=frontTubeCore;
            float frontYellow=frontTubeBody*
                pow(max(0.0,1.0-clamp(frontTubeCore,0.0,1.0)),.55);
            // A beam aimed straight into the camera has almost no 2D projected length. Preserve
            // that physical camera-facing interpretation with a layered pupil/socket emitter
            // instead of allowing the effect to disappear when the projected ray collapses.
            float frontalEmitter=cameraFacing*exp(-r*r*1.65);
            float frontalRing=cameraFacing*exp(-pow((r-.72)*3.2,2.0));
            float localEmitterScale=.88+.12*(1.0-cameraFacing);
            electricLight+=uEyesA.y*(
                vec3(.16,.48,1.00)*(
                    blueRay*4.65+frontBlue*.86+
                    rootFlash*.78*localEmitterScale+
                    frontalEmitter*2.35
                )+
                vec3(1.00,.84,.10)*(
                    yellowRay*3.10+frontYellow*.92+
                    lidWhite*.36*localEmitterScale+
                    socketBloom*.18*localEmitterScale+
                    frontalEmitter*.72+frontalRing*.28
                )+
                vec3(1.00,.075,.025)*(
                    redRay*2.35+edgeGlow*.42+
                    localFrontHalo*.055*front+
                    smoke*.42+frontSmoke*.12+
                    frontalRing*.42
                )
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
        return electricLight + light*blinkGate;
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
    float electricLensHitScore(vec4 eye,vec4 renderGaze) {
        if(eye.z<.0001 || renderGaze.w<.055) return 0.0;
        vec4 resolved=electricResolvedGaze(renderGaze);
        float projected=length(resolved.xy);
        float front3d=smoothstep(.84,.96,uHeadPose.w)*smoothstep(.82,.96,resolved.z);
        if(front3d<.001) return 0.0;

        // Use exactly the same visible screen ray as the beam renderer. Never invent a lens hit
        // from "front-facing" metadata when the drawn beam is clearly travelling elsewhere.
        vec2 lensUv=eyeSourceUv(vec2(.5,.5));
        vec2 toLens=(lensUv-eye.xy)*faceMetricScale();
        float lensDistance=length(toLens);
        if(lensDistance<.0001) return front3d;
        vec2 aim=electricLensAim(resolved,eye);
        float along=dot(toLens,aim);
        float miss=abs(toLens.x*aim.y-toLens.y*aim.x);
        float rayForward=smoothstep(-.008,.045,along);
        float rayHit=1.0-smoothstep(.010,.045,miss);
        return front3d*rayForward*rayHit;
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
        vec4 renderLeft=electricRenderGaze(effectiveLeft);
        vec4 renderRight=electricRenderGaze(effectiveRight);
        // Lens-hit energy follows tracked rays, not eyelid openness. Electric Eyes remains on
        // through a blink, so its subtle lens response must not pop off either.
        float leftHit=electricLensHitScore(uLeftEye,renderLeft);
        float rightHit=electricLensHitScore(uRightEye,renderRight);
        float lensHit=.5*(leftHit+rightHit)*uEyesA.y;
        // Exact frontal gaze can have negligible screen-space ray length even though both eyes are
        // physically aimed at the camera. Use the fused 3D front score as a floor for lens energy.
        float fusedFront=min(electricFrontScore(renderLeft),electricFrontScore(renderRight))*uEyesA.y;
        float pairFront=max(lensHit*lensHit*.10,fusedFront*fusedFront*.10);

        // Exact camera-facing rays can collapse to almost zero screen-space length. Render a
        // tracked-eye emitter directly in source space so the effect remains visible at both
        // pupils while the frame-space lens flare represents the beam travelling toward camera.
        vec2 sourcePoint=vec2(uv.x,1.0-uv.y);
        vec2 metric=faceMetricScale();
        float leftFrontR=uLeftEye.z>.0001 ?
            length((sourcePoint-uLeftEye.xy)*metric)/max(uLeftEye.z,.0001) : 99.0;
        float rightFrontR=uRightEye.z>.0001 ?
            length((sourcePoint-uRightEye.xy)*metric)/max(uRightEye.z,.0001) : 99.0;
        float frontR=min(leftFrontR,rightFrontR);
        float frontBlueEye=exp(-frontR*frontR*3.10);
        float frontYellowEye=exp(-pow((frontR-.58)*2.65,2.0))*
            (1.0-smoothstep(.0,.42,frontBlueEye));
        float frontRedEye=exp(-pow((frontR-.96)*1.90,2.0))*
            (1.0-smoothstep(.0,.38,frontYellowEye));
        light+=fusedFront*(
            vec3(.16,.48,1.00)*frontBlueEye*2.10+
            vec3(1.00,.84,.10)*frontYellowEye*.82+
            vec3(1.00,.075,.025)*frontRedEye*.54
        );

        vec2 lensP=vec2(frameUv.x-.5,(frameUv.y-.5)*(uTexelSize.x/uTexelSize.y));
        float lensNoise=eyeNoise(lensP*2.5+vec2(-uEyeTime*.018,uEyeTime*.010));
        float lensBurn=exp(-dot(lensP*vec2(.96,1.16),lensP*vec2(.96,1.16))*10.5);
        float lensHalo=exp(-dot(lensP,lensP)*4.4)*(.84+.16*lensNoise);
        float lensStreak=exp(-lensP.y*lensP.y*145.0)*exp(-abs(lensP.x)*2.8);
        float lensMist=exp(-dot(lensP*vec2(.82,1.02),lensP*vec2(.82,1.02))*5.6)*
            smoothstep(.34,.76,lensNoise);
        light+=pairFront*(
            vec3(.16,.48,1.00)*(lensBurn*.68)+
            vec3(1.00,.84,.10)*(lensStreak*.18+lensHalo*.12)+
            vec3(1.00,.075,.025)*(lensHalo*.07+lensMist*.10)
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
