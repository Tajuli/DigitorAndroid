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
# Use frontal gaze here. Directional Eye Beam intentionally has its brightest pixels along the
# outgoing ray, so "brightest pixel == eye center" is only a valid anchor check in foreshortened mode.
amounts(1); vec('uRightEye',[0,0,0,0]); vec('uEyeState',[1,0,0,0]); vec('uGazePose',[0,0,1,1])
for ex,ey in ((.24,.28),(.72,.65)):
    vec('uLeftEye',[ex,ey,.04,0])
    delta=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
    py,px=np.unravel_index(np.argmax(delta.sum(axis=2)),(h,w))
    assert abs(px-(ex*w-.5))<2 and abs(py-((1-ey)*h-.5))<2, ('Eye position',ex,ey,px,py)
print('PASS: normalized eye centers land on the expected pixels in a non-square frame.')

# Eye Beam is a ray, not an infinite line through the face. A viewer-left eye must emit toward the
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
assert outward > inward*4 + 100, ('Eye Beam must start at eye and travel outward',outward,inward)
print('PASS: Eye Beam originates at the eye and follows left gaze.')

# Shared gaze must steer both eyes the same way, not force left/right divergence.
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0]); vec('uEyeState',[1,1,0,0])
vec('uGazePose',[1,0,0,1])
right_gaze=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
left_energy=right_gaze[max(0,row-2):row+3, 2:31].sum()
right_energy=right_gaze[max(0,row-2):row+3, 94:126].sum()
assert right_energy > left_energy*2 + 100, ('Both Eye Beam rays must follow shared right gaze',left_energy,right_energy)
vec('uGazePose',[0,0,1,1])
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
assert ratio < 2.4, ('Camera-facing Eye Beam must read as radial lens hit, not directional ray',top,bottom)

# A genuine down glance still has a lower eye-depth component and must retain a directional ray.
# Validate directionality, not total brightness: the frontal starburst is intentionally bright,
# so comparing absolute energy against it can falsely fail even when the down ray is correct.
vec('uHeadPose',[0,.28,0,.97])
vec('uGazePose',[0,.55,.42,1])
down_gaze=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
down_energy=down_gaze[:24].sum()
up_energy=down_gaze[-24:].sum()
assert down_energy > up_energy*1.35 + 100, ('True down gaze must keep downward beam',down_energy,up_energy)

# Roll must not rotate the gaze away from the eye. Positive gaze Y is image-space DOWN.
amounts(1)
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0])
vec('uEyeState',[1,1,.55,.55]); vec('uHeadPose',[0,.45,.55,.72]); vec('uGazePose',[0,.70,.38,1])
rolled_down=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
rolled_down_energy=rolled_down[:24].sum()
rolled_up_energy=rolled_down[-24:].sum()
assert rolled_down_energy > rolled_up_energy*1.20 + 100, ('Rolled Eye Beam down gaze must still point down',rolled_down_energy,rolled_up_energy)
vec('uEyeState',[1,1,0,0]); vec('uHeadPose',[0,0,0,1]); vec('uGazePose',[-1,0,0,1])
print('PASS: Eye Beam follows shared gaze, including rolled-head down gaze, and foreshortens toward camera.')

# Real per-eye gaze: each eye can point independently and must not be forced through shared gaze.
source[:,:,:3]=30
fn(gl,'glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,source.ctypes.data)
amounts(1); vec('uGazePose',[0,0,1,1]); vec('uHeadPose',[0,0,0,1])
vec('uRightEye',[0,0,0,0]); vec('uLeftEye',[.35,.5,.045,0]); vec('uEyeState',[1,0,0,0])
vec('uLeftGaze',[0,.82,.30,.95]); vec('uRightGaze',[0,0,1,0])
left_down=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
assert left_down[:24].sum() > left_down[-24:].sum()*1.15 + 100, 'Left eye real gaze must point down'

vec('uLeftEye',[0,0,0,0]); vec('uRightEye',[.65,.5,.045,0]); vec('uEyeState',[0,1,0,0])
vec('uLeftGaze',[0,0,1,0]); vec('uRightGaze',[0,-.82,.30,.95])
right_up=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
assert right_up[-24:].sum() > right_up[:24].sum()*1.15 + 100, 'Right eye real gaze must point up independently'

# Low-confidence pupil data must not create a long fake ray; it falls back to frontal/shared pose.
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[0,0,0,0]); vec('uEyeState',[1,0,0,0])
vec('uGazePose',[0,0,1,1]); vec('uLeftGaze',[1,0,.2,.01])
low_conf=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
center_conf=low_conf[max(0,row-16):min(h,row+17), 24:62].sum()
edge_conf=low_conf[:, 100:].sum()
assert center_conf > edge_conf + 100, 'Low-confidence gaze must stay local instead of drawing a fake long beam'

vec('uLeftGaze',[0,0,1,0]); vec('uRightGaze',[0,0,1,0])
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0]); vec('uEyeState',[1,1,0,0])
print('PASS: left/right Eye Beam directions are independently eye-driven with confidence fallback.')

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

# Blink gating is independent per eye. <=0.18 must be fully off; >=0.30 is fully on.
# Use frontal gaze so each eye's flare stays local. With side gaze, the open opposite eye's
# shared Eye Beam ray can legitimately cross the closed eye's half of the frame.
vec('uGazePose',[0,0,1,1])
vec('uLeftEye',[.35,.5,.045,0]); vec('uRightEye',[.65,.5,.045,0]); amounts(1)
vec('uEyeState',[.35,.35,0,0])
both_open=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
vec('uEyeState',[.15,.35,0,0])
left_blink=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
vec('uEyeState',[.35,.15,0,0])
right_blink=render()[:,:,:3].astype(int)-source[:,:,:3].astype(int)
vec('uEyeState',[.15,.15,0,0])
both_blink=render()
left_slice=np.s_[:, :w//2, :]
right_slice=np.s_[:, w//2:, :]
assert left_blink[left_slice].sum() < both_open[left_slice].sum()*.20, 'Left blink must turn off left effect'
assert right_blink[right_slice].sum() < both_open[right_slice].sum()*.20, 'Right blink must turn off right effect'
assert right_blink[left_slice].sum() > both_open[left_slice].sum()*.70, 'Right blink must not turn off left effect'
assert left_blink[right_slice].sum() > both_open[right_slice].sum()*.70, 'Left blink must not turn off right effect'
assert np.array_equal(both_blink,source), 'Both closed eyes must remove visible eye effects'
print('PASS: per-eye blink gate independently disables closed-eye effects.')
