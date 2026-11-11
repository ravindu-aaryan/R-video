package com.videomaker.app.media

import android.content.Context
import android.graphics.Matrix
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import com.videomaker.app.model.ClipEffect
import kotlin.math.exp
import kotlin.math.sin

/**
 * Zoom, reposition and movement for one clip: the user's zoom/position, slow "Ken Burns" zoom,
 * camera shake and beat zoom, combined into a single transform.
 *
 * Times are positions on the final timeline, which is what Media3 passes to effects.
 */
@OptIn(UnstableApi::class)
class MotionEffect(
    private val zoom: Float,
    private val offsetX: Float,
    private val offsetY: Float,
    private val slowZoom: Boolean,
    private val shake: Float,
    private val pulse: Float,
    private val clipStartUs: Long,
    private val clipDurationUs: Long,
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val seconds = presentationTimeUs / 1_000_000.0
        var scale = zoom
        if (slowZoom) {
            val progress = ((presentationTimeUs - clipStartUs).toFloat() / clipDurationUs).coerceIn(0f, 1f)
            val eased = progress * progress * (3f - 2f * progress)
            scale *= 1f + 0.18f * eased
        }
        if (pulse > 0f) {
            // A quick punch-in twice a second that eases back out.
            val phase = (seconds * 2.0) % 1.0
            scale *= 1f + 0.14f * pulse * exp(-6.0 * phase).toFloat()
        }
        var dx = 0f
        var dy = 0f
        if (shake > 0f) {
            scale *= 1f + 0.08f * shake // Zoom in a little so the shake never shows the edges.
            dx = (0.03f * shake * (sin(seconds * 37.0) + 0.5 * sin(seconds * 71.0))).toFloat()
            dy = (0.03f * shake * (sin(seconds * 43.0 + 1.3) + 0.5 * sin(seconds * 83.0))).toFloat()
        }
        // The offset moves the picture within the part hidden by the zoom, so no edges show.
        val room = (scale - 1f).coerceAtLeast(0f)
        return Matrix().apply {
            postScale(scale, scale)
            postTranslate(offsetX.coerceIn(-1f, 1f) * room + dx, offsetY.coerceIn(-1f, 1f) * room + dy)
        }
    }
}

/** Shader-based clip effects (glitch, RGB split, VHS, …). */
@OptIn(UnstableApi::class)
class ShaderFxEffect(private val effect: ClipEffect, private val strength: Float) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FxShaderProgram(fragmentShaderFor(effect), strength.coerceIn(0f, 1f), useHdr)

    companion object {
        /** Effects drawn by a fragment shader (the others are transforms or built-in effects). */
        fun supports(effect: ClipEffect): Boolean = effect in BODIES

        private const val VERTEX_SHADER = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        private const val FRAGMENT_HEADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform float uTime;
uniform float uStrength;
uniform vec2 uSize;
varying vec2 vTexSamplingCoord;
float rand(vec2 co) {
  return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}
void main() {
  vec2 c = vTexSamplingCoord;
  vec4 col = texture2D(uTexSampler, c);
"""

        private const val FRAGMENT_FOOTER = """
  gl_FragColor = vec4(clamp(col.rgb, 0.0, 1.0), col.a);
}
"""

        // Each body may change `col` (the pixel colour). `c` is the pixel position (0..1),
        // uTime is seconds (wrapping every 10 s), uStrength is 0..1, uSize is the frame size.
        private val BODIES: Map<ClipEffect, String> = mapOf(
            ClipEffect.VIGNETTE to """
  float d = distance(c, vec2(0.5));
  col.rgb *= 1.0 - smoothstep(0.25, 0.8, d * (0.6 + uStrength));
""",
            ClipEffect.RGB_SPLIT to """
  float a = (0.004 + 0.02 * uStrength) * (0.6 + 0.4 * sin(uTime * 12.5664));
  col.r = texture2D(uTexSampler, c + vec2(a, 0.0)).r;
  col.b = texture2D(uTexSampler, c - vec2(a, 0.0)).b;
""",
            ClipEffect.GLITCH to """
  float tick = floor(uTime * 12.0);
  float burst = step(0.55, rand(vec2(tick, 1.0)));
  float band = floor(c.y * 18.0);
  float n = rand(vec2(band, tick));
  float shift = (n - 0.5) * 0.25 * uStrength * step(0.6, n) * burst;
  vec2 p = vec2(c.x + shift, c.y);
  float a = 0.014 * uStrength * burst;
  col = texture2D(uTexSampler, p);
  col.r = texture2D(uTexSampler, p + vec2(a, 0.0)).r;
  col.b = texture2D(uTexSampler, p - vec2(a, 0.0)).b;
""",
            ClipEffect.VHS to """
  float a = 0.002 + 0.004 * uStrength;
  col.r = texture2D(uTexSampler, c + vec2(a, 0.0)).r;
  col.b = texture2D(uTexSampler, c - vec2(a, 0.0)).b;
  float scan = 0.5 + 0.5 * sin(c.y * uSize.y * 1.5);
  col.rgb *= 1.0 - 0.3 * uStrength * scan;
  float noise = rand(c * uSize * 0.5 + vec2(uTime * 7.0, uTime * 3.0)) - 0.5;
  col.rgb += noise * 0.2 * uStrength;
  float gray = dot(col.rgb, vec3(0.299, 0.587, 0.114));
  col.rgb = mix(col.rgb, vec3(gray), 0.25 * uStrength);
""",
            ClipEffect.PIXELATE to """
  float px = mix(4.0, 48.0, uStrength);
  vec2 cell = vec2(px) / uSize;
  col = texture2D(uTexSampler, (floor(c / cell) + 0.5) * cell);
""",
            ClipEffect.MIRROR to """
  col = texture2D(uTexSampler, vec2(0.5 - abs(c.x - 0.5), c.y));
""",
            ClipEffect.FLASH to """
  float phase = fract(uTime * 1.5);
  float f = pow(1.0 - phase, 6.0) * uStrength;
  col.rgb = mix(col.rgb, vec3(1.0), f * 0.85);
""",
            ClipEffect.WAVE to """
  float w = sin(c.y * 18.0 + uTime * 6.2832) * 0.025 * uStrength;
  col = texture2D(uTexSampler, vec2(clamp(c.x + w, 0.0, 1.0), c.y));
""",
        )

        private fun fragmentShaderFor(effect: ClipEffect): String =
            FRAGMENT_HEADER + BODIES.getValue(effect) + FRAGMENT_FOOTER

        private class FxShaderProgram(
            fragmentShader: String,
            private val strength: Float,
            useHdr: Boolean,
        ) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

            private val glProgram: GlProgram = try {
                GlProgram(VERTEX_SHADER, fragmentShader)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
            private var width = 1
            private var height = 1

            init {
                // Draw on the whole frame.
                glProgram.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
            }

            override fun configure(inputWidth: Int, inputHeight: Int): Size {
                width = inputWidth
                height = inputHeight
                return Size(inputWidth, inputHeight)
            }

            override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
                try {
                    glProgram.use()
                    glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                    // Uniforms a shader doesn't use are removed by the GL compiler, hence "IfPresent".
                    val seconds = (presentationTimeUs % 10_000_000L) / 1_000_000f
                    glProgram.setFloatsUniformIfPresent("uTime", floatArrayOf(seconds))
                    glProgram.setFloatsUniformIfPresent("uStrength", floatArrayOf(strength))
                    glProgram.setFloatsUniformIfPresent("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                    glProgram.bindAttributesAndUniforms()
                    // The four-vertex triangle strip forms a quad.
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
                } catch (e: GlUtil.GlException) {
                    throw VideoFrameProcessingException(e, presentationTimeUs)
                }
            }

            override fun release() {
                super.release()
                try {
                    glProgram.delete()
                } catch (e: GlUtil.GlException) {
                    throw VideoFrameProcessingException(e)
                }
            }
        }
    }
}
