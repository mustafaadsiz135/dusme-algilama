package io.github.mustafaadsiz135.dusme;

import android.graphics.PointF;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

/**
 * Yapay zekanin (ML Kit) verdigi iskelet noktalarindan dusme karari verir.
 *
 * Mantik:
 *  1) Her karede omuz ortasi ile kalca ortasi arasindaki govde cizgisinin dikeyle yaptigi aci hesaplanir.
 *     0 derece = dik duruyor, 90 derece = yatay (yerde yatiyor).
 *  2) Kisi kisa sure once (2 sn icinde) DIK iken hizla YATAY konuma gectiyse ve kalcasi asagi indiyse
 *     bu bir DUSME sayilir. Yavasca uzanmak (yataga yatmak gibi) genelde tetiklemez.
 *  3) Dusmeden sonra kisi yeniden 0,8 sn boyunca dik gorulurse "kalkti" kabul edilir.
 */
public class FallDetector {

    public enum Posture { UNKNOWN, UPRIGHT, LYING, OTHER }

    // --- Ayar degerleri ---
    private static final float MIN_LIKELIHOOD = 0.5f;
    private static final float LYING_ANGLE = 60f;         // bu acidan buyukse yatiyor
    private static final float LYING_ANGLE_SOFT = 45f;    // genis govde kutusuyla birlikte yatiyor
    private static final float UPRIGHT_ANGLE = 30f;       // bu acidan kucukse dik
    private static final long  FAST_TRANSITION_MS = 2000; // dikten yataya gecis bu surede olmali
    private static final long  LYING_CONFIRM_MS = 400;    // yatay durum bu kadar surmeli
    private static final long  FLICKER_TOLERANCE_MS = 350;
    private static final long  STAND_CONFIRM_MS = 800;    // kalkti saymak icin dik kalma suresi
    private static final float MIN_HIP_DROP = 0.05f;      // kalca, kare yuksekliginin %5'i kadar inmeli
    private static final long  HISTORY_MS = 3500;

    private static class Sample {
        final long t; final Posture p; final float hipY;
        Sample(long t, Posture p, float hipY) { this.t = t; this.p = p; this.hipY = hipY; }
    }

    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private long lyingSince = -1;
    private long lastLyingSeen = -1;
    private long uprightSince = -1;
    private long lastUprightSeen = -1;
    private boolean fallReported = false;

    // Son karenin bilgileri (ekranda gostermek icin)
    public float lastAngle = Float.NaN;
    public float lastHipY = Float.NaN;
    public Posture lastPosture = Posture.UNKNOWN;

    /** Iskeletten durus sinifini cikarir. w,h: dondurulmus (dik) kare boyutu. */
    public Posture classify(Pose pose, int w, int h) {
        PointF sh = mid(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
        PointF hip = mid(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP);
        if (sh == null || hip == null || w <= 0 || h <= 0) {
            lastPosture = Posture.UNKNOWN;
            lastAngle = Float.NaN;
            return Posture.UNKNOWN;
        }
        float dx = sh.x - hip.x;
        float dy = sh.y - hip.y; // ekran koordinatinda y asagi dogru artar
        float len = (float) Math.hypot(dx, dy);
        if (len < 0.04f * Math.min(w, h)) {
            lastPosture = Posture.UNKNOWN;
            lastAngle = Float.NaN;
            return Posture.UNKNOWN;
        }
        float angle = (float) Math.toDegrees(Math.atan2(Math.abs(dx), -dy)); // 0..180

        // Tum guvenilir noktalarin kutusu: yatan insanin kutusu genis olur
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int n = 0;
        List<PoseLandmark> all = pose.getAllPoseLandmarks();
        for (PoseLandmark lm : all) {
            if (lm.getInFrameLikelihood() < MIN_LIKELIHOOD) continue;
            PointF p = lm.getPosition();
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
            n++;
        }
        float aspect = 1f;
        if (n >= 4 && maxY - minY > 1f) aspect = (maxX - minX) / (maxY - minY);

        Posture p;
        if (angle >= LYING_ANGLE || (angle >= LYING_ANGLE_SOFT && aspect >= 1.3f)) p = Posture.LYING;
        else if (angle <= UPRIGHT_ANGLE && aspect < 1.0f) p = Posture.UPRIGHT;
        else p = Posture.OTHER;

        lastAngle = angle;
        lastHipY = hip.y / h;
        lastPosture = p;
        return p;
    }

    /**
     * Her kare icin cagrilir. Yeni bir dusme algilandiginda true dondurur.
     * Kisi kadrajda yoksa (UNKNOWN) durum degismez.
     */
    public boolean onFrame(Posture p, long now) {
        if (p == Posture.UNKNOWN) return false;
        float hipY = lastHipY;

        history.addLast(new Sample(now, p, hipY));
        while (!history.isEmpty() && now - history.peekFirst().t > HISTORY_MS) history.removeFirst();

        // Dik durma takibi
        if (p == Posture.UPRIGHT) {
            if (uprightSince < 0) uprightSince = now;
            lastUprightSeen = now;
        } else if (lastUprightSeen < 0 || now - lastUprightSeen > FLICKER_TOLERANCE_MS) {
            uprightSince = -1;
        }

        // Yatma takibi
        if (p == Posture.LYING) {
            if (lyingSince < 0) lyingSince = now;
            lastLyingSeen = now;
        } else if (lastLyingSeen < 0 || now - lastLyingSeen > FLICKER_TOLERANCE_MS) {
            lyingSince = -1;
        }

        if (fallReported || lyingSince < 0 || now - lyingSince < LYING_CONFIRM_MS) return false;

        // Yatmadan hemen once dik miydi ve kalca asagi indi mi?
        Sample lastUpright = null;
        Iterator<Sample> it = history.iterator();
        while (it.hasNext()) {
            Sample s = it.next();
            if (s.t > lyingSince) break;
            if (s.p == Posture.UPRIGHT && lyingSince - s.t <= FAST_TRANSITION_MS) lastUpright = s;
        }
        if (lastUpright == null) return false;
        if (!Float.isNaN(hipY) && !Float.isNaN(lastUpright.hipY)
                && hipY - lastUpright.hipY < MIN_HIP_DROP) return false;

        fallReported = true;
        return true;
    }

    /** Dusmeden sonra kisi tekrar ayaga kalkti mi? */
    public boolean isStandingUp(long now) {
        return uprightSince >= 0 && now - uprightSince >= STAND_CONFIRM_MS;
    }

    /** Uyari bitince (iptal / arama) sifirlanir; yeniden dusme icin once dik durus gerekir. */
    public void reset() {
        history.clear();
        lyingSince = -1;
        lastLyingSeen = -1;
        uprightSince = -1;
        lastUprightSeen = -1;
        fallReported = false;
    }

    private static PointF mid(Pose pose, int a, int b) {
        PoseLandmark la = pose.getPoseLandmark(a);
        PoseLandmark lb = pose.getPoseLandmark(b);
        boolean okA = la != null && la.getInFrameLikelihood() >= MIN_LIKELIHOOD;
        boolean okB = lb != null && lb.getInFrameLikelihood() >= MIN_LIKELIHOOD;
        if (okA && okB) {
            PointF pa = la.getPosition(), pb = lb.getPosition();
            return new PointF((pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f);
        }
        if (okA) return new PointF(la.getPosition().x, la.getPosition().y);
        if (okB) return new PointF(lb.getPosition().x, lb.getPosition().y);
        return null;
    }
}
