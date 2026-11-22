#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Hand Aura : silhouette de l objet (alpha de sa texture) en couleur unie, pour le contour lumineux.

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    float a = texture(Sampler0, texCoord0).a;
    if (a < 0.1) {
        discard;
    }
    fragColor = vertexColor * ColorModulator;
}
