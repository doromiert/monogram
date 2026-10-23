#!/usr/bin/env python3
"""Export the saved, user-edited model without modifying the .blend file.
blender -b design/monero-logo.blend --python-exit-code 1 --python scripts/export-monero-logo.py
"""
import bpy,struct,json,hashlib
from pathlib import Path
root=Path(__file__).resolve().parent.parent
asset=root/'TMessagesProj/src/main/assets/models/monero'
objects=[obj for obj in bpy.context.scene.objects if obj.type=='MESH' and (obj.get('monero_logo',False) or obj.name in ('path22','path26','monero_white','Cylinder'))]
if not objects:raise RuntimeError('No Monero logo objects found. Keep the original object names or set monero_logo=True.')
def srgb(c):return 12.92*c if c<=.0031308 else 1.055*c**(1/2.4)-.055
values=[];depsgraph=bpy.context.evaluated_depsgraph_get()
for obj in objects:
    evaluated=obj.evaluated_get(depsgraph);mesh=evaluated.to_mesh();mesh.calc_loop_triangles()
    normal_matrix=obj.matrix_world.to_3x3().inverted().transposed()
    for triangle in mesh.loop_triangles:
        material=mesh.materials[triangle.material_index] if len(mesh.materials)>triangle.material_index else None
        shader=material.node_tree.nodes.get('Principled BSDF') if material and material.use_nodes else None
        if shader:
            rgb=[srgb(v) for v in shader.inputs['Base Color'].default_value[:3]]
            metallic=shader.inputs['Metallic'].default_value;roughness=shader.inputs['Roughness'].default_value
        elif material is None and obj.name in ('monero_white','Cylinder'):
            # The user-saved backing has an empty material slot (Blender displays white).
            rgb=[1.,1.,1.];metallic=0.;roughness=.85
        else:rgb=list(material.diffuse_color[:3]) if material else [.3,.3,.3];metallic=.65;roughness=.24
        for loop_id in triangle.loops:
            loop=mesh.loops[loop_id];position=obj.matrix_world@mesh.vertices[loop.vertex_index].co;normal=(normal_matrix@mesh.corner_normals[loop_id].vector).normalized()
            values.extend((*position,*normal,*rgb,metallic,roughness))
    evaluated.to_mesh_clear()
blob=struct.pack('<4sII',b'XMR2',len(values)//11,11)+struct.pack('<%sf'%len(values),*values)
asset.mkdir(parents=True,exist_ok=True);(asset/'extruded.bin').write_bytes(blob)
blend=Path(bpy.data.filepath)
metadata={'source_blend':str(blend.relative_to(root)) if blend.is_relative_to(root) else blend.name,'source_blend_sha256':hashlib.sha256(blend.read_bytes()).hexdigest(),'objects':[obj.name for obj in objects],'vertex_stride_floats':11,'vertices':len(values)//11,'mesh_sha256':hashlib.sha256(blob).hexdigest(),'generator':'scripts/export-monero-logo.py','blender':bpy.app.version_string}
(asset/'extruded.json').write_text(json.dumps(metadata,indent=2)+'\n');print(json.dumps(metadata))
