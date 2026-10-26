package local.tv.bluehome;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Original ES 2.0 ribbon background: small meshes, no textures or network assets. */
public final class WaveView extends GLSurfaceView {
    private boolean active;
    private boolean resumed;
    private boolean attached;
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!active) return;
            requestRender();
            postDelayed(this, 33); // Background at 30fps; focus animation has its own vsync.
        }
    };

    public WaveView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 0, 0, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(new RibbonRenderer());
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override public void onResume() {
        super.onResume();
        resumed = true;
        updateFrames();
    }

    @Override public void onPause() {
        resumed = false;
        updateFrames();
        super.onPause();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateFrames();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (frame != null) updateFrames();
    }

    @Override protected void onVisibilityChanged(android.view.View view, int visibility) {
        super.onVisibilityChanged(view, visibility);
        if (frame != null) updateFrames();
    }

    private void updateFrames() {
        active = resumed && attached && getWindowVisibility() == VISIBLE && isShown();
        removeCallbacks(frame);
        if (active) post(frame);
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        updateFrames();
        super.onDetachedFromWindow();
    }

    private static final class RibbonRenderer implements GLSurfaceView.Renderer {
        private static final int NX = 96, NY = 14, STARS = 32;
        private final FloatBuffer quad = floats(new float[] {-1,-1, 1,-1, -1,1, 1,1});
        private final FloatBuffer mesh;
        private final ShortBuffer indices;
        private final FloatBuffer stars;
        private final int indexCount = NX * NY * 6;
        private int background, ribbon, sparkle;
        private int bgPosition, bgAtmosphere, ribbonPosition, ribbonTime, ribbonLayer, starPosition, starTime;
        private final long epoch = SystemClock.uptimeMillis();
        private boolean ready;

        RibbonRenderer() {
            float[] vertices = new float[(NX + 1) * (NY + 1) * 2];
            int at = 0;
            for (int y = 0; y <= NY; y++) for (int x = 0; x <= NX; x++) {
                vertices[at++] = x / (float) NX;
                vertices[at++] = y / (float) NY * 2f - 1f;
            }
            mesh = floats(vertices);
            short[] cells = new short[indexCount];
            at = 0;
            for (int y = 0; y < NY; y++) for (int x = 0; x < NX; x++) {
                short a = (short) (y * (NX + 1) + x), b = (short) (a + 1);
                short c = (short) (a + NX + 1), d = (short) (c + 1);
                cells[at++] = a; cells[at++] = b; cells[at++] = c;
                cells[at++] = c; cells[at++] = b; cells[at++] = d;
            }
            indices = ByteBuffer.allocateDirect(cells.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            indices.put(cells).position(0);
            float[] dust = new float[STARS * 3];
            int seed = 0x42716;
            for (int i = 0; i < dust.length; i++) {
                seed = seed * 1664525 + 1013904223;
                dust[i] = ((seed >>> 8) & 0xffff) / 65535f;
            }
            stars = floats(dust);
        }

        @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            GLES20.glClearColor(0.005f, 0.18f, 0.48f, 1f);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            try {
                background = program(
                    "attribute vec2 aPosition; varying vec2 uv; void main(){uv=aPosition*.5+.5;gl_Position=vec4(aPosition,0.,1.);}",
                    "precision mediump float; varying vec2 uv; uniform vec4 uAtmosphere; void main(){" +
                    "vec3 midnight=vec3(.0,.043,.129), royal=vec3(.014,.185,.49);" +
                    "float depth=1.-uv.y; vec3 col=mix(midnight,royal,smoothstep(.02,.94,depth));" +
                    "vec2 p=(uv-vec2(uAtmosphere.x,.34))*vec2(1.65,1.7); float light=exp(-dot(p,p)*2.4);" +
                    "vec2 q=(uv-vec2(.84,uAtmosphere.y))*vec2(2.4,2.0); float halo=exp(-dot(q,q)*2.);" +
                    "col+=vec3(.009,.070,.16)*light*uAtmosphere.z+vec3(.006,.032,.074)*halo;" +
                    "float vignette=clamp(1.-.35*dot((uv-.5)*vec2(1.,.65),(uv-.5)*vec2(1.,.65)),.72,1.);" +
                    "gl_FragColor=vec4(col*vignette,1.);}");
                ribbon = program(
                    "attribute vec2 aPosition; uniform float uTime; uniform mediump float uLayer; varying vec2 uv; varying float fresnel; varying float lighting;" +
                    "vec3 surface(float u,float v){float t=uTime,k=uLayer;" +
                    "float phase=u*3.2-.65+t*.042+k*.48;" +
                    "float center=-.20+.245*sin(phase)+.065*sin(u*6.7-t*.055+k*1.1)+k*.018;" +
                    "float width=.205+.058*cos(u*4.5-t*.04+k*.36);" +
                    "float twist=.92*sin(u*3.15+t*.055+k*.44)+k*.17;" +
                    "return vec3(u*2.6-1.3,center+v*width*cos(twist)+v*v*.022*sin(phase*1.8),v*width*sin(twist));}" +
                    "void main(){float u=aPosition.x,v=aPosition.y;uv=vec2(u,v);vec3 p=surface(u,v);" +
                    "vec3 normal=normalize(cross(surface(u+.001,v)-p,surface(u,v+.001)-p));" +
                    "float facing=clamp(abs(normal.z),0.,1.);float rim=1.-facing;fresnel=rim*rim*rim;" +
                    "lighting=.45+.55*abs(dot(normal,normalize(vec3(-.2,.5,1.))));" +
                    "gl_Position=vec4(p.xy/(1.+p.z*.18),0.,1.);}",
                    "precision mediump float; varying vec2 uv; varying float fresnel; varying float lighting; uniform mediump float uLayer; void main(){" +
                    "float v=uv.y;float fade=1.-smoothstep(.82,1.,abs(v));" +
                    "float foldDistance=(v+.10)*3.8;float fold=exp(-foldDistance*foldDistance);" +
                    "float edgeDistance=(abs(v)-.72)*10.;float rim=exp(-edgeDistance*edgeDistance)*fresnel;" +
                    "float alpha=(.052+.060*fold*lighting+.095*fresnel+.055*rim)*fade;" +
                    "alpha*=.72+uLayer*.075;" +
                    "vec3 tint=mix(vec3(.095,.32,.68),vec3(.42,.72,1.),clamp(fresnel*.7+fold*.20,0.,1.));" +
                    "gl_FragColor=vec4(tint,alpha);}");
                sparkle = program(
                    "attribute vec3 aPosition; uniform float uTime; varying float brightness; void main(){" +
                    "vec2 p=aPosition.xy; p.x=fract(p.x+uTime*(.001+.0007*aPosition.z));" +
                    "p.y=fract(p.y+uTime*.0008+.012*sin(uTime*.06+aPosition.z*9.));gl_Position=vec4(p*2.-1.,0.,1.);" +
                    "gl_PointSize=3.+aPosition.z*aPosition.z*17.;brightness=.022+.024*(.5+.5*sin(uTime*.12+aPosition.z*20.));}",
                    "precision mediump float; varying float brightness; void main(){vec2 p=gl_PointCoord-.5;" +
                    "float a=exp(-dot(p,p)*19.)*brightness;gl_FragColor=vec4(.57,.77,1.,a);}");
                bgPosition = GLES20.glGetAttribLocation(background, "aPosition");
                bgAtmosphere = GLES20.glGetUniformLocation(background, "uAtmosphere");
                ribbonPosition = GLES20.glGetAttribLocation(ribbon, "aPosition");
                ribbonTime = GLES20.glGetUniformLocation(ribbon, "uTime");
                ribbonLayer = GLES20.glGetUniformLocation(ribbon, "uLayer");
                starPosition = GLES20.glGetAttribLocation(sparkle, "aPosition");
                starTime = GLES20.glGetUniformLocation(sparkle, "uTime");
                ready = true;
                Log.i("BlueHomeWave", "ES2 ribbon ready");
            } catch (RuntimeException error) {
                Log.w("BlueHomeWave", "Shader initialization failed; using solid blue", error);
                ready = false; // A solid blue home remains usable if this GPU rejects a shader.
            }
        }

        @Override public void onSurfaceChanged(GL10 unused, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
        }

        @Override public void onDrawFrame(GL10 unused) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (!ready) return;
            float time = (SystemClock.uptimeMillis() - epoch) / 1000f;
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(background);
            GLES20.glUniform4f(bgAtmosphere, .30f+.035f*(float)Math.sin(time*.055f), .22f+.03f*(float)Math.sin(time*.041f), .94f+.06f*(float)Math.sin(time*.07f), 0f);
            bind(bgPosition, 2, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(bgPosition);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(ribbon);
            GLES20.glUniform1f(ribbonTime, time);
            bind(ribbonPosition, 2, mesh);
            for (int layer = 3; layer >= 0; layer--) {
                GLES20.glUniform1f(ribbonLayer, layer);
                indices.position(0);
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indices);
            }
            GLES20.glDisableVertexAttribArray(ribbonPosition);
            GLES20.glUseProgram(sparkle);
            GLES20.glUniform1f(starTime, time);
            bind(starPosition, 3, stars);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, STARS);
            GLES20.glDisableVertexAttribArray(starPosition);
        }

        private static void bind(int location, int size, FloatBuffer buffer) {
            buffer.position(0);
            GLES20.glEnableVertexAttribArray(location);
            GLES20.glVertexAttribPointer(location, size, GLES20.GL_FLOAT, false, 0, buffer);
        }

        private static int shader(int type, String source) {
            int id = GLES20.glCreateShader(type);
            GLES20.glShaderSource(id, source);
            GLES20.glCompileShader(id);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(id);
                GLES20.glDeleteShader(id);
                throw new IllegalStateException(log);
            }
            return id;
        }

        private static int program(String vertexSource, String fragmentSource) {
            int vertex = shader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int id = GLES20.glCreateProgram();
            GLES20.glAttachShader(id, vertex);
            GLES20.glAttachShader(id, fragment);
            GLES20.glLinkProgram(id);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(id);
                GLES20.glDeleteProgram(id);
                throw new IllegalStateException(log);
            }
            return id;
        }

        private static FloatBuffer floats(float[] data) {
            FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            buffer.put(data).position(0);
            return buffer;
        }
    }
}
