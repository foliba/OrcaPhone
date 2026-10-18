package com.orcaphone.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {
    fun program(vs: String, fs: String): Int {
        val v = shader(GLES30.GL_VERTEX_SHADER, vs)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            GLES30.glDeleteProgram(p)
            throw IllegalStateException("Program link failed: $log")
        }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            Log.e("OrcaPhoneGL", "Shader compile failed: $log\n$src")
            GLES30.glDeleteShader(s)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return s
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }

    fun createBuffer(): Int {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        return ids[0]
    }

    fun deleteBuffer(id: Int) {
        if (id != 0) GLES30.glDeleteBuffers(1, intArrayOf(id), 0)
    }
}

object Shaders {
    const val MESH_VS = """#version 300 es
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_nrm;
uniform mat4 u_vp;
uniform mat4 u_model;
uniform mat3 u_nrm;
out vec3 v_nrm;
out vec3 v_world;
void main() {
    vec4 w = u_model * vec4(a_pos, 1.0);
    v_world = w.xyz;
    v_nrm = u_nrm * a_nrm;
    gl_Position = u_vp * w;
}
"""

    const val MESH_FS = """#version 300 es
precision mediump float;
in vec3 v_nrm;
in vec3 v_world;
uniform vec3 u_color;
uniform vec3 u_eye;
uniform float u_overhangCos;
uniform float u_alpha;
out vec4 o_color;
void main() {
    vec3 n = normalize(v_nrm);
    vec3 v = normalize(u_eye - v_world);
    if (dot(n, v) < 0.0) n = -n;
    vec3 l1 = normalize(vec3(-0.35, -0.55, 0.9));
    vec3 l2 = normalize(vec3(0.6, 0.45, 0.25));
    vec3 c = u_color;
    if (n.z < u_overhangCos && v_world.z > 0.08) c = mix(c, vec3(0.92, 0.18, 0.12), 0.8);
    float d = 0.32 + 0.6 * max(dot(n, l1), 0.0) + 0.22 * max(dot(n, l2), 0.0);
    float s = pow(max(dot(n, normalize(l1 + v)), 0.0), 40.0) * 0.18;
    o_color = vec4(c * d + vec3(s), u_alpha);
}
"""

    const val FLAT_VS = """#version 300 es
layout(location = 0) in vec3 a_pos;
uniform mat4 u_vp;
void main() { gl_Position = u_vp * vec4(a_pos, 1.0); }
"""

    const val FLAT_FS = """#version 300 es
precision mediump float;
uniform vec4 u_color;
out vec4 o_color;
void main() { o_color = u_color; }
"""

    /**
     * Instanced extrusion prisms: each instance is a segment (p -> q) drawn as a two-faced ridge
     * whose shading fakes a round extrusion. Feature visibility is a bit mask uniform.
     */
    const val PATH_VS = """#version 300 es
layout(location = 0) in vec2 a_tpl;   // x: along (0/1), y: side (-1, 0, 1)
layout(location = 1) in vec3 i_p;
layout(location = 2) in vec3 i_q;
layout(location = 3) in vec2 i_wh;
layout(location = 4) in vec4 i_attr;  // feature/255, speed, -, -
uniform mat4 u_vp;
uniform int u_mask;
uniform int u_colorMode;
uniform vec3 u_palette[16];
uniform float u_dimBelow;  // instances below this index are dimmed (current-layer highlight)
out vec3 v_color;
out float v_side;
vec3 speedColor(float t) {
    vec3 a = vec3(0.16, 0.36, 0.92);
    vec3 b = vec3(0.10, 0.78, 0.55);
    vec3 c = vec3(0.98, 0.84, 0.18);
    vec3 d = vec3(0.92, 0.22, 0.16);
    if (t < 0.33) return mix(a, b, t / 0.33);
    if (t < 0.66) return mix(b, c, (t - 0.33) / 0.33);
    return mix(c, d, (t - 0.66) / 0.34);
}
void main() {
    int feature = int(i_attr.x * 255.0 + 0.5);
    if (((u_mask >> feature) & 1) == 0) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        v_color = vec3(0.0); v_side = 0.0;
        return;
    }
    vec3 d = i_q - i_p;
    float len = length(d.xy);
    vec2 dir = len > 1e-6 ? d.xy / len : vec2(1.0, 0.0);
    vec2 n = vec2(-dir.y, dir.x);
    float hw = i_wh.x * 0.5;
    vec3 base = mix(i_p, i_q, a_tpl.x);
    base.xy += dir * (a_tpl.x * 2.0 - 1.0) * min(hw * 0.5, len * 0.5 + hw * 0.25);
    float side = a_tpl.y;
    vec3 pos = base + vec3(n * hw * side, -abs(side) * i_wh.y * 0.5);
    gl_Position = u_vp * vec4(pos, 1.0);
    v_side = side;
    vec3 col = u_colorMode == 0 ? u_palette[feature] : speedColor(i_attr.y);
    if (float(gl_InstanceID) < u_dimBelow) col *= 0.55;
    v_color = col;
}
"""

    const val PATH_FS = """#version 300 es
precision mediump float;
in vec3 v_color;
in float v_side;
out vec4 o_color;
void main() {
    float s = clamp(1.0 - v_side * v_side, 0.0, 1.0);
    float shade = 0.42 + 0.58 * sqrt(s);
    o_color = vec4(v_color * shade, 1.0);
}
"""
}
