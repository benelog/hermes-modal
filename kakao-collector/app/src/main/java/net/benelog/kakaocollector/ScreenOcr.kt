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
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/**
 * 현재 화면을 캡처([AccessibilityService.takeScreenshot], API 30+)해 기기 내 OCR(ML Kit 한국어,
 * 모델 번들 — 네트워크 불필요)로 읽는다.
 *  - 전체 화면 줄+좌표: 시각 라벨용([TimeLabels]).
 *  - 사진 영역별 글자: 사진마다 잘라서 따로 OCR([ImageTexts]) — 같은 픽셀이면 같은 결과가 나와
 *    본문 키가 안정된다. 호출자가 처리를 끝낸 사진(픽셀 지문, [skipImage])은 다시 읽지 않는다.
 *
 * [isStill]은 캡처 직후 main 스레드에서 불려 '캡처한 화면 == 호출자가 가진 스냅샷 배치'인지 확인한다 —
 * false면(그 사이 스크롤) 좌표를 쓸 수 없으니 OCR 없이 null. OCR은 수백 ms 걸려 끝난 뒤에
 * 확인하면 이미 다음 스와이프가 지나간다(2026-09-26 실측: 끝난 뒤 확인 시 9프레임 중 8개 폐기).
 * [onResult]는 main 스레드에서 한 번 불리며, 실패(구버전/화면 이동/OCR 오류)면 null — 호출자는
 * 시각·사진 글자 없이 수집을 이어간다(나중 재수집에서 채워진다).
 *
 * 시스템은 캡처 간격을 약 333ms로 제한한다(더 빠르면 ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT).
 * 수집 레인 여럿(스크롤·새 메시지)이 거의 동시에 요청하므로 실패시키지 않고 간격이 찰 때까지
 * 미뤄서 캡처한다 — 호출자가 캡처 순간 배치를 확인하므로 조금 늦어도 안전하다.
 */
class ScreenOcr(private val service: AccessibilityService) {
    companion object {
        private const val MIN_CAPTURE_INTERVAL_MS = 350L
        // 시스템은 간격을 자기 쪽 처리 시각으로 재서 350ms 간격에도 가끔 거절한다 → 한 번만 다시 시도.
        private const val INTERVAL_RETRY_DELAY_MS = 400L
    }

    /** 사진 한 장의 OCR 결과: 픽셀 지문(처리 완료 표시용) + 줄들. */
    class ImageText(val fingerprint: String, val lines: List<String>)

    /**
     * [lines]: 전체 화면 OCR 줄(요청 안 했으면 null). [imageTexts]: 요청한 사진 영역 인덱스 → OCR 결과
     * (처리 완료된 사진은 빠진다).
     */
    class Result(val lines: List<TimeLabels.Line>?, val imageTexts: Map<Int, ImageText>)

    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val handler = Handler(Looper.getMainLooper())
    private var nextCaptureAt = 0L // main 스레드 전용

    fun read(
        isStill: () -> Boolean,
        fullScreen: Boolean,
        images: List<FrameAssembler.Bubble>,
        skipImage: (String) -> Boolean,
        onResult: (Result?) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        val now = SystemClock.uptimeMillis()
        val at = maxOf(now, nextCaptureAt)
        nextCaptureAt = at + MIN_CAPTURE_INTERVAL_MS
        handler.postAtTime({ capture(isStill, fullScreen, images, skipImage, onResult, retryOnInterval = true) }, at)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun capture(
        isStill: () -> Boolean,
        fullScreen: Boolean,
        images: List<FrameAssembler.Bubble>,
        skipImage: (String) -> Boolean,
        onResult: (Result?) -> Unit,
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
                    recognize(bitmap, fullScreen, images, skipImage, onResult)
                }

                override fun onFailure(errorCode: Int) {
                    if (retryOnInterval && errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        nextCaptureAt = SystemClock.uptimeMillis() + INTERVAL_RETRY_DELAY_MS + MIN_CAPTURE_INTERVAL_MS
                        handler.postDelayed(
                            { capture(isStill, fullScreen, images, skipImage, onResult, retryOnInterval = false) },
                            INTERVAL_RETRY_DELAY_MS,
                        )
                        return
                    }
                    // 드문 실패(보안 창 등) — 이번 프레임은 OCR 없이 수집.
                    Log.i(KakaoCollectorService.TAG, "screenshot failed code=$errorCode")
                    onResult(null)
                }
            },
        )
    }

    /** 사진 영역(아직 안 읽은 것만)과 전체 화면을 OCR해 한 번에 돌려준다. */
    private fun recognize(
        bitmap: Bitmap,
        fullScreen: Boolean,
        images: List<FrameAssembler.Bubble>,
        skipImage: (String) -> Boolean,
        onResult: (Result?) -> Unit,
    ) {
        class Crop(val index: Int, val bitmap: Bitmap, val fingerprint: String)
        val crops = images.mapIndexedNotNull { i, img ->
            val c = crop(bitmap, img) ?: return@mapIndexedNotNull null
            val fp = fingerprint(c)
            if (skipImage(fp)) {
                c.recycle()
                null
            } else {
                Crop(i, c, fp)
            }
        }
        val imageTasks: List<Task<Text>> = crops.map { recognizer.process(InputImage.fromBitmap(it.bitmap, 0)) }
        val fullTask: Task<Text>? = if (fullScreen) recognizer.process(InputImage.fromBitmap(bitmap, 0)) else null
        Tasks.whenAllComplete(imageTasks + listOfNotNull(fullTask))
            .addOnCompleteListener(service.mainExecutor) {
                val imageTexts = crops.indices.mapNotNull { k ->
                    val task = imageTasks[k]
                    if (!task.isSuccessful) return@mapNotNull null
                    crops[k].index to ImageText(crops[k].fingerprint, task.result.textBlocks.flatMap { b -> b.lines.map { it.text } })
                }.toMap()
                val lines = fullTask?.let { task ->
                    if (!task.isSuccessful) {
                        Log.w(KakaoCollectorService.TAG, "screen OCR failed: ${task.exception?.message}")
                        null
                    } else {
                        task.result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                            line.boundingBox?.let { b -> TimeLabels.Line(line.text, b.left, b.top, b.right, b.bottom) }
                        }
                    }
                }
                crops.forEach { it.bitmap.recycle() }
                bitmap.recycle()
                onResult(if (fullScreen && lines == null && imageTexts.isEmpty()) null else Result(lines, imageTexts))
            }
    }

    private fun crop(bitmap: Bitmap, box: FrameAssembler.Bubble): Bitmap? {
        val left = box.left.coerceIn(0, bitmap.width)
        val top = box.top.coerceIn(0, bitmap.height)
        val right = box.right.coerceIn(left, bitmap.width)
        val bottom = box.bottom.coerceIn(top, bitmap.height)
        if (right - left < 16 || bottom - top < 16) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    /** 사진 픽셀 지문: 16×16 흑백 축소의 평균 대비 비트열 — 같은 사진을 다시 OCR하지 않기 위한 것. */
    private fun fingerprint(img: Bitmap): String {
        val small = Bitmap.createScaledBitmap(img, 16, 16, true)
        val px = IntArray(256)
        small.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (small !== img) small.recycle()
        val gray = px.map { (((it shr 16) and 0xff) * 3 + ((it shr 8) and 0xff) * 6 + (it and 0xff)) / 10 }
        val mean = gray.average()
        return gray.joinToString("") { if (it >= mean) "1" else "0" } + "#${img.width}x${img.height}"
    }
}
