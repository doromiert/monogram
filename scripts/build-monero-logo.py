#!/usr/bin/env python3
"""Run using Blender: blender -b --factory-startup --python scripts/build-monero-logo.py.
Extrudes the two original Wikimedia Monero paths; excludes the wordmark.
"""
from pathlib import Path
import bpy, math, re, struct, json, hashlib
import xml.etree.ElementTree as ET
from mathutils import Vector
from mathutils.geometry import tessellate_polygon

root=Path(__file__).resolve().parent.parent
asset=root/'TMessagesProj/src/main/assets/models/monero'
source=asset/'logo.svg'

def contour(data):
    tokens=re.findall(r'[A-Za-z]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?',data)
    result=[];i=0;command=None;x=y=0.0
    while i<len(tokens):
        if tokens[i].isalpha():command=tokens[i];i+=1
        if command.lower()=='z':break
        count={'m':2,'l':2,'c':6}[command.lower()]
        values=[float(v) for v in tokens[i:i+count]];i+=count
        relative=command.islower();kind=command.lower()
        if kind in ('m','l'):
            x=values[0]+(x if relative else 0);y=values[1]+(y if relative else 0);result.append((x,y))
            if kind=='m':command='l' if relative else 'L'
        else:
            start=Vector((x,y));a=Vector(values[:2]);b=Vector(values[2:4]);end=Vector(values[4:])
            if relative:a+=start;b+=start;end+=start
            for step in range(1,33):
                t=step/32.0;point=(1-t)**3*start+3*(1-t)**2*t*a+3*(1-t)*t*t*b+t**3*end;result.append(tuple(point))
            x,y=end
    if (Vector(result[-1])-Vector(result[0])).length<1e-5:result.pop()
    points=[((x-37.3)/37.0,(37.25529395-y)/37.0) for x,y in result]
    area=sum(points[n][0]*points[(n+1)%len(points)][1]-points[(n+1)%len(points)][0]*points[n][1] for n in range(len(points)))
    return points if area>0 else list(reversed(points))

bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
objects=[];lottie_shapes=[]
for path in ET.parse(source).getroot():
    if path.attrib.get('id') not in ('path22','path26'):continue
    points=contour(path.attrib['d']);count=len(points);depth=.24
    lottie_vertices=[[x*37.0+37.3,37.25529395-y*37.0] for x,y in points]
    color_hex=path.attrib['style'].split('#')[1][:6];color=[int(color_hex[n:n+2],16)/255 for n in (0,2,4)]+[1]
    lottie_shapes.append({'ty':'gr','it':[{'ty':'sh','ks':{'a':0,'k':{'i':[[0,0]]*count,'o':[[0,0]]*count,'v':lottie_vertices,'c':True}}},{'ty':'fl','c':{'a':0,'k':color},'o':{'a':0,'k':100},'r':1},{'ty':'tr','p':{'a':0,'k':[0,0]},'a':{'a':0,'k':[0,0]},'s':{'a':0,'k':[100,100]},'r':{'a':0,'k':0},'o':{'a':0,'k':100}}]})
    vertices=[(x,y,z) for z in (depth/2,-depth/2) for x,y in points]
    lookup={tuple(p):i for i,p in enumerate(points)}
    triangles=tessellate_polygon([[Vector((x,y,0)) for x,y in points]])
    faces=[]
    for triangle in triangles:
        ids=[v if isinstance(v,int) else lookup[(v.x,v.y)] if (v.x,v.y) in lookup else min(range(count),key=lambda i:(points[i][0]-v.x)**2+(points[i][1]-v.y)**2) for v in triangle]
        if (Vector(points[ids[1]])-Vector(points[ids[0]])).cross(Vector(points[ids[2]])-Vector(points[ids[0]]))<0:ids.reverse()
        faces.append(tuple(ids));faces.append(tuple(i+count for i in reversed(ids)))
    for i in range(count):j=(i+1)%count;faces.append((i,i+count,j+count,j))
    mesh=bpy.data.meshes.new(path.attrib['id']);mesh.from_pydata(vertices,[],faces);mesh.update()
    obj=bpy.data.objects.new(path.attrib['id'],mesh);bpy.context.collection.objects.link(obj)
    color=path.attrib['style'].split('#')[1][:6];rgb=tuple(int(color[n:n+2],16)/255 for n in (0,2,4))
    material=bpy.data.materials.new('Monero '+color);material.diffuse_color=(*rgb,1);material.use_nodes=True
    shader=material.node_tree.nodes.get('Principled BSDF');shader.inputs['Base Color'].default_value=(*(component**2.2 for component in rgb),1);shader.inputs['Metallic'].default_value=.65;shader.inputs['Roughness'].default_value=.24
    obj.data.materials.append(material)
    bevel=obj.modifiers.new('Soft machined rim','BEVEL');bevel.width=.018;bevel.segments=3;bevel.limit_method='ANGLE';bevel.angle_limit=.3
    weighted=obj.modifiers.new('Face normals','WEIGHTED_NORMAL');weighted.keep_sharp=True;weighted.weight=50
    for polygon in mesh.polygons:polygon.use_smooth=True
    obj['monero_logo']=True
    objects.append(obj)

values=[];depsgraph=bpy.context.evaluated_depsgraph_get()
for obj in objects:
    evaluated=obj.evaluated_get(depsgraph);mesh=evaluated.to_mesh();mesh.calc_loop_triangles()
    rgb=obj.data.materials[0].diffuse_color[:3]
    for triangle in mesh.loop_triangles:
        for loop_id in triangle.loops:
            loop=mesh.loops[loop_id];position=mesh.vertices[loop.vertex_index].co;normal=mesh.corner_normals[loop_id].vector
            values.extend((*position,*normal,*rgb,.65,.24))
    evaluated.to_mesh_clear()
blob=struct.pack('<4sII',b'XMR2',len(values)//11,11)+struct.pack('<%sf'%len(values),*values)
(asset/'extruded.bin').write_bytes(blob)
fallback={'v':'5.7.4','fr':30,'ip':0,'op':1,'w':75,'h':75,'layers':[{'ddd':0,'ind':1,'ty':4,'nm':'Monero','ks':{'o':{'a':0,'k':100},'r':{'a':0,'k':0},'p':{'a':0,'k':[0,0,0]},'a':{'a':0,'k':[0,0,0]},'s':{'a':0,'k':[100,100,100]}},'shapes':lottie_shapes,'ip':0,'op':1,'st':0,'bm':0}]}
(root/'TMessagesProj/src/main/res/raw/wallet_monero.json').write_text(json.dumps(fallback,separators=(',',':')))

metadata={'source':'https://upload.wikimedia.org/wikipedia/commons/2/2d/Monero-Logo.svg','source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'paths':['path22','path26'],'depth':.24,'bevel':.018,'vertex_stride_floats':11,'vertices':len(values)//11,'mesh_sha256':hashlib.sha256(blob).hexdigest(),'generator':'scripts/build-monero-logo.py','blender':bpy.app.version_string}
(asset/'extruded.json').write_text(json.dumps(metadata,indent=2)+'\n')
print(json.dumps(metadata))

# Optional visual inspection of the generated geometry; no wallet/app execution.
if '--preview' in __import__('sys').argv or '--save-blend' in __import__('sys').argv:
    scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=12
    scene.render.resolution_x=384;scene.render.resolution_y=384;scene.render.resolution_percentage=100
    scene.render.image_settings.file_format='PNG';scene.render.film_transparent=True
    scene.world.color=(.15,.15,.15)
    camera=bpy.data.objects.new('Camera',bpy.data.cameras.new('Camera'));scene.collection.objects.link(camera);camera.location=(0,0,5) if '--front' in __import__('sys').argv else (2,-1.1,5)
    camera.rotation_euler=(Vector((0,0,0))-camera.location).to_track_quat('-Z','Y').to_euler();camera.data.type='ORTHO';camera.data.ortho_scale=2.5;scene.camera=camera
    for name,location,energy,size in [('Key',(-3,-2,5),500,4),('Rim',(3,2,2),350,3)]:
        light=bpy.data.objects.new(name,bpy.data.lights.new(name,'AREA'));scene.collection.objects.link(light);light.location=location;light.data.energy=energy;light.data.shape='DISK';light.data.size=size;light.rotation_euler=(-light.location).to_track_quat('-Z','Y').to_euler()
    scene.view_settings.view_transform='Standard'
    if '--save-blend' in __import__('sys').argv:
        output=root/'design/monero-logo.blend';output.parent.mkdir(parents=True,exist_ok=True)
        bpy.ops.object.select_all(action='DESELECT')
        for obj in objects:obj.select_set(True)
        bpy.context.view_layer.objects.active=objects[0]
        if output.exists() and '--overwrite-blend' not in __import__('sys').argv:raise FileExistsError('The editable .blend already exists; use export-monero-logo.py to preserve user edits.')
        bpy.ops.wm.save_as_mainfile(filepath=str(output))
    if '--preview' in __import__('sys').argv:
        scene.render.filepath=str(root/('.state/monero-extruded-front.png' if '--front' in __import__('sys').argv else '.state/monero-extruded-preview.png'));bpy.ops.render.render(write_still=True)
