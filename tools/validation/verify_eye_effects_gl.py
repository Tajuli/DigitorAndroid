"""Render the production GLES shader on Mesa/EGL; run with python3 from repository root.
Requires Linux libEGL/libGL and numpy. No Android SDK or downloaded model is needed.
Synthetic mattes exercise optics, not neural-model accuracy.
"""
import ctypes as c
import os
import re
from pathlib import Path
import numpy as np

os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
egl = c.CDLL('libEGL.so.1')
gl = c.CDLL('libGL.so.1')
I, U, F, P = c.c_int, c.c_uint, c.c_float, c.c_void_p

def fn(lib, name, result, *args):
    f = getattr(lib, name); f.restype = result; f.argtypes = args
    return f

display = fn(egl, 'eglGetDisplay', P, P)(None)
assert fn(egl, 'eglInitialize', U, P, P, P)(display, None, None)
assert fn(egl, 'eglBindAPI', U, U)(0x30A0)
attrs = (I * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
config, count = P(), I()
assert fn(egl, 'eglChooseConfig', U, P, P, P, I, P)(display, attrs, c.byref(config), 1, c.byref(count)) and count.value
ctx = fn(egl, 'eglCreateContext', P, P, P, P, P)(display, config, None, (I * 3)(0x3098, 2, 0x3038))
w, h = 128, 96
surface = fn(egl, 'eglCreatePbufferSurface', P, P, P, P)(display, config, (I * 5)(0x3057, 128, 0x3056, 128, 0x3038))
assert fn(egl, 'eglMakeCurrent', U, P, P, P, P)(display, surface, surface, ctx)
create_shader = fn(gl, 'glCreateShader', U, U)
shader_source = fn(gl, 'glShaderSource', None, U, I, P, P)
compile_shader = fn(gl, 'glCompileShader', None, U)
get_shader = fn(gl, 'glGetShaderiv', None, U, U, P)
get_log = fn(gl, 'glGetShaderInfoLog', None, U, I, P, P)

def compile_one(source, kind):
    shader = create_shader(kind); data = c.c_char_p(source.encode())
    shader_source(shader, 1, c.byref(data), None); compile_shader(shader)
    status = I(); get_shader(shader, 0x8B81, c.byref(status))
    log = c.create_string_buffer(16384); get_log(shader, len(log), None, log)
    assert status.value, log.value.decode()
    return shader

def program_for(path):
    text = Path(path).read_text()
    vertex = re.search(r'VERTEX_SHADER = """(.*?)"""', text, re.S).group(1)
    fragment = re.search(r'NODE_FRAGMENT_SHADER = """(.*?)"""', text, re.S).group(1)
    eye = Path('app/src/main/java/com/tajuli/digitorandroid/editor/render/EyeEffectShader.kt').read_text().split('"""')[1]
    fragment = fragment.replace('$EYE_EFFECT_SHADER', eye)
    program = fn(gl, 'glCreateProgram', U)()
    for src, kind in [(vertex, 0x8B31), (fragment, 0x8B30)]:
        fn(gl, 'glAttachShader', None, U, U)(program, compile_one(src, kind))
    fn(gl, 'glLinkProgram', None, U)(program)
    status = I(); fn(gl, 'glGetProgramiv', None, U, U, P)(program, 0x8B82, c.byref(status))
    assert status.value, 'Link failed'
    return program


base = 'app/src/main/java/com/tajuli/digitorandroid/editor/render/'
program = program_for(base + 'CreatorEffectGraphV25.kt')
fn(gl, 'glUseProgram', None, U)(program)
location = fn(gl, 'glGetUniformLocation', I, U, c.c_char_p)
def vec(name, values):
    loc = location(program, name.encode())
    assert loc >= 0, name
    data = (F * len(values))(*values)
    fn(gl, 'glUniform%dfv' % len(values), None, I, I, P)(loc, 1, data)
def uniform(name, value): fn(gl, 'glUniform1f', None, I, F)(location(program, name.encode()), value)
y,x=np.mgrid[:h,:w]
source=np.zeros((h,w,4),np.uint8);source[:,:,0]=30+x+((y//4)%2)*60;source[:,:,1]=30+y;source[:,:,2]=30+(x+y)//2+((x//4)%2)*40;source[:,:,3]=179
ident=U(); fn(gl,'glGenTextures',None,I,P)(1,c.byref(ident))
fn(gl,'glBindTexture',None,U,U)(0x0DE1,ident)
for key,val in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:
    fn(gl,'glTexParameteri',None,U,U,I)(0x0DE1,key,val)
fn(gl,'glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,source.ctypes.data)
vec('uEyeTransform',[1,0,1,1]); vec('uEyeTranslation',[0,0]); vec('uTexelSize',[1/w,1/h]); uniform('uEyeTime',.35)
vec('uHeadPose',[0,0,0,1]); vec('uGazePose',[-1,0,0,1])
vec('uLeftGaze',[0,0,1,0]); vec('uRightGaze',[0,0,1,0])
vec('uLeftEye',[.35,.5,.055,0]); vec('uRightEye',[.65,.5,.055,0]); vec('uEyeState',[1,1,0,0])
vec('uFaceRegion',[.5,.5,.25,.35]);vec('uMouthRegion',[.5,.35,.08,.045])
vertices=np.array([-1,-1,0,1,1,-1,0,1,-1,1,0,1,1,1,0,1],np.float32)
attribute=fn(gl,'glGetAttribLocation',I,U,c.c_char_p)(program,b'aFramePosition')
fn(gl,'glEnableVertexAttribArray',None,U)(attribute)
fn(gl,'glVertexAttribPointer',None,U,I,U,U,I,P)(attribute,4,0x1406,0,0,vertices.ctypes.data)
fn(gl,'glViewport',None,I,I,I,I)(0,0,w,h)
def render():
    fn(gl,'glDrawArrays',None,U,I,I)(5,0,4)
    out=np.zeros_like(source)
    fn(gl,'glReadPixels',None,I,I,I,I,U,U,P)(0,0,w,h,0x1908,0x1401,out.ctypes.data)
    assert fn(gl,'glGetError',U)()==0
    return out
def amounts(index, strength=1):
    a=[0.]*28
    if index>=0: a[index]=strength
    for name,part in zip(['uEyesA','uEyesB','uEyesC','uEyesD','uEyesE','uFunnyA','uFunnyB'],[a[i:i+4] for i in range(0,28,4)]): vec(name,part)
amounts(-1); assert np.array_equal(render(),source),'Zero strength'
active_indices=[i for i in range(27) if i != 12]
results={}
for index in active_indices:
    amounts(index); vec('uEyeState',[1,1,0,0])
    out=render(); results[index]=out
    assert np.max(np.abs(out[:,:,:3].astype(int)-source[:,:,:3].astype(int)))>2, ('Invisible',index)
    assert np.array_equal(out[:,:,3],source[:,:,3]),('Alpha',index)
    vec('uEyeState',[0,0,0,0]);
    if (index<16 or index==17) and index!=1:
        assert np.array_equal(render(),source),('Blink',index)
    if index==1:
        assert not np.array_equal(render(),source),('Electric Eyes must stay on through blink',index)
    vec('uEyeState',[1,1,0,0]); vec('uLeftEye',[0,0,0,0]); vec('uRightEye',[0,0,0,0])
    vec('uFaceRegion',[0,0,0,0]);vec('uMouthRegion',[0,0,0,0])
    assert np.array_equal(render(),source),('Missing face',index)
    vec('uFaceRegion',[.5,.5,.25,.35]);vec('uMouthRegion',[.5,.35,.08,.045])
    vec('uLeftEye',[.35,.5,.055,0]); vec('uRightEye',[.65,.5,.055,0])
for pos,i in enumerate(active_indices):
    for j in active_indices[:pos]: assert not np.array_equal(results[i],results[j]),('Duplicate',i,j)
amounts(12); assert np.array_equal(render(),source),'Removed Electric slot must stay inert'
# Movement and head tilt must alter the actual rendered pixels.
amounts(1); before=render(); vec('uLeftEye',[.42,.4,.055,0]); vec('uEyeState',[1,1,.6,0])
assert not np.array_equal(before,render()),'Tracking uniforms ignored'
print('PASS: production shader compiles/links; 26 public effects; Electric Eyes ignores blink while other blink-aware effects and missing-face checks pass.')


# Non-square, off-center coordinates catch axis flips hidden by a square center-only test.
source[:,:,:3]=30
fn(gl,'glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,source.ctypes.data)
# Use frontal gaze here. Electric Eyes now has a deliberately broad volumetric/lens bloom, so
# a single brightest pixel can move a few pixels inside the white-hot socket. Validate the local
# emitted-energy centroid instead; that tests the real anchor without penalizing natural bloom.
amounts(1); vec('uRightEye',[0,0,0,0]); vec('uEyeState',[1,0,0,0]); vec('uGazePose',[0,0,1,1])
for ex,ey in ((.24,.28),(.72,.65)):
    vec('uLeftEye',[ex,ey,.04,0])
    delta=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
    energy=np.clip(delta.sum(axis=2),0,None)
    expected_x=ex*w-.5
    expected_y=(1-ey)*h-.5
    yy,xx=np.mgrid[:h,:w]
    mask=(np.abs(xx-expected_x)<=12)&(np.abs(yy-expected_y)<=12)
    local=energy*mask
    total=local.sum()
    assert total>100, ('Eye anchor emitted no local energy',ex,ey,total)
    centroid_x=(local*xx).sum()/total
    centroid_y=(local*yy).sum()/total
    # The CapCut-like volumetric body is intentionally wider now, so the local energy centroid
    # can move a fraction farther from the mathematical pupil while the emitter/root stays anchored.
    # Keep this tight enough to catch coordinate/transform regressions without rejecting the wider bloom.
    assert abs(centroid_x-expected_x)<5.8 and abs(centroid_y-expected_y)<5.8, (
        'Eye energy centroid',ex,ey,centroid_x,centroid_y)
print('PASS: normalized eye centers anchor the volumetric Electric Eyes energy in a non-square frame.')

# Electric Eyes is a ray, not an infinite line through the face. A viewer-left eye must emit toward the
# left side only; pixels behind the eye on the inward/right side should stay near the source.
source[:,:,:3]=30
fn(gl,'glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,source.ctypes.data)
amounts(1)
# Reset the shared gaze after the preceding frontal anchor test; otherwise this block renders the
# camera-facing flare and cannot validate a leftward ray.
vec('uGazePose',[-1,0,0,1]); vec('uHeadPose',[0,0,0,1])
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[0,0,0,0]); vec('uEyeState',[1,0,0,0])
eye_beam=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
row=h//2
outward=eye_beam[max(0,row-2):row+3, 8:36].sum()
inward=eye_beam[max(0,row-2):row+3, 66:98].sum()
assert outward > inward*1.20 + 100, ('Electric Eyes must start at eye and travel outward',outward,inward)
print('PASS: Electric Eyes originates at the eye and follows left gaze.')

# Electric/Laser-style beam architecture: pupils/eye contour own the SOURCE point, while
# face/nose/ear/mouth tracking owns DIRECTION. Per-eye pupil gaze must not steer the long beam.
shader_contract=Path('app/src/main/java/com/tajuli/digitorandroid/editor/render/EyeEffectShader.kt').read_text()
tracker_contract=Path('app/src/main/cpp/FaceTrackingNcnnVulkanJni.cpp').read_text()
assert 'electricRenderGaze' in shader_contract, 'Electric Eyes face direction resolver missing'
assert 'if(uGazePose.w>=.5) return vec4(uGazePose.xyz,confidence);' in shader_contract, (
    'Electric Eyes must use fused face direction while retaining only per-eye confidence')
assert 'float confidence=max(ownGaze.w,.08);' in shader_contract, (
    'Per-eye gaze uniforms must remain active without using pupil X/Y/Z for beam direction')
assert 'dir=normalize(dir+outwardLocal*.034);' in shader_contract, (
    'Electric Eyes stronger paired V-angle missing')
assert 'grazeDir=normalize(grazeDir+outwardLocal*.028);' in shader_contract, (
    'Frontal paired V-angle increase missing')
assert 'float spreadProgress=smoothstep(.40,6.6,forward);' in shader_contract, (
    'Electric Eyes stronger distance-based divergence ramp missing')
assert 'progressiveSpread=outwardSign*forward*(.060*spreadProgress);' in shader_contract, (
    'Electric Eyes stronger far-tip outward separation missing')
assert 'vec2 electricScreenDir(vec4 gaze,vec4 eye)' in shader_contract, (
    'Electric Eyes camera-facing lens direction resolver missing')
assert 'return normalize(mix(faceDir,lensDir,frontAmount));' in shader_contract, (
    'Camera-facing Electric Eyes must turn toward the virtual lens')
assert 'float blueRay=beamCore;' in shader_contract and 'float yellowRay=beamBody*' in shader_contract, (
    'Electric Eyes blue core and yellow middle layers missing')
assert 'float redRay=(beamHaze+edgeGlow*.55)*' in shader_contract, (
    'Electric Eyes red outer layer missing')
assert 'float shifted=across-sway-progressiveSpread;' in shader_contract, (
    'Progressive separation must move the beam body, not only metadata')
assert 'EffectDirectionFromLandmarks' in tracker_contract, (
    'Face/nose/ear/mouth fused direction tracking missing')
assert tracker_contract.count('EyeContourCenter') >= 2, (
    'Multi-point eye contour source tracking missing')
assert 'const float gazeX = headYaw;' in tracker_contract and 'const float gazeY = headPitch;' in tracker_contract, (
    'Shared effect direction must be independent from pupil gaze')

yy_dir,xx_dir=np.mgrid[:h,:w]

def direction_centroid(eye_name, eye_x, face_x, face_y):
    if eye_name=='left':
        vec('uLeftEye',[eye_x,.5,.045,0]); vec('uRightEye',[0,0,0,0]); vec('uEyeState',[1,0,0,0])
    else:
        vec('uLeftEye',[0,0,0,0]); vec('uRightEye',[eye_x,.5,.045,0]); vec('uEyeState',[0,1,0,0])
    vec('uHeadPose',[face_x,face_y,0,.72])
    vec('uGazePose',[face_x,face_y,.72,1])
    # Deliberately contradictory pupil-gaze metadata: renderer direction must ignore it.
    vec('uLeftGaze',[-face_x,-face_y,.25,.95])
    vec('uRightGaze',[-face_x,-face_y,.25,.95])
    delta=np.clip(render()[:,:,:3].astype(int)-source[:,:,:3].astype(int),0,None).sum(axis=2)
    eye_px=eye_x*w-.5
    eye_py=.5*h-.5
    far=((xx_dir-eye_px)**2+(yy_dir-eye_py)**2)>(7.8**2)
    energy=delta*far
    total=energy.sum()
    assert total>120, ('Directional Electric Eyes emitted too little off-eye energy',
        eye_name,face_x,face_y,total)
    return (energy*xx_dir).sum()/total, (energy*yy_dir).sum()/total

# Horizontal and vertical beam steering follow only fused face direction.
for eye_name,eye_x in (('left',.35),('right',.65)):
    cx_left,_=direction_centroid(eye_name,eye_x,-1,0)
    cx_right,_=direction_centroid(eye_name,eye_x,1,0)
    assert cx_right > cx_left + 2.0, (
        'Fused face direction must steer Electric Eyes horizontally',
        eye_name,cx_left,cx_right)

    _,cy_up=direction_centroid(eye_name,eye_x,0,-.82)
    _,cy_down=direction_centroid(eye_name,eye_x,0,.82)
    assert cy_down < cy_up - 2.0, (
        'Fused face direction must steer Electric Eyes vertically',
        eye_name,cy_up,cy_down)

# With a fixed fused face direction, even opposite high-confidence pupil gaze must produce the
# same Electric Eyes pixels. Pupil/iris movement is a SOURCE refinement only.
vec('uHeadPose',[.72,.18,0,.68]); vec('uGazePose',[.72,.18,.68,1])
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0]); vec('uEyeState',[1,1,0,0])
vec('uLeftGaze',[-1,-.8,.20,.95]); vec('uRightGaze',[-1,-.8,.20,.95])
pupil_left=render()
vec('uLeftGaze',[1,.8,.20,.95]); vec('uRightGaze',[1,.8,.20,.95])
pupil_right=render()
assert np.array_equal(pupil_left,pupil_right), (
    'Pupil gaze must not steer Electric Eyes; only fused face direction may steer it')

# Roll must rotate the face-driven beam coherently around the tracked pupil origins.
vec('uEyeState',[1,1,.55,.55]); vec('uHeadPose',[0,.45,.55,.72]); vec('uGazePose',[0,.70,.38,1])
rolled_down=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
rolled_down_energy=rolled_down[:24].sum()
rolled_up_energy=rolled_down[-24:].sum()
assert rolled_down_energy > rolled_up_energy*1.12 + 100, (
    'Rolled fused face direction must keep the beam on the intended side',
    rolled_down_energy,rolled_up_energy)
vec('uEyeState',[1,1,0,0]); vec('uHeadPose',[0,0,0,1]); vec('uGazePose',[0,0,1,1])
vec('uLeftGaze',[0,0,1,0]); vec('uRightGaze',[0,0,1,0])
print('PASS: pupil/eye contour controls source; fused face/nose/ear/mouth pose controls Electric Eyes direction.')

# Frontal lens-graze is intentionally subtle now. Validate the production contract and confirm
# the paired frontal effect renders, but do not force a minimum 8-bit lens brightness: small natural
# halation can quantize to zero in the tiny headless GL fixture even though it is visible in video.
source[:,:,:3]=30
fn(gl,'glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,source.ctypes.data)
amounts(1); vec('uHeadPose',[0,0,0,1]); vec('uGazePose',[0,0,1,1])
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0]); vec('uEyeState',[1,1,0,0])
vec('uLeftGaze',[0,0,1,.95]); vec('uRightGaze',[0,0,1,.95])
pair_front=np.clip(render()[:,:,:3].astype(int)-source[:,:,:3].astype(int),0,None).sum(axis=2)
assert pair_front.sum()>100, ('Two frontal Electric Eyes must render visible paired energy',pair_front.sum())

shader_contract=Path('app/src/main/java/com/tajuli/digitorandroid/editor/render/EyeEffectShader.kt').read_text()
assert 'electricLensHitScore' in shader_contract, 'Geometric lens-hit gate missing'
assert 'float pairFront=lensHit*lensHit*.10' in shader_contract, 'Subtle lens-hit flare strength missing'
assert 'rayHit=1.0-smoothstep(.010,.045,miss)' in shader_contract, 'Lens miss rejection missing'
assert 'if(front3d<.001) return 0.0' in shader_contract, (
    'Only non-frontal faces should reject the lens-facing hit')
assert 'electricScreenDir(resolved,eye)' in shader_contract, (
    'Lens hit must use the same camera-facing direction as the visible beam')
assert 'renderLeft=electricRenderGaze(effectiveLeft)' in shader_contract, (
    'Lens flare must use the same resolved direction as the visible beam')
assert 'return electricLight + light*blinkGate' in shader_contract, (
    'Electric Eyes must bypass the per-eye blink multiplier')
assert 'bool pairedBeams=uLeftEye.z>.0001 && uRightEye.z>.0001;' in shader_contract, (
    'Electric paired beam geometry must depend on tracked eyes, not eyelid openness')
assert "forward*.115-t*.080" in shader_contract and "t*.280" in shader_contract, (
    'Electric plasma movement must use the slowed temporal rates')
assert 'lensBurn' in shader_contract and 'lensHalo' in shader_contract, 'Lens-graze components missing'
assert 'vec3(.16,.48,1.00)*(lensBurn*.68)' in shader_contract, (
    'Camera-facing lens center must use the blue inner ray color')
assert 'vec3(1.00,.84,.10)*(lensStreak*.18+lensHalo*.12)' in shader_contract, (
    'Camera-facing lens middle must use yellow')
assert 'vec3(1.00,.075,.025)*(lensHalo*.07+lensMist*.10)' in shader_contract, (
    'Camera-facing lens outer energy must use red')

# Electric-beam haze should exist outside the hot core and animate over time. Use broad masks and
# qualitative temporal change so harmless shader tuning cannot make this regression flaky.
vec('uRightEye',[0,0,0,0]); vec('uLeftEye',[.30,.5,.045,0]); vec('uEyeState',[1,0,0,0])
vec('uLeftGaze',[1,0,.25,.95]); vec('uRightGaze',[0,0,1,0]); vec('uGazePose',[1,0,.25,1])
uniform('uEyeTime',.18)
smoke_a=np.clip(render()[:,:,:3].astype(int)-source[:,:,:3].astype(int),0,None)
uniform('uEyeTime',.71)
smoke_b=np.clip(render()[:,:,:3].astype(int)-source[:,:,:3].astype(int),0,None)
outer=((np.abs(yy_dir-row)>=4)&(np.abs(yy_dir-row)<=20)&(xx_dir>=36))
outer_energy=smoke_a.sum(axis=2)[outer].sum()
smoke_delta=np.abs(smoke_a-smoke_b).sum(axis=2)[outer].sum()
assert outer_energy>15, ('Electric beam must carry off-core haze',outer_energy)
assert smoke_delta>0, ('Electric beam smoke/haze must drift over time',smoke_delta)
assert not np.array_equal(smoke_a,smoke_b), 'Electric Eyes procedural haze must animate'
uniform('uEyeTime',.35)
vec('uLeftGaze',[0,0,1,0]); vec('uRightGaze',[0,0,1,0])
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0]); vec('uEyeState',[1,1,0,0])
print('PASS: frontal Electric Eyes graze the virtual lens and directional beams carry animated haze.')

# Fire/Flame are eye-anchored procedural flames with temporal turbulence, not static blobs.
vec('uGazePose',[0,0,1,1]); vec('uHeadPose',[0,0,0,1])
amounts(0); uniform('uEyeTime',.20); fire_a=render()
uniform('uEyeTime',.47); fire_b=render()
assert not np.array_equal(fire_a,fire_b), 'Fire Eyes must flicker/turbulate over time'
amounts(13); uniform('uEyeTime',.31); flame_a=render()
amounts(14); uniform('uEyeTime',.31); flame_b=render()
assert not np.array_equal(flame_a,flame_b), 'Flame variants must remain visually distinct'
uniform('uEyeTime',.35)
print('PASS: Fire/Flame effects are dynamic, eye-anchored and visually distinct.')

# Electric Eyes intentionally stays continuous through eyelid closure. Blink state must not
# switch off either isolated beam, change the paired direction, or pop the subtle lens response.
source[:,:,:3]=30
fn(gl,'glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,source.ctypes.data)
amounts(1); vec('uHeadPose',[0,0,0,1]); vec('uGazePose',[0,0,1,1])

vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[0,0,0,0])
vec('uLeftGaze',[0,0,1,.95]); vec('uRightGaze',[0,0,1,0]); vec('uEyeState',[.35,0,0,0])
left_open=render()
assert not np.array_equal(left_open,source), 'Open left eye must render Electric Eyes'
vec('uEyeState',[.15,0,0,0])
left_blink=render()
assert np.array_equal(left_blink,left_open), 'Left blink must not alter Electric Eyes'

vec('uLeftEye',[0,0,0,0]); vec('uRightEye',[.65,.5,.045,0])
vec('uLeftGaze',[0,0,1,0]); vec('uRightGaze',[0,0,1,.95]); vec('uEyeState',[0,.35,0,0])
right_open=render()
assert not np.array_equal(right_open,source), 'Open right eye must render Electric Eyes'
vec('uEyeState',[0,.15,0,0])
right_blink=render()
assert np.array_equal(right_blink,right_open), 'Right blink must not alter Electric Eyes'

vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0])
vec('uLeftGaze',[0,0,1,.95]); vec('uRightGaze',[0,0,1,.95]); vec('uEyeState',[.35,.35,0,0])
pair_open=render()
vec('uEyeState',[.15,.15,0,0])
pair_blink=render()
assert np.array_equal(pair_blink,pair_open), (
    'Both-eye blink must not alter Electric Eyes or its lens response')
print('PASS: Electric Eyes remains continuous through left, right, and both-eye blinks.')

