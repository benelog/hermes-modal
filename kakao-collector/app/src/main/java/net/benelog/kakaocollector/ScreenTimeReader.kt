package net.benelog.kakaocollector

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/**
 * 현재 화면을 캡처([AccessibilityService.takeScreenshot], API 30+)해 기기 내 OCR(ML Kit 한국어,
 * 모델 번들 — 네트워크 불필요)로 텍스트 줄과 좌표를 읽는다. 시각 라벨 선별은 [TimeLabels].
 * [isStill]은 캡처 직후 main 스레드에서 불려 '캡처한 화면 == 호출자가 가진 스냅샷 배치'인지 확인한다 —
 * false면(그 사이 스크롤) 라벨 좌표를 쓸 수 없으니 OCR 없이 null. OCR은 수백 ms 걸려 끝난 뒤에
 * 확인하면 이미 다음 스와이프가 지나간다(2026-09-26 실측: 끝난 뒤 확인 시 9프레임 중 8개 폐기).
 * [onResult]는 main 스레드에서 한 번 불리며, 실패(구버전/화면 이동/OCR 오류)면 null — 호출자는
 * 시각 없이 수집을 이어간다(시각은 dedupe 키가 아니라 나중 재수집에서 채워진다).
 *
 * 시스템은 캡처 간격을 약 333ms로 제한한다(더 빠르면 ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT).
 * 수집 레인 여럿(스크롤·새 메시지)이 거의 동시에 요청하므로 실패시키지 않고 간격이 찰 때까지
 * 미뤄서 캡처한다 — 호출자가 캡처 뒤 배치 변화를 다시 확인하므로 조금 늦어도 안전하다.
 */
class ScreenTimeReader(private val service: AccessibilityService) {
    companion object {
        private const val MIN_CAPTURE_INTERVAL_MS = 350L
        // 시스템은 간격을 자기 쪽 처리 시각으로 재서 350ms 간격에도 가끔 거절한다 → 한 번만 다시 시도.
        private const val INTERVAL_RETRY_DELAY_MS = 400L
    }

    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val handler = Handler(Looper.getMainLooper())
    private var nextCaptureAt = 0L // main 스레드 전용

    fun read(isStill: () -> Boolean, onResult: (List<TimeLabels.Line>?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        val now = SystemClock.uptimeMillis()
        val at = maxOf(now, nextCaptureAt)
        nextCaptureAt = at + MIN_CAPTURE_INTERVAL_MS
        handler.postAtTime({ capture(isStill, onResult, retryOnInterval = true) }, at)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun capture(
        isStill: () -> Boolean,
        onResult: (List<TimeLabels.Line>?) -> Unit,
        retryOnInterval: Boolean,
    ) {
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    if (!isStill()) {
                        result.hardwareBuffer.close()
                        onResult(null)
                        return
                    }
                    val bitmap = result.hardwareBuffer.use { buffer ->
                        // ML Kit은 하드웨어 비트맵을 못 읽는다 → 소프트웨어 사본.
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hw ->
                            hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                        }
                    }
                    if (bitmap == null) {
                        onResult(null)
                        return
                    }
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { text ->
                            onResult(
                                text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                    line.boundingBox?.let { b ->
                                        TimeLabels.Line(line.text, b.left, b.top, b.right, b.bottom)
                                    }
                                },
                            )
                        }
                        .addOnFailureListener { e ->
                            Log.w(KakaoCollectorService.TAG, "time OCR failed: ${e.message}")
                            onResult(null)
                        }
                        .addOnCompleteListener { bitmap.recycle() }
                }

                override fun onFailure(errorCode: Int) {
                    if (retryOnInterval && errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        nextCaptureAt = SystemClock.uptimeMillis() + INTERVAL_RETRY_DELAY_MS + MIN_CAPTURE_INTERVAL_MS
                        handler.postDelayed({ capture(isStill, onResult, retryOnInterval = false) }, INTERVAL_RETRY_DELAY_MS)
                        return
                    }
                    // 드문 실패(보안 창 등) — 이번 프레임은 시각 없이 수집.
                    Log.i(KakaoCollectorService.TAG, "screenshot failed code=$errorCode")
                    onResult(null)
                }
            },
        )
    }
}
