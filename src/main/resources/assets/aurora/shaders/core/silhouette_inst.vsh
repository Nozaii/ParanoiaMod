#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Hand Aura instanciee : la meme silhouette dessinee une fois par decalage (gl_InstanceID).
// Offsets[i] : xy = decalage dans le repere de la main (comme l ancienne translation de la pose), z = alpha.

layout(std140) uniform AuraOffsets {
    vec4 Offsets[36];
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    vec4 o = Offsets[gl_InstanceID];
    vec3 pos = Position + vec3(o.xy, 0.0);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = vec4(Color.rgb, Color.a * o.z);
    lightMapColor = vec4(1.0);
    overlayColor = vec4(1.0);
    texCoord0 = UV0;
}
