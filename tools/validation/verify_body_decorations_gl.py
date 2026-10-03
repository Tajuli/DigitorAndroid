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
w = h = 128
surface = fn(egl, 'eglCreatePbufferSurface', P, P, P, P)(display, config, (I * 5)(0x3057, w, 0x3056, h, 0x3038))
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
    eye = Path('app/src/main/java/com/tajuli/digitorandroid/editor/render/BodyDecorationShader.kt').read_text().split('"""')[1]
    fragment = fragment.replace('$BODY_DECORATION_SHADER', eye)
    program = fn(gl, 'glCreateProgram', U)()
    for src, kind in [(vertex, 0x8B31), (fragment, 0x8B30)]:
        fn(gl, 'glAttachShader', None, U, U)(program, compile_one(src, kind))
    fn(gl, 'glLinkProgram', None, U)(program)
    status = I(); fn(gl, 'glGetProgramiv', None, U, U, P)(program, 0x8B82, c.byref(status))
    assert status.value, 'Link failed'
    return program


base = 'app/src/main/java/com/tajuli/digitorandroid/editor/render/'
program = program_for(base + 'BodyEffectGraphV102.kt')
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
vec('uTexelSize',[1/w,1/h]);vec('uBodyBounds',[.5,.5,.2,.35]);uniform('uTime',.35)
uniform('uHasMaskA',1);uniform('uHasMaskB',1);uniform('uAlphaBias',.5);uniform('uEdgeSoftness',.04)
mask=np.zeros_like(source);mask[:,:,3]=255
mask[((x-w*.5)/(w*.2))**2+((y-h*.5)/(h*.35))**2<1,:3]=255
for unit in [1,2]:
    mid=U();fn(gl,'glGenTextures',None,I,P)(1,c.byref(mid))
    fn(gl,'glActiveTexture',None,U)(0x84C0+unit);fn(gl,'glBindTexture',None,U,U)(0x0DE1,mid)
    for key,val in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:fn(gl,'glTexParameteri',None,U,U,I)(0x0DE1,key,val)
    fn(gl,'glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,mask.ctypes.data)
    fn(gl,'glUniform1i',None,I,I)(location(program,('uMaskA' if unit==1 else 'uMaskB').encode()),unit)
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
def amounts(index):
    a=[0.]*32
    if index>=0:a[index]=1.
    for i in range(8):vec('uDecor'+str(i),a[i*4:i*4+4])
amounts(-1);assert np.array_equal(render(),source),'Zero strength'
results=[]
for index in range(31):
    amounts(index);out=render();results.append(out)
    assert np.max(np.abs(out[:,:,:3].astype(int)-source[:,:,:3].astype(int)))>2,('Invisible',index)
    assert np.array_equal(out[:,:,3],source[:,:,3]),('Alpha',index)
    uniform('uHasMaskA',0);uniform('uHasMaskB',0)
    assert np.array_equal(render(),source),('Missing matte',index)
    uniform('uHasMaskA',1);uniform('uHasMaskB',1)
for i in range(31):
    for j in range(i):assert not np.array_equal(results[i],results[j]),('Duplicate',i,j)
print('PASS: 31 body decorations compile/link and visibly differ; zero strength, missing matte, alpha preservation pass.')
