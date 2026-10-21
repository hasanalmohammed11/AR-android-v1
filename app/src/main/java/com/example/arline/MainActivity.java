package com.example.arline;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity implements GLSurfaceView.Renderer {
    private static final String TAG = "ARLine";

    // إعدادات الرسم
    private static final float DRAW_DISTANCE = 0.25f; // المسافة أمام الكاميرا (متر)
    private static final float MIN_STEP = 0.004f;     // أقل مسافة بين نقطتين
    private static final float HALF_WIDTH = 0.004f;   // نصف عرض الخط

    private static final float[][] COLORS = {
            {1f, 1f, 1f, 1f}, {1f, 0.2f, 0.2f, 1f}, {0.2f, 0.9f, 0.3f, 1f},
            {0.3f, 0.6f, 1f, 1f}, {1f, 0.9f, 0.2f, 1f}, {1f, 0.4f, 0.9f, 1f}
    };
    private static final int[] COLOR_INTS = {
            Color.WHITE, Color.rgb(255, 51, 51), Color.rgb(51, 230, 77),
            Color.rgb(77, 153, 255), Color.rgb(255, 230, 51), Color.rgb(255, 102, 230)
    };

    private static class Stroke {
        final float[] color;
        float[] pts = new float[3 * 256];
        int count = 0;

        Stroke(float[] color) {
            this.color = color;
        }

        void add(float x, float y, float z) {
            if (count * 3 + 3 > pts.length) pts = Arrays.copyOf(pts, pts.length * 2);
            pts[count * 3] = x;
            pts[count * 3 + 1] = y;
            pts[count * 3 + 2] = z;
            count++;
        }
    }

    private GLSurfaceView glView;
    private TextView hintView;
    private Button colorButton;
    private volatile Session session;
    private boolean installRequested = false;

    private volatile boolean drawing = false;
    private volatile int colorIndex = 0;

    // تُستخدم على خيط OpenGL فقط
    private final ArrayList<Stroke> strokes = new ArrayList<>();
    private Stroke current = null;
    private boolean viewportChanged = false;
    private int viewWidth, viewHeight;
    private Boolean lastTracking = null;

    private int cameraTexture;
    private int bgProgram, bgPosLoc, bgTexLoc;
    private int lineProgram, linePosLoc, lineMvpLoc, lineColorLoc;
    private FloatBuffer quadCoords, quadTexCoords, vertexBuf;

    private final float[] viewMatrix = new float[16];
    private final float[] projMatrix = new float[16];
    private final float[] vpMatrix = new float[16];

    private static final String BG_VS =
            "attribute vec4 a_Position;\n" +
            "attribute vec2 a_TexCoord;\n" +
            "varying vec2 v_TexCoord;\n" +
            "void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }\n";
    private static final String BG_FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 v_TexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }\n";
    private static final String LINE_VS =
            "uniform mat4 u_MVP;\n" +
            "attribute vec3 a_Position;\n" +
            "void main() { gl_Position = u_MVP * vec4(a_Position, 1.0); }\n";
    private static final String LINE_FS =
            "precision mediump float;\n" +
            "uniform vec4 u_Color;\n" +
            "void main() { gl_FragColor = u_Color; }\n";

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);

        glView = new GLSurfaceView(this);
        glView.setPreserveEGLContextOnPause(true);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glView.setRenderer(this);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glView.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drawing = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drawing = false;
                    break;
                default:
                    break;
            }
            return true;
        });
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hintView = new TextView(this);
        hintView.setTextColor(Color.WHITE);
        hintView.setTextSize(15);
        hintView.setGravity(Gravity.CENTER);
        hintView.setBackgroundColor(Color.argb(140, 0, 0, 0));
        hintView.setPadding(dp(12), dp(10), dp(12), dp(10));
        hintView.setText("حرّك الهاتف ببطء ليتعرف على المكان...");
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        hintLp.topMargin = dp(32);
        root.addView(hintView, hintLp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(24));

        Button undo = makeButton("تراجع");
        Button clear = makeButton("مسح");
        colorButton = makeButton("اللون");
        colorButton.setTextColor(COLOR_INTS[0]);
        colorButton.setTypeface(Typeface.DEFAULT_BOLD);

        undo.setOnClickListener(v -> glView.queueEvent(() -> {
            if (!strokes.isEmpty()) {
                Stroke last = strokes.remove(strokes.size() - 1);
                if (last == current) current = null;
            }
        }));
        clear.setOnClickListener(v -> glView.queueEvent(() -> {
            strokes.clear();
            current = null;
        }));
        colorButton.setOnClickListener(v -> {
            colorIndex = (colorIndex + 1) % COLORS.length;
            colorButton.setTextColor(COLOR_INTS[colorIndex]);
        });

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        blp.setMargins(dp(4), 0, dp(4), 0);
        bar.addView(undo, blp);
        bar.addView(clear, blp);
        bar.addView(colorButton, blp);

        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        return b;
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        try {
            if (session == null) {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }
                session = new Session(this);
            }
            session.resume();
            glView.onResume();
        } catch (Throwable t) {
            Log.e(TAG, "AR start failed", t);
            Toast.makeText(this, "تعذر تشغيل الواقع المعزز: " + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (session != null) {
            glView.onPause();
            session.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (!hasCameraPermission()) {
            Toast.makeText(this, "يلزم السماح بالكاميرا لتشغيل التطبيق", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // ---------------- OpenGL ----------------

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        cameraTexture = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        bgProgram = buildProgram(BG_VS, BG_FS);
        bgPosLoc = GLES20.glGetAttribLocation(bgProgram, "a_Position");
        bgTexLoc = GLES20.glGetAttribLocation(bgProgram, "a_TexCoord");

        lineProgram = buildProgram(LINE_VS, LINE_FS);
        linePosLoc = GLES20.glGetAttribLocation(lineProgram, "a_Position");
        lineMvpLoc = GLES20.glGetUniformLocation(lineProgram, "u_MVP");
        lineColorLoc = GLES20.glGetUniformLocation(lineProgram, "u_Color");

        float[] quad = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        quadCoords = directBuffer(quad.length);
        quadCoords.put(quad).position(0);
        quadTexCoords = directBuffer(8);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        viewWidth = width;
        viewHeight = height;
        viewportChanged = true;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session s = session;
        if (s == null) return;

        try {
            if (viewportChanged) {
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                s.setDisplayGeometry(rotation, viewWidth, viewHeight);
                viewportChanged = false;
            }
            s.setCameraTextureName(cameraTexture);
            Frame frame = s.update();
            Camera camera = frame.getCamera();

            drawBackground(frame);

            boolean tracking = camera.getTrackingState() == TrackingState.TRACKING;
            updateHint(tracking);
            handleDrawing(camera, tracking);
            if (tracking) drawStrokes(camera);
        } catch (Throwable t) {
            Log.e(TAG, "draw error", t);
        }
    }

    private void drawBackground(Frame frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quadCoords.position(0);
            quadTexCoords.position(0);
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    quadCoords, Coordinates2d.TEXTURE_NORMALIZED, quadTexCoords);
        }
        if (frame.getTimestamp() == 0) return;

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
        GLES20.glUseProgram(bgProgram);

        quadCoords.position(0);
        quadTexCoords.position(0);
        GLES20.glVertexAttribPointer(bgPosLoc, 2, GLES20.GL_FLOAT, false, 0, quadCoords);
        GLES20.glVertexAttribPointer(bgTexLoc, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords);
        GLES20.glEnableVertexAttribArray(bgPosLoc);
        GLES20.glEnableVertexAttribArray(bgTexLoc);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(bgPosLoc);
        GLES20.glDisableVertexAttribArray(bgTexLoc);
        GLES20.glDepthMask(true);
    }

    private void handleDrawing(Camera camera, boolean tracking) {
        boolean wantDraw = drawing;
        if (wantDraw && tracking) {
            if (current == null) {
                current = new Stroke(COLORS[colorIndex]);
                strokes.add(current);
            }
            Pose p = camera.getPose().compose(Pose.makeTranslation(0f, 0f, -DRAW_DISTANCE));
            float x = p.tx(), y = p.ty(), z = p.tz();
            boolean add = current.count == 0;
            if (!add) {
                int i = (current.count - 1) * 3;
                float dx = x - current.pts[i], dy = y - current.pts[i + 1], dz = z - current.pts[i + 2];
                add = Math.sqrt(dx * dx + dy * dy + dz * dz) > MIN_STEP;
            }
            if (add) current.add(x, y, z);
        } else if (!wantDraw && current != null) {
            if (current.count < 2) strokes.remove(current);
            current = null;
        }
    }

    private void drawStrokes(Camera camera) {
        if (strokes.isEmpty()) return;

        camera.getViewMatrix(viewMatrix, 0);
        camera.getProjectionMatrix(projMatrix, 0, 0.01f, 100f);
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0);
        Pose cp = camera.getPose();
        float cx = cp.tx(), cy = cp.ty(), cz = cp.tz();

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(lineProgram);
        GLES20.glUniformMatrix4fv(lineMvpLoc, 1, false, vpMatrix, 0);
        GLES20.glEnableVertexAttribArray(linePosLoc);

        for (Stroke s : strokes) {
            int n = s.count;
            if (n < 2) continue;
            int floats = n * 2 * 3;
            if (vertexBuf == null || vertexBuf.capacity() < floats) {
                vertexBuf = directBuffer(Math.max(floats, 3 * 2 * 512));
            }
            vertexBuf.clear();
            for (int i = 0; i < n; i++) {
                float px = s.pts[i * 3], py = s.pts[i * 3 + 1], pz = s.pts[i * 3 + 2];
                int a = Math.max(i - 1, 0), b = Math.min(i + 1, n - 1);
                float tx = s.pts[b * 3] - s.pts[a * 3];
                float ty = s.pts[b * 3 + 1] - s.pts[a * 3 + 1];
                float tz = s.pts[b * 3 + 2] - s.pts[a * 3 + 2];
                float vx = cx - px, vy = cy - py, vz = cz - pz;
                float nx = ty * vz - tz * vy;
                float ny = tz * vx - tx * vz;
                float nz = tx * vy - ty * vx;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len < 1e-8f) {
                    nx = 0f; ny = HALF_WIDTH; nz = 0f;
                } else {
                    float k = HALF_WIDTH / len;
                    nx *= k; ny *= k; nz *= k;
                }
                vertexBuf.put(px + nx).put(py + ny).put(pz + nz);
                vertexBuf.put(px - nx).put(py - ny).put(pz - nz);
            }
            vertexBuf.position(0);
            GLES20.glVertexAttribPointer(linePosLoc, 3, GLES20.GL_FLOAT, false, 0, vertexBuf);
            GLES20.glUniform4fv(lineColorLoc, 1, s.color, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, n * 2);
        }
        GLES20.glDisableVertexAttribArray(linePosLoc);
    }

    private void updateHint(boolean tracking) {
        if (lastTracking != null && lastTracking == tracking) return;
        lastTracking = tracking;
        final String msg = tracking
                ? "اضغط بإصبعك على الشاشة وحرّك الهاتف لترسم في الهواء"
                : "حرّك الهاتف ببطء ليتعرف على المكان...";
        runOnUiThread(() -> hintView.setText(msg));
    }

    private static FloatBuffer directBuffer(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Link error: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Shader error: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
