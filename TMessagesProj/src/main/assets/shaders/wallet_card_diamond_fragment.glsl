#version 300 es
precision highp float;

uniform sampler2D uCardDetailTexture;
uniform vec4 uDiamondBounds;
uniform float uDiamondAlpha;
flat in vec3 vEngravingXAxis;
flat in vec3 vEngravingYAxis;
flat in vec3 vFaceNormal;
flat in vec3 vLightDirection;
in vec3 vObjectNormal;
in vec3 vViewPosition;
in vec2 vCardPosition;
out vec4 fragmentColor;

void main() {
    vec2 cardUv = vec2(vCardPosition.x * 0.5 + 0.5,
            (0.618561 - vCardPosition.y) / 1.212122);
    vec2 uv = (cardUv - uDiamondBounds.xy) / uDiamondBounds.zw;
    // Sample before discard to preserve derivatives at the silhouette.
    // GLES render targets have a bottom-left origin; the card artwork uses top-left.
    vec4 sampleColor = texture(uCardDetailTexture, vec2(uv.x, 1.0 - uv.y));
    if (vObjectNormal.x > -0.5 || any(lessThan(uv, vec2(0.0)))
            || any(greaterThan(uv, vec2(1.0))) || sampleColor.a <= 0.0) discard;

    // The texture already contains the extruded Monero mesh and its lighting.
    // Follow the card face, without imposing the old diamond's facet pattern.
    vec3 normal = normalize(vFaceNormal);
    vec3 viewDirection = normalize(-vViewPosition);
    vec3 halfway = normalize(viewDirection + vLightDirection);
    float diffuse = max(dot(normal, vLightDirection), 0.0);
    float shine = pow(max(dot(normal, halfway), 0.0), 52.0);
    float broadShine = pow(max(dot(normal, halfway), 0.0), 10.0);
    float fresnel = pow(1.0 - max(dot(normal, viewDirection), 0.0), 4.0);

    // Preserve the supplied texture even outside the card's moving light band.
    // The shared light adds facet glints; it never dims the entire icon in unison.
    vec3 artwork = sampleColor.rgb / max(sampleColor.a, 0.00392157);
    vec3 color = artwork * (0.94 + 0.06 * diffuse);
    color += vec3(0.90, 0.96, 1.0)
            * (0.34 * shine + 0.055 * broadShine + 0.10 * fresnel);
    // Fade premultiplied RGB and coverage together, preserving the card beneath.
    float alpha = sampleColor.a * uDiamondAlpha;
    fragmentColor = vec4(min(color, vec3(1.0)) * alpha, alpha);
}
