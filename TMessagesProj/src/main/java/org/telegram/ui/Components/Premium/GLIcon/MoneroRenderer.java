// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Components.Premium.GLIcon;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.Matrix;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Extruded official SVG geometry in the source wallet's existing EGL/pose pipeline. */
public final class MoneroRenderer {
    private final int modelProgram,copyProgram,count;
    private final int[] vao=new int[2],vbo=new int[1],frame=new int[2],renderbuffer=new int[2],texture=new int[1];
    private final float[] model=new float[16],view=new float[16],projection=new float[16],mv=new float[16],mvp=new float[16];
    private int width,height,samples;
    float resolutionScale=1;
    private float clock;
    public MoneroRenderer(Context context,int maxSamples){
        try{
            byte[] bytes=read(context,"models/monero/extruded.bin");ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if(data.getInt()!=0x32524d58)throw new IOException("Invalid Monero mesh.");
            count=data.getInt();int stride=data.getInt();if(stride!=11 || count<=0 || count%3!=0 || bytes.length!=12+count*44)throw new IOException("Invalid Monero mesh size.");
            FloatBuffer vertices=ByteBuffer.allocateDirect(count*44).order(ByteOrder.nativeOrder()).asFloatBuffer();while(data.hasRemaining())vertices.put(data.getFloat());vertices.position(0);
            modelProgram=GLProgramCache.create(context,"monero-model",text(context,"shaders/monero/modelVertex.glsl"),text(context,"shaders/monero/modelFragment.glsl"),"position","normal");
            copyProgram=GLProgramCache.create(context,"monero-copy",text(context,"shaders/diamond/fullscreenVertex.glsl"),text(context,"shaders/diamond/copyFragment.glsl"),"position","normal");
            GLES30.glGenVertexArrays(2,vao,0);GLES30.glGenBuffers(1,vbo,0);GLES30.glBindVertexArray(vao[0]);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo[0]);GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,count*44,vertices,GLES30.GL_STATIC_DRAW);
            for(int i=0;i<3;i++){GLES30.glEnableVertexAttribArray(i);GLES30.glVertexAttribPointer(i,3,GLES30.GL_FLOAT,false,44,i*12);}
            GLES30.glEnableVertexAttribArray(3);GLES30.glVertexAttribPointer(3,2,GLES30.GL_FLOAT,false,44,36);
            GLES30.glBindVertexArray(0);GLES30.glGenFramebuffers(2,frame,0);GLES30.glGenRenderbuffers(2,renderbuffer,0);GLES30.glGenTextures(1,texture,0);
            int[] maximum=new int[1];GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES,maximum,0);samples=Math.max(1,Math.min(maxSamples,maximum[0]));
        }catch(Exception failure){throw new IllegalStateException("Cannot load Monero logo.",failure);}
    }
    private static byte[] read(Context context,String path)throws IOException{try(InputStream in=context.getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
    private static String text(Context context,String path)throws IOException{return new String(read(context,path),StandardCharsets.UTF_8);}
    private void resize(int w,int h){
        if(width==w && height==h)return;width=w;height=h;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frame[0]);
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER,renderbuffer[0]);GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER,samples,GLES30.GL_RGBA8,w,h);GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_RENDERBUFFER,renderbuffer[0]);
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER,renderbuffer[1]);GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER,samples,GLES30.GL_DEPTH_COMPONENT16,w,h);GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_RENDERBUFFER,renderbuffer[1]);check();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frame[1]);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,w,h,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,texture[0],0);check();
    }
    private static void check(){if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Monero framebuffer is incomplete.");}
    void draw(int w,int h,float yaw,float pitch,float roll,float dt,float alpha,float white){draw(w,h,yaw,pitch,roll,dt,alpha,white,false);}
    public void drawToTexture(int side,float yaw,float pitch,float dt){draw(side,side,yaw,pitch,0,dt,1,0,true);}
    private void draw(int w,int h,float yaw,float pitch,float roll,float dt,float alpha,float white,boolean readback){
        if(w<=0 || h<=0)return;clock+=Math.max(0,Math.min(dt,.1f));resize(Math.max(1,Math.round(w*resolutionScale)),Math.max(1,Math.round(h*resolutionScale)));
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frame[0]);GLES30.glViewport(0,0,width,height);GLES30.glClearColor(0,0,0,0);GLES30.glDepthMask(true);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);GLES30.glEnable(GLES30.GL_DEPTH_TEST);GLES30.glEnable(GLES30.GL_CULL_FACE);GLES30.glDisable(GLES30.GL_BLEND);GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        Matrix.setIdentityM(model,0);Matrix.rotateM(model,0,-(float)Math.toDegrees(roll),0,0,1);Matrix.rotateM(model,0,(float)Math.toDegrees(pitch),1,0,0);Matrix.rotateM(model,0,(float)Math.toDegrees(yaw),0,1,0);Matrix.scaleM(model,0,.8f,.8f,.8f);
        Matrix.setLookAtM(view,0,0,0,3.8f,0,0,0,0,1,0);Matrix.perspectiveM(projection,0,35,(float)w/h,.1f,10);Matrix.multiplyMM(mv,0,view,0,model,0);Matrix.multiplyMM(mvp,0,projection,0,mv,0);
        GLES30.glUseProgram(modelProgram);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(modelProgram,"model"),1,false,model,0);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(modelProgram,"mvp"),1,false,mvp,0);GLES30.glUniform1f(GLES30.glGetUniformLocation(modelProgram,"time"),clock);GLES30.glBindVertexArray(vao[0]);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,count);
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER,frame[0]);GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,frame[1]);GLES30.glBlitFramebuffer(0,0,width,height,0,0,width,height,GLES30.GL_COLOR_BUFFER_BIT,GLES30.GL_NEAREST);
        if(readback){GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frame[1]);GLES30.glBindVertexArray(0);return;}drawTexture(w,h,alpha,white);
    }
    void drawTexture(int w,int h,float alpha){drawTexture(w,h,alpha,0);}
    private void drawTexture(int w,int h,float alpha,float white){
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glViewport(0,0,w,h);GLES30.glDisable(GLES30.GL_BLEND);GLES30.glDisable(GLES30.GL_DEPTH_TEST);GLES30.glUseProgram(copyProgram);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture[0]);GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProgram,"image"),0);GLES30.glUniform1f(GLES30.glGetUniformLocation(copyProgram,"opacity"),alpha);GLES30.glUniform1f(GLES30.glGetUniformLocation(copyProgram,"white"),white);GLES30.glBindVertexArray(vao[1]);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);GLES30.glBindVertexArray(0);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);GLES30.glEnable(GLES30.GL_DEPTH_TEST);
    }
    public int textureId(){return texture[0];}
    public void destroy(){GLES30.glDeleteProgram(modelProgram);GLES30.glDeleteProgram(copyProgram);GLES30.glDeleteVertexArrays(2,vao,0);GLES30.glDeleteBuffers(1,vbo,0);GLES30.glDeleteFramebuffers(2,frame,0);GLES30.glDeleteRenderbuffers(2,renderbuffer,0);GLES30.glDeleteTextures(1,texture,0);}
}
