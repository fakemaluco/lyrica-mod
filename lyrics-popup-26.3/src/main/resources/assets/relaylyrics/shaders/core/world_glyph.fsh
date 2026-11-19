#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    // R8 stores coverage. Multiplying RGB by it as well makes partially covered strokes dark.
    float coverage = texture(Sampler0, texCoord0).r;
    vec4 color = vertexColor * ColorModulator;
    color.a *= coverage;
    if (color.a <= 1.0 / 255.0) discard;

#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
    vec4 fogColor = FogColor;
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    fogColor.rgb *= color.a;
    #endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
#endif
}
