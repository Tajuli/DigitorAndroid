"""Production readback shader color/orientation regression (Mesa EGL, no Android SDK).
The Android instrumentation companion exercises the real OES producer and Bitmap packing.
"""
import ctypes as c
import re
import sys
from pathlib import Path
import numpy as np
import verify_eye_effects_gl as gpu

# Reuse the EGL context and compiler; importing also checks the production eye renderer.
gl, fn, I, U, F, P = gpu.gl, gpu.fn, gpu.I, gpu.U, gpu.F, gpu.P
text = Path(sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/java/com/tajuli/digitorandroid/editor/processing/GpuSequentialCutoutDecoderV47.kt').read_text()
vertex = re.search(r'VERTEX_SHADER = """(.*?)"""', text, re.S).group(1)
fragment = re.search(r'OES_FRAGMENT_SHADER = """(.*?)"""', text, re.S).group(1)
# A normal texture stands in for the codec producer. Keep all production sampling math.
fragment = fragment.replace('#extension GL_OES_EGL_image_external : require', '').replace('samplerExternalOES', 'sampler2D')
program = fn(gl, 'glCreateProgram', U)()
for src, kind in [(vertex, 0x8B31), (fragment, 0x8B30)]:
    fn(gl, 'glAttachShader', None, U, U)(program, gpu.compile_one(src, kind))
fn(gl, 'glLinkProgram', None, U)(program)
status = I(); fn(gl, 'glGetProgramiv', None, U, U, P)(program, 0x8B82, c.byref(status))
assert status.value
fn(gl, 'glUseProgram', None, U)(program)
loc = lambda name: fn(gl, 'glGetUniformLocation', I, U, c.c_char_p)(program, name.encode())
# SurfaceTexture producer convention: Y flip. Bitmap consumes readback row 0 as top.
matrix = np.array([1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,1,0,1], np.float32)
fn(gl, 'glUniformMatrix4fv', None, I, I, U, P)(loc('uTexMatrix'), 1, 0, matrix.ctypes.data)
source = np.zeros((96,128,4), np.uint8)
colors = [(255,0,0,255),(0,255,0,255),(0,0,255,255),(255,255,0,255)]
source[:48,:64] = colors[0]; source[:48,64:] = colors[1]
source[48:,:64] = colors[2]; source[48:,64:] = colors[3]
fn(gl, 'glBindTexture', None, U, U)(0x0DE1, gpu.ident)
fn(gl, 'glTexImage2D', None, U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,128,96,0,0x1908,0x1401,source.ctypes.data)
vertices = np.array([-1,-1, 1,-1, -1,1, 1,1], np.float32)
attribute = fn(gl,'glGetAttribLocation',I,U,c.c_char_p)(program,b'aPosition')
fn(gl,'glEnableVertexAttribArray',None,U)(attribute)
fn(gl,'glVertexAttribPointer',None,U,I,U,U,I,P)(attribute,2,0x1406,0,0,vertices.ctypes.data)
for rotation in (0,90,180,270):
    fn(gl,'glUniform1f',None,I,F)(loc('uRotation'),rotation)
    expected = np.rot90(source, -rotation//90)
    for edge in (128,64):
        w,h = (edge*3//4,edge) if rotation in (90,270) else (edge,edge*3//4)
        fn(gl,'glViewport',None,I,I,I,I)(0,0,w,h)
        fn(gl,'glDrawArrays',None,U,I,I)(5,0,4)
        actual = np.zeros((h,w,4),np.uint8)
        fn(gl,'glReadPixels',None,I,I,I,I,U,U,P)(0,0,w,h,0x1908,0x1401,actual.ctypes.data)
        assert fn(gl,'glGetError',U)()==0
        for x,y in ((.25,.25),(.75,.25),(.25,.75),(.75,.75)):
            want = expected[int(y*expected.shape[0]),int(x*expected.shape[1])]
            got = actual[int(y*h),int(x*w)]
            assert np.max(np.abs(got.astype(int)-want.astype(int)))<3, (rotation,edge,x,y,got,want)
print('PASS: decoder RGBA and normalized quadrants at 0/90/180/270 degrees, two resolutions')
