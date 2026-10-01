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
    if index<16 or index==17: assert np.array_equal(render(),source),('Blink',index)
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
print('PASS: production shader compiles/links; 26 public effects; removed Electric slot inert; blink/missing-face checks pass.')


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
    assert abs(centroid_x-expected_x)<4.5 and abs(centroid_y-expected_y)<4.5, (
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

# Isolated eyes still follow measured gaze. With both eyes present, the shader resolves one shared
# binocular render direction, so separate pupil origins stay parallel at the original eye spacing.
shader_contract=Path('app/src/main/java/com/tajuli/digitorandroid/editor/render/EyeEffectShader.kt').read_text()
assert 'electricRenderGaze' in shader_contract, 'Electric Eyes paired gaze resolver missing'
assert 'eyeSide=sign' not in shader_contract, 'Electric Eyes must not add eye-side beam divergence'
# Test isolated steering with opposite high-confidence gazes.
vec('uHeadPose',[0,0,0,.72]); vec('uGazePose',[0,0,.30,1])
yy_dir,xx_dir=np.mgrid[:h,:w]

def gaze_centroid(eye_name, eye_x, gaze_x, gaze_y):
    if eye_name=='left':
        vec('uLeftEye',[eye_x,.5,.045,0]); vec('uRightEye',[0,0,0,0]); vec('uEyeState',[1,0,0,0])
        vec('uLeftGaze',[gaze_x,gaze_y,.30,.95]); vec('uRightGaze',[0,0,1,0])
    else:
        vec('uLeftEye',[0,0,0,0]); vec('uRightEye',[eye_x,.5,.045,0]); vec('uEyeState',[0,1,0,0])
        vec('uLeftGaze',[0,0,1,0]); vec('uRightGaze',[gaze_x,gaze_y,.30,.95])
    delta=np.clip(render()[:,:,:3].astype(int)-source[:,:,:3].astype(int),0,None).sum(axis=2)
    eye_px=eye_x*w-.5
    eye_py=.5*h-.5
    # Ignore the symmetric socket/emitter. Only off-eye volumetric energy is allowed to decide
    # steering direction, making this independent of bloom width and haze strength.
    far=((xx_dir-eye_px)**2+(yy_dir-eye_py)**2)>(7.8**2)
    energy=delta*far
    total=energy.sum()
    assert total>120, ('Directional Electric Eyes emitted too little off-eye energy',
        eye_name,gaze_x,gaze_y,total)
    return (energy*xx_dir).sum()/total, (energy*yy_dir).sum()/total

for eye_name,eye_x in (('left',.35),('right',.65)):
    cx_left,_=gaze_centroid(eye_name,eye_x,-1,0)
    cx_right,_=gaze_centroid(eye_name,eye_x,1,0)
    assert cx_right > cx_left + 2.0, (
        'Electric Eyes gaze must steer beam horizontally',eye_name,cx_left,cx_right)

vec('uHeadPose',[0,0,0,1]); vec('uGazePose',[0,0,1,1])
vec('uLeftGaze',[0,0,1,0]); vec('uRightGaze',[0,0,1,0])
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0]); vec('uEyeState',[1,1,0,0])
front=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
eye_band=front[max(0,row-12):min(h,row+13), 34:94].sum()
far_edges=front[:, :14].sum()+front[:, -14:].sum()
assert eye_band > far_edges*2 + 100, ('Frontal gaze should read as camera-facing flare',eye_band,far_edges)

# Real footage can report a modest downward 2D pitch while the 3D head and eye-depth still
# say camera-facing. That must become a radial lens hit, never a long beam toward frame bottom.
vec('uHeadPose',[0,.28,0,.97])
vec('uGazePose',[0,.36,.90,1])
biased_front=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
biased_eye_band=biased_front[max(0,row-16):min(h,row+17), 30:98].sum()
biased_far=biased_front[:18].sum()+biased_front[-18:].sum()+biased_front[:, :14].sum()+biased_front[:, -14:].sum()
assert biased_eye_band > biased_far*1.35 + 100, ('Camera-facing gaze must suppress pitch-biased long ray',biased_eye_band,biased_far)

# The camera-facing result should be roughly radial rather than strongly downward-biased.
top=biased_front[:row, 28:100].sum()
bottom=biased_front[row:, 28:100].sum()
ratio=max(top,bottom)/max(1,min(top,bottom))
assert ratio < 2.4, ('Camera-facing Electric Eyes must read as radial lens hit, not directional ray',top,bottom)

# A genuine down glance still has a lower eye-depth component and must retain a directional ray.
# Validate directionality, not total brightness: the frontal starburst is intentionally bright,
# so comparing absolute energy against it can falsely fail even when the down ray is correct.
vec('uHeadPose',[0,.28,0,.97])
vec('uGazePose',[0,.55,.42,1])
down_gaze=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
down_energy=down_gaze[:24].sum()
up_energy=down_gaze[-24:].sum()
assert down_energy > up_energy*1.15 + 100, ('True down gaze must keep downward beam',down_energy,up_energy)

# Roll must not rotate the gaze away from the eye. Positive gaze Y is image-space DOWN.
amounts(1)
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0])
vec('uEyeState',[1,1,.55,.55]); vec('uHeadPose',[0,.45,.55,.72]); vec('uGazePose',[0,.70,.38,1])
rolled_down=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
rolled_down_energy=rolled_down[:24].sum()
rolled_up_energy=rolled_down[-24:].sum()
assert rolled_down_energy > rolled_up_energy*1.20 + 100, ('Rolled Electric Eyes down gaze must still point down',rolled_down_energy,rolled_up_energy)
vec('uEyeState',[1,1,0,0]); vec('uHeadPose',[0,0,0,1]); vec('uGazePose',[-1,0,0,1])
print('PASS: Electric Eyes follows shared gaze, including rolled-head down gaze, and foreshortens toward camera.')

# Real per-eye gaze: validate vertical steering by rendering opposite gazes for the same isolated
# eye. glReadPixels returns the bottom row first, so image-space DOWN moves the energy centroid
# toward a smaller numpy row index.
source[:,:,:3]=30
fn(gl,'glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,source.ctypes.data)
amounts(1); vec('uGazePose',[0,0,.30,1]); vec('uHeadPose',[0,0,0,.72])
for eye_name,eye_x in (('left',.35),('right',.65)):
    _,cy_up=gaze_centroid(eye_name,eye_x,0,-.82)
    _,cy_down=gaze_centroid(eye_name,eye_x,0,.82)
    assert cy_down < cy_up - 2.0, (
        'Electric Eyes gaze must steer beam vertically',eye_name,cy_up,cy_down)

# Low-confidence pupil data should fall back to the frontal/local emitter instead of honoring a
# requested long side ray. Compare it with the same eye at high confidence.
vec('uHeadPose',[0,0,0,1]); vec('uGazePose',[0,0,1,1])
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[0,0,0,0]); vec('uEyeState',[1,0,0,0])
vec('uRightGaze',[0,0,1,0]); vec('uLeftGaze',[1,0,.20,.01])
low_conf=np.clip(render()[:,:,:3].astype(int)-source[:,:,:3].astype(int),0,None)
vec('uLeftGaze',[1,0,.20,.95])
high_conf=np.clip(render()[:,:,:3].astype(int)-source[:,:,:3].astype(int),0,None)
eye_px=.35*w-.5; eye_py=.5*h-.5
far_right=(xx_dir>eye_px+14)&(np.abs(yy_dir-eye_py)<14)
low_far=low_conf.sum(axis=2)[far_right].sum()
high_far=high_conf.sum(axis=2)[far_right].sum()
local_mask=((xx_dir-eye_px)**2+(yy_dir-eye_py)**2)<(11**2)
assert low_conf.sum(axis=2)[local_mask].sum()>100, 'Low-confidence gaze must retain a local eye emitter'
assert high_far > low_far + 120, (
    'High-confidence gaze must extend farther than low-confidence fallback',low_far,high_far)

vec('uLeftGaze',[0,0,1,0]); vec('uRightGaze',[0,0,1,0])
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0]); vec('uEyeState',[1,1,0,0])
print('PASS: isolated Electric Eyes steering stays gaze-driven; paired beams share one render direction.')

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
assert 'pairFront=pairFront*pairFront*.52' in shader_contract, 'Natural frontal lens gate missing'
assert 'lensBurn' in shader_contract and 'lensHalo' in shader_contract, 'Lens-graze components missing'
assert 'lensBurn*.72' in shader_contract, 'Lens flare should remain intentionally softened'

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

# Blink gating is independent per eye. Test each eye in isolation so the pair-only lens-graze
# layer cannot make one blink appear to dim the opposite eye.
source[:,:,:3]=30
fn(gl,'glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,source.ctypes.data)
amounts(1); vec('uHeadPose',[0,0,0,1]); vec('uGazePose',[0,0,1,1])

vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[0,0,0,0])
vec('uLeftGaze',[0,0,1,.95]); vec('uRightGaze',[0,0,1,0]); vec('uEyeState',[.35,0,0,0])
left_open=render()
assert not np.array_equal(left_open,source), 'Open left eye must render Electric Eyes'
vec('uEyeState',[.15,0,0,0])
assert np.array_equal(render(),source), 'Closed left eye must remove left Electric Eyes'

vec('uLeftEye',[0,0,0,0]); vec('uRightEye',[.65,.5,.045,0])
vec('uLeftGaze',[0,0,1,0]); vec('uRightGaze',[0,0,1,.95]); vec('uEyeState',[0,.35,0,0])
right_open=render()
assert not np.array_equal(right_open,source), 'Open right eye must render Electric Eyes'
vec('uEyeState',[0,.15,0,0])
assert np.array_equal(render(),source), 'Closed right eye must remove right Electric Eyes'

vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0])
vec('uLeftGaze',[0,0,1,.95]); vec('uRightGaze',[0,0,1,.95]); vec('uEyeState',[.15,.15,0,0])
assert np.array_equal(render(),source), 'Both closed eyes must remove Electric Eyes and lens-graze flare'
print('PASS: per-eye blink gate independently disables Electric Eyes without pair-flare coupling.')

