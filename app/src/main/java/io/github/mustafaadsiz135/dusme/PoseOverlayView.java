package io.github.mustafaadsiz135.dusme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

/** Kamera goruntusunun ustune yapay zekanin buldugu iskeleti cizer. */
public class PoseOverlayView extends View {

    private static final int[][] BONES = {
            {PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER},
            {PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP},
            {PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP},
            {PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP},
            {PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW},
            {PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST},
            {PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW},
            {PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST},
            {PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE},
            {PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE},
            {PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE},
            {PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE},
            {PoseLandmark.NOSE, PoseLandmark.LEFT_SHOULDER},
            {PoseLandmark.NOSE, PoseLandmark.RIGHT_SHOULDER},
    };

    private final Paint bone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint joint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Pose pose;
    private int imgW, imgH;

    public PoseOverlayView(Context c, AttributeSet a) {
        super(c, a);
        bone.setStrokeWidth(8f);
        bone.setStrokeCap(Paint.Cap.ROUND);
        joint.setColor(Color.WHITE);
        setColor(Color.parseColor("#64B5F6"));
    }

    public void setColor(int color) {
        bone.setColor(color);
        invalidate();
    }

    /** imgW/imgH: dik (dondurulmus) goruntu boyutu. pose=null ise temizler. */
    public void setPose(Pose pose, int imgW, int imgH) {
        this.pose = pose;
        this.imgW = imgW;
        this.imgH = imgH;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pose == null || imgW <= 0 || imgH <= 0) return;
        // PreviewView FILL_CENTER ile ayni olcekleme
        float scale = Math.max(getWidth() / (float) imgW, getHeight() / (float) imgH);
        float ox = (getWidth() - imgW * scale) / 2f;
        float oy = (getHeight() - imgH * scale) / 2f;

        for (int[] b : BONES) {
            PoseLandmark a = pose.getPoseLandmark(b[0]);
            PoseLandmark c = pose.getPoseLandmark(b[1]);
            if (!ok(a) || !ok(c)) continue;
            PointF pa = a.getPosition(), pc = c.getPosition();
            canvas.drawLine(ox + pa.x * scale, oy + pa.y * scale, ox + pc.x * scale, oy + pc.y * scale, bone);
        }
        for (PoseLandmark lm : pose.getAllPoseLandmarks()) {
            if (!ok(lm)) continue;
            PointF p = lm.getPosition();
            canvas.drawCircle(ox + p.x * scale, oy + p.y * scale, 7f, joint);
        }
    }

    private static boolean ok(PoseLandmark lm) {
        return lm != null && lm.getInFrameLikelihood() >= 0.5f;
    }
}
