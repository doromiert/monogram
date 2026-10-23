// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;
import android.opengl.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;
/** Compiles the actual wallet shader variants in an offscreen GLES3 context; no Activity or wallet. */
public final class WalletShaderSmoke {
 static String define(String source,String mode){int end=source.indexOf('\n')+1;return source.substring(0,end)+"#define "+mode+"\n"+source.substring(end);}
 static int shader(int type,String source){int s=GLES30.glCreateShader(type);GLES30.glShaderSource(s,source);GLES30.glCompileShader(s);int[] ok=new int[1];GLES30.glGetShaderiv(s,GLES30.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new AssertionError(GLES30.glGetShaderInfoLog(s));return s;}
 public static void main(String[] args)throws Exception{
  EGLDisplay display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);if(!EGL14.eglInitialize(display,new int[2],0,new int[2],0))throw new AssertionError("EGL initialization");
  EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,count,0);
  EGLContext context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
  EGLSurface surface=EGL14.eglCreatePbufferSurface(display,configs[0],new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);if(!EGL14.eglMakeCurrent(display,surface,surface,context))throw new AssertionError("EGL context");
  String vertex=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8),fragment=new String(Files.readAllBytes(Paths.get(args[1])),StandardCharsets.UTF_8);
  for(String mode:new String[]{"WALLET_BODY_PASS","WALLET_ENGRAVING_PASS","WALLET_QR_PASS","WALLET_EDGE_PASS","WALLET_FLECKS_PASS"}){
   int vs=shader(GLES30.GL_VERTEX_SHADER,define(vertex,mode)),fs=shader(GLES30.GL_FRAGMENT_SHADER,define(fragment,mode)),program=GLES30.glCreateProgram();GLES30.glAttachShader(program,vs);GLES30.glAttachShader(program,fs);GLES30.glLinkProgram(program);int[] ok=new int[1];GLES30.glGetProgramiv(program,GLES30.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new AssertionError(mode+": "+GLES30.glGetProgramInfoLog(program));GLES30.glDeleteProgram(program);GLES30.glDeleteShader(vs);GLES30.glDeleteShader(fs);
  }
  EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);EGL14.eglDestroySurface(display,surface);EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);
  System.out.println("PASS: five actual wallet shader variants compile and link with moving QR/address uniforms");
 }
}
