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
    fragment = re.search(r'FRAGMENT_SHADER = """(.*?)"""', text, re.S).group(1)
    program = fn(gl, 'glCreateProgram', U)()
    for src, kind in [(vertex, 0x8B31), (fragment, 0x8B30)]:
        fn(gl, 'glAttachShader', None, U, U)(program, compile_one(src, kind))
    fn(gl, 'glLinkProgram', None, U)(program)
    status = I(); fn(gl, 'glGetProgramiv', None, U, U, P)(program, 0x8B82, c.byref(status))
    assert status.value, 'Link failed'
    return program

base = 'app/src/main/java/com/tajuli/digitorandroid/editor/render/'
program = program_for(base + 'CutoutEffectV43.kt')
program_for(base + 'FabricAwareCutoutRefineV46.kt')
fn(gl, 'glUseProgram', None, U)(program)
location = fn(gl, 'glGetUniformLocation', I, U, c.c_char_p)
one = fn(gl, 'glUniform1f', None, I, F)
def uniform(name, value): one(location(program, name.encode()), value)

def texture(unit, data):
    ident = U(); fn(gl, 'glGenTextures', None, I, P)(1, c.byref(ident))
    fn(gl, 'glActiveTexture', None, U)(0x84C0 + unit)
    fn(gl, 'glBindTexture', None, U, U)(0x0DE1, ident)
    parameter = fn(gl, 'glTexParameteri', None, U, U, I)
    for pname, val in [(0x2801, 0x2601), (0x2800, 0x2601), (0x2802, 0x812F), (0x2803, 0x812F)]:
        parameter(0x0DE1, pname, val)
    fn(gl, 'glTexImage2D', None, U, I, I, I, I, I, U, U, P)(0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, data.ctypes.data)
    name = ['uTexSampler', 'uMaskA', 'uMaskB'][unit]
    fn(gl, 'glUniform1i', None, I, I)(location(program, name.encode()), unit)
    return ident

y, x = np.mgrid[:h, :w]
source = np.zeros((h, w, 4), dtype=np.uint8)
source[:, :, :3] = (((x + y) % 2) * 255)[:, :, None]
source[:, :w//2, :3] = [255, 32, 16]; source[:, :, 3] = 255
mask = np.zeros_like(source); mask[:, :w//2, :3] = 255; mask[:, :, 3] = 255
texture(0, source); texture(1, mask); texture(2, mask)
fn(gl, 'glUniform2f', None, I, F, F)(location(program, b'uTexelSize'), 1/w, 1/h)
for name, value in [('uMode', 1), ('uLensBlur', 1), ('uLensRadius', 24*w/1080), ('uHasMaskA', 1), ('uHasMaskB', 1), ('uTemporalMix', 0)]: uniform(name, value)
vertices = np.array([-1,-1,0,1, 1,-1,0,1, -1,1,0,1, 1,1,0,1], dtype=np.float32)
attribute = fn(gl, 'glGetAttribLocation', I, U, c.c_char_p)(program, b'aFramePosition')
fn(gl, 'glEnableVertexAttribArray', None, U)(attribute)
fn(gl, 'glVertexAttribPointer', None, U, I, U, U, I, P)(attribute, 4, 0x1406, 0, 0, vertices.ctypes.data)
fn(gl, 'glViewport', None, I, I, I, I)(0, 0, w, h)

def render():
    fn(gl, 'glDrawArrays', None, U, I, I)(5, 0, 4)
    out = np.zeros_like(source)
    fn(gl, 'glReadPixels', None, I, I, I, I, U, U, P)(0, 0, w, h, 0x1908, 0x1401, out.ctypes.data)
    error = fn(gl, 'glGetError', U)(); assert error == 0, hex(error)
    return out

out = render()
assert np.array_equal(out[:, :w//2], source[:, :w//2]), 'Subject pixels changed'
assert 60 < out[64, 96, 0] < 195, 'Background was not defocused'
assert np.all(out[:, :, 3] == 255), 'Blur removed background alpha'
uniform('uLensRadius', 0)
assert np.array_equal(render(), source), 'Zero amount is not identity'
uniform('uLensRadius', 24*w/1080); uniform('uHasMaskA', 0); uniform('uHasMaskB', 0)
assert np.array_equal(render(), source), 'Missing matte must pass through'
print('PASS: both production shaders compile/link; subject exact, background blurred, alpha retained, zero strength and missing matte are identity.')
fn(egl, 'eglMakeCurrent', U, P, P, P, P)(display, None, None, None)
fn(egl, 'eglDestroySurface', U, P, P)(display, surface)
fn(egl, 'eglDestroyContext', U, P, P)(display, ctx)
fn(egl, 'eglTerminate', U, P)(display)
