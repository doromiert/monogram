#version 300 es
precision highp float;
layout(location=0) in vec3 position;
layout(location=1) in vec3 normal;
layout(location=2) in vec3 color;
layout(location=3) in vec2 materialInfo;
uniform mat4 model;
uniform mat4 mvp;
out vec3 vNormal;
out vec3 vPosition;
out vec3 vColor;
out vec2 vMaterial;
void main(){vec4 p=model*vec4(position,1.0);vPosition=p.xyz;vNormal=normalize(mat3(model)*normal);vColor=color;vMaterial=materialInfo;gl_Position=mvp*vec4(position,1.0);}
