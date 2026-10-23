#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vPosition;
in vec3 vColor;
in vec2 vMaterial;
uniform float time;
out vec4 fragmentColor;
void main(){
    vec3 n=normalize(vNormal),v=normalize(vec3(0.0,0.0,3.8)-vPosition);
    vec3 key=normalize(vec3(-2.5,3.5,5.0));
    vec3 fill=normalize(vec3(3.0,-1.0,2.5));
    vec3 h=normalize(key+v),hf=normalize(fill+v);
    vec3 base=pow(vColor,vec3(2.2));
    float diffuse=0.52+0.40*max(dot(n,key),0.0)+0.16*max(dot(n,fill),0.0);
    float metallic=clamp(vMaterial.x,0.0,1.0),roughness=clamp(vMaterial.y,0.04,1.0);
    float exponent=clamp(2.0/(roughness*roughness)-2.0,4.0,256.0);
    float gloss=pow(max(dot(n,h),0.0),exponent)+0.35*pow(max(dot(n,hf),0.0),exponent*1.4);
    float rim=pow(1.0-max(dot(n,v),0.0),4.0);
    vec3 linear=base*diffuse+mix(vec3(0.08),base,metallic)*gloss+base*rim*0.2;
    fragmentColor=vec4(pow(clamp(linear,0.0,1.0),vec3(1.0/2.2)),1.0);
}
